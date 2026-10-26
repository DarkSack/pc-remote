// Publica la tienda de plugins de SackDesk.
//
//   node deploy/plugins/publicar.mjs            → genera dist/tienda-plugins (zips, catalogo.json, catalogo.sig)
//   node deploy/plugins/publicar.mjs --subir    → además lo sube al VPS
//
// Cada carpeta de agent/plugins-tienda/ es un plugin. El catálogo va firmado con
// Ed25519; el agente solo acepta catálogos firmados con la clave cuya parte pública
// lleva dentro (PluginStore.PublicKey). La privada vive fuera del repo, en
// %USERPROFILE%\.sackdesk\tienda-plugins.key: si no existe se crea y se imprime la
// pública. Sin esa clave no se puede publicar: guarda una copia.
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const repo = path.resolve(import.meta.dirname, '..', '..');
const fuente = path.join(repo, 'agent', 'plugins-tienda');
const salida = path.join(repo, 'dist', 'tienda-plugins');
const clave = path.join(os.homedir(), '.sackdesk', 'tienda-plugins.key');
const vps = 'root@144.126.155.36';
const destino = '/var/www/sackito/descargas/plugins';

function claveFirma() {
  if (!fs.existsSync(clave)) {
    const { privateKey } = crypto.generateKeyPairSync('ed25519');
    fs.mkdirSync(path.dirname(clave), { recursive: true });
    fs.writeFileSync(clave, privateKey.export({ format: 'pem', type: 'pkcs8' }), { flag: 'wx' });
    console.log(`Clave nueva en ${clave}. Haz una copia: sin ella no se puede publicar.`);
  }
  const privada = crypto.createPrivateKey(fs.readFileSync(clave));
  const publica = crypto.createPublicKey(privada).export({ format: 'der', type: 'spki' }).subarray(-32);
  return { privada, publica: publica.toString('base64') };
}

const leerJson = (f) => JSON.parse(fs.readFileSync(f, 'utf8').replace(/^\uFEFF/, ''));
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

const { privada, publica } = claveFirma();
console.log('Clave pública (PluginStore.PublicKey):', publica);

// Se vacía en vez de borrarla: Windows no deja borrar una carpeta abierta (p. ej. por un servidor de prueba).
fs.mkdirSync(salida, { recursive: true });
for (const f of fs.readdirSync(salida)) fs.rmSync(path.join(salida, f), { recursive: true, force: true });

const plugins = fs.readdirSync(fuente, { withFileTypes: true })
  .filter((d) => d.isDirectory() && fs.existsSync(path.join(fuente, d.name, 'plugin.json')))
  .map((d) => {
    const dir = path.join(fuente, d.name);
    const m = leerJson(path.join(dir, 'plugin.json'));
    const id = (m.id ?? d.name).toLowerCase();
    if (!/^[a-z0-9][a-z0-9_-]{0,39}$/.test(id)) throw new Error(`${d.name}: id no válido`);
    if (!m.version) throw new Error(`${id}: falta "version" en plugin.json`);
    if (m.assembly) throw new Error(`${id}: la tienda solo admite plugins de acciones`);

    // tar de Windows (bsdtar) hace zip con -a; el de Git Bash no.
    const file = `${id}-${m.version}.zip`;
    execFileSync(path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'tar.exe'),
      ['-a', '-cf', path.join(salida, file), '-C', dir, ...fs.readdirSync(dir)]);
    const zip = fs.readFileSync(path.join(salida, file));
    return {
      id, name: m.name ?? id, description: m.description ?? null, icon: m.icon ?? null,
      version: m.version, author: m.author ?? null, changes: m.changes ?? null,
      file, sha256: sha256(zip), bytes: zip.length,
    };
  })
  .sort((a, b) => a.id.localeCompare(b.id));

const catalogo = Buffer.from(JSON.stringify({ version: 1, generated: new Date().toISOString(), plugins }, null, 2));
fs.writeFileSync(path.join(salida, 'catalogo.json'), catalogo);
fs.writeFileSync(path.join(salida, 'catalogo.sig'), crypto.sign(null, catalogo, privada).toString('base64'));
for (const p of plugins) console.log(`  ${p.id} ${p.version}  ${(p.bytes / 1024).toFixed(1)} KB`);
console.log(`Listo en ${salida}`);

if (process.argv.includes('--subir')) {
  const run = (cmd, args) => execFileSync(cmd, args, { stdio: 'inherit' });
  run('ssh', [vps, `mkdir -p ${destino}`]);
  // Primero los zips y al final el catálogo: nunca hay un catálogo que apunte a un zip que falta.
  run('scp', ['-q', ...plugins.map((p) => path.join(salida, p.file)), `${vps}:${destino}/`]);
  run('scp', ['-q', path.join(salida, 'catalogo.json'), path.join(salida, 'catalogo.sig'), `${vps}:${destino}/`]);
  console.log(`Subido a ${destino}`);
}

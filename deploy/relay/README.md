# Relay en el VPS

El relay une el móvil y el PC cuando no se alcanzan directamente (antivirus,
dos routers, otra red). Corre en Docker detrás del Caddy del VPS, que pone el
TLS. Diseño y límites: [`docs/PROTOCOL.md`](../../docs/PROTOCOL.md#relay-cuando-el-móvil-no-llega-al-pc).

## 1 · DNS (Cloudflare, una vez)

Un registro **A** comodín a la IP del VPS, **sin proxy (nube gris)**: Caddy pide
los certificados por HTTP-01 y con la nube naranja el desafío no llega.

```
Tipo A · Nombre *.relay · Contenido 144.126.155.36 · Proxy desactivado
```

Cubre los 16 nombres `r0.relay.sackito.online` … `rf.relay.sackito.online`, uno
por primer dígito del id del PC. Si algún día un servidor no basta, se crea un
registro concreto (por ejemplo `r8.relay`) hacia otro VPS, que gana al comodín,
sin actualizar ninguna app.

## 2 · Caddy

Añadir al `Caddyfile` del VPS (en sackito-network: `infra/vps/Caddyfile`):

```caddy
# Relay de PC Remote (deploy/relay en el repo pc-remote). Un nombre por primer
# dígito del id del PC. El relay escucha en claro solo en loopback.
r0.relay.sackito.online, r1.relay.sackito.online, r2.relay.sackito.online, r3.relay.sackito.online,
r4.relay.sackito.online, r5.relay.sackito.online, r6.relay.sackito.online, r7.relay.sackito.online,
r8.relay.sackito.online, r9.relay.sackito.online, ra.relay.sackito.online, rb.relay.sackito.online,
rc.relay.sackito.online, rd.relay.sackito.online, re.relay.sackito.online, rf.relay.sackito.online {
	reverse_proxy 127.0.0.1:47900 {
		# Al recargar Caddy, los túneles abiertos no se cortan todos de golpe.
		stream_close_delay 5m
	}
}
```

y recargar: `systemctl reload caddy` (recarga sin cortar lo demás).

## 3 · El contenedor

En este PC:

```powershell
powershell -ExecutionPolicy Bypass -File deploy\relay\publicar.ps1
```

copia `deploy/relay` (con la carpeta `app` publicada) a `/opt/pcremote-relay`
del VPS, y allí:

```bash
cd /opt/pcremote-relay && docker compose up -d --build
```

Comprobar: `curl -s https://r0.relay.sackito.online/healthz` → `ok`, y en
`docker logs pcremote-relay` una línea por minuto con PCs, túneles y Mbit/s.

## Capacidad (medida el 2026-10-25)

En Docker con Linux, 1500 PCs y 4500 túneles a la vez: 0 fallos, ~1,5 Gbit/s
reenviados, ~365 MB de RAM y ~6 núcleos con mensajes pequeños de 3 KB (el peor
caso). En el VPS el límite real es el ancho de banda del enlace: una pantalla
remota usa 2–9 Mbit/s. La app usa el relay solo cuando no llega al PC directo, y
vuelve al camino directo en cuanto puede.

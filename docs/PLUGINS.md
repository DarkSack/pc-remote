# Plugins

Hay dos formas de añadir cosas a PC Remote sin tocar el código del agente:

1. **Funciones opcionales** que ya trae el agente (Terminal, Archivos). Se
   encienden y apagan en el panel.
2. **Plugins** en la carpeta de plugins del PC:
   - **de acciones** (`plugin.json`): botones que ejecutan un programa o abren
     algo. Sin compilar nada.
   - **de módulo** (`plugin.json` + una DLL de .NET): un dominio nuevo del
     protocolo, igual que los módulos integrados.

Todo se **activa solo desde el panel del PC** (`http://localhost:47810` →
«Funciones y plugins»). El móvil ve la lista y ejecuta, pero no puede encender
nada: quien tenga un móvil emparejado no gana poderes nuevos por su cuenta.
Un plugin nuevo aparece **desactivado**.

## Funciones opcionales

| Función | Por defecto | Qué da |
|---|---|---|
| `files` | activada | Explorar carpetas y unidades, abrir en el PC, subir y bajar archivos |
| `terminal` | **desactivada** | PowerShell en el PC con la salida en el móvil |

Con una función apagada, sus comandos responden `FEATURE_DISABLED` y la app
muestra cómo activarla. El estado se guarda en
`%LOCALAPPDATA%\PcRemote\features.json` (junto con el de los plugins).

## Carpeta de plugins

`%LOCALAPPDATA%\PcRemote\plugins\` (configurable con `Storage:PluginsPath`).
El menú de la bandeja tiene «Abrir carpeta de plugins». Al arrancar, el agente
crea la carpeta con un `LEEME.txt` y copia en ella los plugins incluidos (ver
abajo).

## Plugins incluidos

El agente trae estos plugins dentro del exe (su código está en
[`agent/plugins/`](../agent/plugins)). Llegan **desactivados**, como cualquier
otro. Todos corren con el usuario del PC, sin pedir administrador (salvo los
instaladores que lo pidan por su cuenta, con winget).

| Plugin | Qué hace |
|---|---|
| `pantalla` | Apagar el monitor, brillo (portátil por WMI y monitores externos por DDC/CI), modo de proyección (Win+P) |
| `energia` | Ver y cambiar el plan de energía, batería (carga, autonomía, salud), mantener el PC despierto X minutos |
| `temporizador` | Apagar, reiniciar, suspender o hibernar dentro de X minutos; ver y cancelar |
| `audio` | Pasar a la siguiente salida de audio, elegir una por número, abrir el mezclador |
| `wifi` | Conexión actual, redes guardadas y cercanas, contraseña de una red guardada, conectarse |
| `red` | IP pública, test de velocidad, traceroute, DNS, puertos en escucha, programas conectados a internet |
| `limpieza` | Cuánto se puede liberar, borrar temporales, vaciar la papelera, descargas antiguas, Liberador de espacio |
| `discos` | Espacio libre, salud (SMART según Windows), carpetas más pesadas, archivos más grandes |
| `captura` | Captura de pantalla a `Imágenes\PC Remote` (se baja desde Archivos) |
| `windows-update` | Actualizaciones pendientes, últimas instaladas, si hace falta reiniciar, buscar ahora |
| `winget` | Apps con actualización, actualizar todas o una, buscar, instalar, desinstalar |
| `navegador` | Abrir un enlace o buscar en Google, YouTube, YouTube Music o Maps |
| `avisos` | Notificación, mensaje en pantalla, leer un texto en voz alta, hacer sonar el PC |
| `escritorio` | Mostrar el escritorio, tema claro u oscuro, reiniciar el Explorador |
| `defender` | Estado del antivirus, análisis rápido, actualizar firmas, amenazas encontradas |
| `diagnostico` | Tiempo encendido, reinicios y apagados inesperados, errores de 24 h, programas de inicio, temperaturas |
| `bluetooth` | Encender, apagar, estado y dispositivos emparejados |
| `impresoras` | Cola de impresión, cancelar trabajos, página de prueba |
| `configuracion` | Accesos directos a páginas de Configuración y herramientas (Servicios, Visor de eventos…) |
| `juegos` | Steam (Big Picture, biblioteca, descargas, amigos, cerrar), Epic, modo de juego |
| `notas` | Apuntar notas desde el móvil en `Documentos\PC Remote\notas.txt` |
| `buscar-archivos` | Buscar por nombre (índice de Windows Search), últimas descargas, abiertos recientemente |
| `utilidades-windows` | El ejemplo original: ipconfig, DNS, ping, papelera, Descargas, Administrador de tareas |

La carpeta es tuya:

- Un plugin incluido se copia **una vez**. Si ya había una carpeta con ese
  nombre, no se toca.
- Cuando una versión nueva del agente trae una versión nueva de un plugin, se
  actualiza **solo si no lo has cambiado** (ni editado, ni añadido o quitado
  archivos). Si lo tocaste, se queda tu copia.
- Si borras un plugin incluido, no vuelve a aparecer.

Lo que se instaló se apunta en `plugins\.incluidos.json`. Borrar una entrada de
ese archivo (y la carpeta) hace que se vuelva a copiar al arrancar.

Los scripts son `.ps1` de Windows PowerShell 5.1 guardados en **UTF-8 con BOM**
(sin BOM, PowerShell 5.1 los lee como ANSI y rompe las tildes) y escriben su
salida en UTF-8 (`"encoding": "utf8"` en la acción).

Cada subcarpeta con un `plugin.json` es un plugin. La carpeta se relee cada vez
que el panel o el móvil piden la lista: para añadir o cambiar un plugin de
acciones basta con guardar el archivo. Los errores del manifiesto se ven en el
panel y en la app, y un plugin con errores no se puede ejecutar.

## Plugin de acciones

```json
{
  "name": "Utilidades de Windows",
  "description": "Red, papelera y carpetas.",
  "icon": "build",
  "version": "1.0.0",
  "author": "Sack",
  "actions": [
    {
      "id": "ping",
      "label": "Hacer ping",
      "icon": "network_ping",
      "run": "ping",
      "args": ["-n", "4", "{host}"],
      "params": [
        { "id": "host", "label": "Host o IP", "type": "string",
          "pattern": "^[A-Za-z0-9.:-]{1,253}$", "default": "1.1.1.1" }
      ],
      "output": true,
      "timeoutSec": 20
    },
    {
      "id": "empty-bin",
      "label": "Vaciar la papelera",
      "run": "powershell.exe",
      "args": ["-NoProfile", "-Command", "Clear-RecycleBin -Force"],
      "confirm": "¿Vaciar la papelera del PC? No se puede deshacer."
    },
    { "id": "downloads", "label": "Abrir Descargas", "open": "%USERPROFILE%\\Downloads" }
  ]
}
```

### Campos del plugin

| Campo | | |
|---|---|---|
| `id` | opcional | Por defecto, el nombre de la carpeta. Minúsculas, números, `-`, `_` (máx. 40) |
| `name`, `description`, `icon`, `version`, `author` | opcionales | Lo que se muestra. `icon` es un nombre de Material Symbols (`build`, `lan`, `dns`, `folder`, `delete`, `terminal`, `monitoring`, `play_arrow`, `download`, `settings`, `wifi`, `code`…) |
| `actions` | | Lista de acciones |
| `assembly` | opcional | DLL de un plugin de módulo (ver abajo) |

### Campos de una acción

| Campo | | |
|---|---|---|
| `id` | obligatorio | Único dentro del plugin |
| `label`, `description`, `icon` | | Texto e icono del botón |
| `run` | `run` **o** `open` | Programa a ejecutar (ruta o nombre en el `PATH`). Admite variables de entorno (`%USERPROFILE%`) |
| `args` | | Lista de argumentos. **Cada elemento es un argumento**: se pasan con `ArgumentList`, sin consola, así que un valor con espacios o comillas nunca se parte ni se interpreta |
| `open` | `run` **o** `open` | URL, archivo o carpeta que se abre con la app predeterminada |
| `params` | | Valores que se piden en el móvil antes de ejecutar |
| `confirm` | | Texto de confirmación (la app lo muestra antes de ejecutar). Admite `{param}`: se ve el valor escrito |
| `output` | `false` | `true` = esperar a que termine y mostrar la salida en el móvil |
| `timeoutSec` | `30` | 1–600. Pasado ese tiempo se mata el proceso (y sus hijos) |
| `encoding` | | Codificación de la salida. Por defecto la de la consola (OEM), que es la que usan `ipconfig`, `ping`… |

La salida se recorta a 64 KB.

### Parámetros

| Campo | | |
|---|---|---|
| `id` | obligatorio | Se usa como `{id}` dentro de `args` |
| `label`, `placeholder` | | Texto del campo |
| `type` | `string` | `string`, `number`, `bool` o `choice` |
| `required` | `true` | |
| `default` | | Valor inicial |
| `options` | con `choice` | Valores permitidos |
| `pattern` | | Regex que tiene que cumplir un `string` (entero, `^…$`) |
| `min`, `max` | | Límites de un `number` |
| `maxLength` | `256` | Longitud máxima de un `string` |

**Los parámetros solo pueden aparecer en `args`.** En `run` u `open` serían el
propio programa o la URL, y eso no lo decide el móvil: el manifiesto lo rechaza.
El agente valida cada valor (tipo, patrón, límites, opciones) antes de ejecutar
y rechaza saltos de línea y caracteres de control. Aun así, un argumento sigue
siendo lo que el programa haga con él: si pasas `{texto}` a `powershell -Command`,
el móvil puede escribir PowerShell. Usa `pattern` o `choice` para acotarlo.

## Plugin de módulo (.NET)

Para algo que no cabe en "ejecutar un programa": un dominio nuevo del protocolo.

```json
{
  "name": "Mi módulo",
  "assembly": "MiModulo.dll"
}
```

La DLL implementa `ICommandModule` (y opcionalmente `IStreamModule`,
`ISessionAware`) de `PcRemote.Core`, igual que los módulos de `agent/src/`:

```csharp
public sealed class MiModulo : ICommandModule
{
    public string Domain => "mimodulo";
    public IReadOnlyList<CommandDescriptor> Commands { get; } = [ new("hola", "Saluda") ];

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct) =>
        Task.FromResult(req.Action == "hola"
            ? CommandResponse.Ok(req.Id, new { mensaje = "Hola desde el PC" })
            : CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, "Acción desconocida"));
}
```

Compílala contra la misma versión de `PcRemote.Core` que el agente y copia la
DLL (y sus dependencias que no traiga el agente) en la carpeta del plugin.

- Se carga **al arrancar el agente** y solo si está activado: activarlo o
  desactivarlo pide reiniciar el agente (el panel lo indica).
- Corre dentro del agente, con sus permisos. **Solo instala DLLs en las que
  confíes** tanto como en el propio agente.
- Si su dominio coincide con uno existente, se ignora (queda en el log).
- Mientras está desactivado, sus comandos responden `FEATURE_DISABLED`.

## Protocolo

`plugins.list` y `plugins.run` están en [PROTOCOL.md](PROTOCOL.md#plugins).
Cada ejecución queda en la actividad del PC (`activity`).

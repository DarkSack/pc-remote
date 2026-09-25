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
El menú de la bandeja tiene «Abrir carpeta de plugins». La primera vez se crea
con un `LEEME.txt` y un ejemplo, `utilidades-windows`.

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
| `confirm` | | Texto de confirmación (la app lo muestra antes de ejecutar) |
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

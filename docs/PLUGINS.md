# Plugins

Todo lo que el móvil puede hacer en el PC es un **plugin**: un módulo del
agente con su dominio (`system`, `media`, `terminal`…). Desde el **panel del
agente** (`http://localhost:47810/` → *Plugins*) se ve cada uno y se activa o
desactiva con un interruptor.

- Se activan y desactivan **solo en el PC**. El móvil puede ver la lista
  (`plugins.list`) pero no cambiarla: encender la terminal tiene que hacerlo
  alguien sentado delante del PC.
- Un plugin desactivado responde `PLUGIN_DISABLED` y sus streams se cortan al
  momento. La app lo muestra como "desactivado en el PC" en vez de fallar.
- La elección se guarda en `%LOCALAPPDATA%\PcRemote\plugins.json`.

## Integrados

| Plugin | Dominio | Por defecto | Qué hace |
|---|---|---|---|
| Energía | `system` | ✅ | Apagar, reiniciar, suspender, hibernar, bloquear, cerrar sesión |
| Monitor del sistema | `systeminfo` | ✅ | CPU, RAM, GPU, discos, red; info del equipo |
| Ratón y teclado | `input` | ✅ | Touchpad, teclado, atajos |
| Portapapeles | `clipboard` | ✅ | Leer y escribir texto e imágenes |
| Historial del portapapeles | `cliphistory` | ✅ | Todo lo copiado en el PC (texto, imágenes, archivos), solo en memoria |
| Multimedia | `media` | ✅ | Lo que suena, controles y volumen |
| Apps / Iconos de apps | `applications`, `appicons` | ✅ | Lanzador |
| Ventanas | `windows` | ✅ | Enfocar, minimizar, maximizar, cerrar |
| Procesos | `processes` | ✅ | Consumo y finalizar |
| Archivos | `files` | ✅ | Explorar, abrir en el PC, descargar al móvil |
| Red | `network` | ✅ | Interfaces, conexiones, ping desde el PC |
| Actividad | `activity` | ✅ | Conexiones, comandos y alertas |
| **Terminal** | `terminal` | ❌ | PowerShell / cmd como tu usuario. **Ejecuta cualquier cosa**: actívalo solo si lo necesitas |
| Ping, Plugins | `ping`, `plugins` | siempre | Los necesita el protocolo; no se pueden desactivar |

## Plugins externos

Un plugin externo es una DLL de .NET que implementa `ICommandModule` (y
opcionalmente `IStreamModule`, `ISessionAware` e `IPluginMetadata`).

1. Copia la DLL en `%LOCALAPPDATA%\PcRemote\plugins\` (bandeja → *Abrir
   carpeta de plugins*). Si tiene dependencias, ponla en una subcarpeta con su
   mismo nombre: `plugins\MiPlugin\MiPlugin.dll` + sus DLL.
2. Reinicia el agente: aparece en el panel como **externo**, desactivado.
3. Actívalo y reinicia otra vez para cargarlo.

**Una DLL desactivada no se carga nunca**, así que ningún código suyo se
ejecuta hasta que la activas. Una vez activada corre dentro del agente con
los mismos permisos que él: instala solo plugins en los que confíes.

### Escribir uno

```xml
<!-- MiPlugin.csproj -->
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net10.0-windows10.0.19041.0</TargetFramework>
    <ImplicitUsings>enable</ImplicitUsings>
    <Nullable>enable</Nullable>
  </PropertyGroup>
  <ItemGroup>
    <!-- Solo para compilar: el agente ya trae PcRemote.Core. -->
    <Reference Include="PcRemote.Core" HintPath="ruta\a\PcRemote.Core.dll" Private="false" />
  </ItemGroup>
</Project>
```

```csharp
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

public sealed class HelloModule : ICommandModule, IPluginMetadata
{
    public string Domain => "hello";

    public string DisplayName => "Hola";
    public string Description => "Ejemplo de plugin externo.";
    public string Category => PluginCategories.Tools;

    public IReadOnlyList<CommandDescriptor> Commands { get; } =
        new[] { new CommandDescriptor("greet", "Saluda") };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct) =>
        Task.FromResult(req.Action switch
        {
            "greet" => CommandResponse.Ok(req.Id, new { message = $"Hola, {session.DeviceName}" }),
            _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
        });
}
```

Reglas que el agente espera de un módulo:

- **Comandos tipados**, nunca "ejecuta este string" (salvo el plugin
  Terminal, que existe justo para eso y viene apagado).
- Valida los parámetros: un parámetro que falta o con otro tipo debe acabar en
  `INVALID_PARAMS`, no en una excepción sin capturar.
- No bloquees: cada dominio tiene su propia cola, pero un comando lento
  retrasa los siguientes del mismo dominio.
- El constructor puede pedir servicios del agente por inyección
  (`ActivityLog`, `PluginManager`, `AgentSettings`…).
- Si dos módulos declaran el mismo dominio, gana el primero (los integrados) y
  el otro se ignora con un aviso en el registro.

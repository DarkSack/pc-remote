# PC Remote · Pantalla: modo de proyección (Win+P).
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$modos = @{
    'Extender'              = '/extend'
    'Duplicar'              = '/clone'
    'Solo pantalla del PC'  = '/internal'
    'Solo segunda pantalla' = '/external'
}
$modo = "$($args[0])"
if (-not $modos.ContainsKey($modo)) { "Modo desconocido: $modo"; exit 1 }

& "$env:WINDIR\System32\DisplaySwitch.exe" $modos[$modo]
"Modo de proyección: $modo."

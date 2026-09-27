# PC Remote · Wi-Fi: contraseña de una red guardada.
# netsh la muestra con "key=clear" para las redes del usuario, sin permisos de administrador.
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$red = "$($args[0])"
$salida = & netsh.exe wlan show profile "name=$red" key=clear
if ($LASTEXITCODE -ne 0) { "No hay ninguna red guardada llamada «$red»."; ''; $salida; exit 1 }

# "Contenido de la clave" / "Key Content" según el idioma de Windows.
$linea = $salida | Where-Object { $_ -match '^\s*(Contenido de la clave|Key Content)\s*:\s*(.*)$' } | Select-Object -First 1
if ($linea -and $Matches[2]) {
    "Red: $red"
    "Contraseña: $($Matches[2].Trim())"
} else {
    "La red «$red» no tiene contraseña guardada (abierta o de empresa)."
}

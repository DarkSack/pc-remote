# PC Remote · Energía: ver o cambiar el plan de energía.
#   plan.ps1          → plan activo y los disponibles
#   plan.ps1 <nombre> → activa ese plan
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

# Alias de powercfg: no dependen del idioma de Windows ni del GUID de cada equipo.
$planes = @{
    'Equilibrado'      = 'SCHEME_BALANCED'
    'Alto rendimiento' = 'SCHEME_MIN'
    'Economizador'     = 'SCHEME_MAX'
}

$elegido = "$($args[0])"
if ($elegido -ne '') {
    if (-not $planes.ContainsKey($elegido)) { "Plan desconocido: $elegido"; exit 1 }
    $salida = powercfg /setactive $planes[$elegido] 2>&1
    if ($LASTEXITCODE -ne 0) {
        "No se pudo activar «$elegido». En algunos portátiles con Modern Standby solo existe el plan Equilibrado."
        $salida
        exit 1
    }
    "Plan activado: $elegido"
    ''
}

# Solo las líneas de los planes (llevan su GUID); la cabecera también tiene paréntesis.
$lista = powercfg /list | Where-Object { $_ -match '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' }
$activo = ($lista | Where-Object { $_ -match '\*\s*$' }) -replace '^.*\((.+)\).*$', '$1'
"Plan activo: $activo"
''
'Disponibles:'
$lista | Where-Object { $_ -match '\((.+)\)' } | ForEach-Object {
    $nombre = $_ -replace '^.*\((.+)\).*$', '$1'
    if ($_ -match '\*\s*$') { "  • $nombre (activo)" } else { "  • $nombre" }
}

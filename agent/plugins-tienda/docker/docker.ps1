# SackDesk · Docker (tienda de plugins).
#   docker.ps1 enmarcha | todos | recursos | arrancar|parar|reiniciar <nombre> | abrir
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Docker {
    $cli = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $cli) { Write-Output 'No encuentro Docker: instala Docker Desktop o añade docker al PATH.'; exit 1 }
    # Los errores de docker (p. ej. Docker Desktop cerrado) van a la salida junto con lo demás.
    $ErrorActionPreference = 'Continue'
    $salida = & $cli.Source @args 2>&1 | ForEach-Object { "$_" }
    $script:codigo = $LASTEXITCODE
    $salida
}

function Tabla($salida, $vacio) {
    if (@($salida).Count -le 1 -and $script:codigo -eq 0) { $vacio } else { $salida }
}

switch ("$($args[0])") {
    'enmarcha' { Tabla (Docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}') 'No hay contenedores en marcha.' }
    'todos'    { Tabla (Docker ps -a --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}') 'No hay contenedores.' }
    'recursos' { Tabla (Docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}') 'No hay contenedores en marcha.' }
    { $_ -in 'arrancar', 'parar', 'reiniciar' } {
        $verbo, $hecho = @{ arrancar = 'start', 'arrancado'; parar = 'stop', 'parado'; reiniciar = 'restart', 'reiniciado' }[$_]
        $salida = Docker $verbo "$($args[1])"
        if ($script:codigo -eq 0) { "Contenedor $($args[1]) $hecho." } else { $salida }
    }
    'abrir' {
        $exe = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
        if (-not (Test-Path $exe)) { 'No encuentro Docker Desktop en Archivos de programa.'; exit 1 }
        Start-Process $exe
        'Abriendo Docker Desktop.'
    }
    default { "Acción desconocida: $($args[0])"; exit 1 }
}
if ($script:codigo) { exit $script:codigo }

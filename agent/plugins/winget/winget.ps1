# PC Remote · Apps con winget.
#   winget.ps1 actualizables | actualizar-todo | buscar <texto> | instalar <id> | actualizar <id> | desinstalar <id>
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

# winget es un alias de ejecución de la Microsoft Store: no siempre está en el PATH del proceso.
$winget = (Get-Command winget.exe -ErrorAction SilentlyContinue).Source
if (-not $winget) { $winget = Join-Path $env:LOCALAPPDATA 'Microsoft\WindowsApps\winget.exe' }
if (-not (Test-Path $winget)) { 'winget no está instalado. Se instala con «Instalador de aplicación» desde la Microsoft Store.'; exit 1 }

$comunes = @('--accept-source-agreements', '--disable-interactivity')
$instalar = @('--exact', '--silent', '--accept-package-agreements') + $comunes

# winget dibuja barras de progreso con \r y caracteres de bloque: en el móvil solo estorban.
function Invoke-Winget([string[]]$argumentos) {
    & $winget @argumentos 2>&1 | ForEach-Object {
        $l = ("$_" -split "`r")[-1].TrimEnd()
        if ($l -and $l -notmatch '^[\s\-\\|/█▒░]+$' -and $l -notmatch '^\s*[\d.]+\s*[KMG]?B\s*/\s*[\d.]+\s*[KMG]?B\s*$') { $l }
    }
    return
}

$id = "$($args[1])"
switch ("$($args[0])") {
    'actualizables'   { Invoke-Winget (@('upgrade') + $comunes) }
    'actualizar-todo' { Invoke-Winget (@('upgrade', '--all', '--silent', '--accept-package-agreements') + $comunes) }
    'buscar'          { Invoke-Winget (@('search', '--query', $id) + $comunes) }
    'instalar'        { Invoke-Winget (@('install', '--id', $id) + $instalar) }
    'actualizar'      { Invoke-Winget (@('upgrade', '--id', $id) + $instalar) }
    'desinstalar'     { Invoke-Winget (@('uninstall', '--id', $id, '--exact', '--silent') + $comunes) }
    default           { "Modo desconocido: $($args[0])"; exit 1 }
}
exit $LASTEXITCODE

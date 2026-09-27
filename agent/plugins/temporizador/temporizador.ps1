# PC Remote · Temporizador de apagado.
#   temporizador.ps1 Apagar|Reiniciar|Suspender|Hibernar <minutos>
#   temporizador.ps1 Estado
#   temporizador.ps1 Cancelar
# Apagar y reiniciar usan shutdown.exe /t (Windows avisa y se cancela con shutdown /a).
# Suspender e hibernar no existen en shutdown /t: un PowerShell oculto espera y llama a
# SetSuspendState.
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$estado = Join-Path $env:TEMP 'pcremote-temporizador.json'

function Read-Estado {
    if (-not (Test-Path $estado)) { return $null }
    $info = Get-Content $estado -Raw | ConvertFrom-Json
    if ([datetime]::Parse($info.cuando) -lt (Get-Date)) { Remove-Item $estado -Force; return $null }
    return $info
}

function Stop-Todo {
    $habia = $false
    $info = Read-Estado
    if ($info -and $info.pid) {
        $p = Get-Process -Id $info.pid -ErrorAction SilentlyContinue
        if ($p -and $p.StartTime.ToUniversalTime().Ticks -eq [long]$info.start) { Stop-Process -Id $info.pid -Force; $habia = $true }
    }
    # Un apagado pendiente de shutdown /t (nuestro o no): /a lo cancela; si no hay ninguno, falla sin más.
    & shutdown.exe /a 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $habia = $true }
    if (Test-Path $estado) { Remove-Item $estado -Force }
    return $habia
}

$accion = "$($args[0])"

switch ($accion) {
    'Estado' {
        $info = Read-Estado
        if ($info) { "Programado: $($info.accion) a las $(([datetime]::Parse($info.cuando)).ToString('HH:mm')) (faltan $([int][Math]::Ceiling(([datetime]::Parse($info.cuando) - (Get-Date)).TotalMinutes)) min)." }
        else { 'No hay nada programado desde PC Remote.' }
        exit 0
    }
    'Cancelar' {
        if (Stop-Todo) { 'Cancelado.' } else { 'No había nada programado.' }
        exit 0
    }
}

if ($accion -notin 'Apagar', 'Reiniciar', 'Suspender', 'Hibernar') { "Acción desconocida: $accion"; exit 1 }
$minutos = [int][Math]::Round([double]::Parse("$($args[1])", [Globalization.CultureInfo]::InvariantCulture))
if ($minutos -lt 1) { 'Los minutos tienen que ser 1 o más.'; exit 1 }

[void](Stop-Todo)
$cuando = (Get-Date).AddMinutes($minutos)
$info = @{ accion = $accion; cuando = $cuando.ToString('o') }

if ($accion -in 'Apagar', 'Reiniciar') {
    $modo = if ($accion -eq 'Apagar') { '/s' } else { '/r' }
    & shutdown.exe $modo /t ($minutos * 60) /c "Programado desde PC Remote. Se cancela desde el móvil o con: shutdown /a"
    if ($LASTEXITCODE -ne 0) { "shutdown.exe falló (código $LASTEXITCODE)."; exit 1 }
} else {
    $hibernar = if ($accion -eq 'Hibernar') { '$true' } else { '$false' }
    $codigo = @"
Start-Sleep -Seconds $($minutos * 60)
Add-Type -Namespace PcRemote -Name Suspend -MemberDefinition '[DllImport("powrprof.dll")] public static extern bool SetSuspendState(bool hibernate, bool force, bool disableWake);'
[void][PcRemote.Suspend]::SetSuspendState($hibernar, `$false, `$false)
"@
    $cifrado = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($codigo))
    $p = Start-Process powershell.exe -WindowStyle Hidden -PassThru -ArgumentList '-NoProfile', '-NonInteractive', '-EncodedCommand', $cifrado
    $info.pid = $p.Id
    $info.start = $p.StartTime.ToUniversalTime().Ticks
}

$info | ConvertTo-Json | Set-Content $estado
"$accion a las $($cuando.ToString('HH:mm')) (dentro de $minutos min). Se cancela con «Cancelar»."

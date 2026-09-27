# PC Remote · Energía: mantener el PC despierto.
#   despierto.ps1 <minutos> → no se suspende ni apaga la pantalla durante ese tiempo
#   despierto.ps1 0         → cancelar
# Un PowerShell oculto pide SetThreadExecutionState y duerme; al terminar (o al matarlo)
# Windows vuelve a su comportamiento normal. Nada queda cambiado en la configuración.
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$estado = Join-Path $env:TEMP 'pcremote-despierto.json'

function Stop-Anterior {
    if (-not (Test-Path $estado)) { return $false }
    $info = Get-Content $estado -Raw | ConvertFrom-Json
    Remove-Item $estado -Force
    $p = Get-Process -Id $info.pid -ErrorAction SilentlyContinue
    # Solo si es el mismo proceso que lanzamos (el PID puede haberse reutilizado).
    if ($p -and $p.StartTime.ToUniversalTime().Ticks -eq [long]$info.start) {
        Stop-Process -Id $info.pid -Force
        return $true
    }
    return $false
}

$minutos = [int][Math]::Round([double]::Parse("$($args[0])", [Globalization.CultureInfo]::InvariantCulture))
$habia = Stop-Anterior

if ($minutos -le 0) {
    if ($habia) { 'Listo: el PC vuelve a suspenderse con normalidad.' } else { 'No había nada que cancelar.' }
    exit 0
}

# ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED
$codigo = @"
Add-Type -Namespace PcRemote -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
[void][PcRemote.Power]::SetThreadExecutionState([uint32]2147483651)
Start-Sleep -Seconds $($minutos * 60)
"@
$cifrado = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($codigo))
$p = Start-Process powershell.exe -WindowStyle Hidden -PassThru -ArgumentList '-NoProfile', '-NonInteractive', '-EncodedCommand', $cifrado

@{ pid = $p.Id; start = $p.StartTime.ToUniversalTime().Ticks; hasta = (Get-Date).AddMinutes($minutos).ToString('HH:mm') } |
    ConvertTo-Json | Set-Content $estado

"El PC se mantendrá despierto hasta las $((Get-Date).AddMinutes($minutos).ToString('HH:mm')) ($minutos min)."

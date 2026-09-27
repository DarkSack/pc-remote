# PC Remote · Bluetooth.
#   bluetooth.ps1 estado | encender | apagar | dispositivos
# La radio se maneja con Windows.Devices.Radios (lo mismo que el botón del centro de actividades).
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() |
    Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } |
    Select-Object -First 1

function Wait-WinRt($operacion, [Type]$tipo) {
    $tarea = $asTask.MakeGenericMethod($tipo).Invoke($null, @($operacion))
    [void]$tarea.Wait(10000)
    return $tarea.Result
}

[void][Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime]
[void][Windows.Devices.Radios.RadioAccessStatus, Windows.System.Devices, ContentType = WindowsRuntime]
[void][Windows.Devices.Radios.RadioState, Windows.System.Devices, ContentType = WindowsRuntime]

function Get-Radio {
    [void](Wait-WinRt ([Windows.Devices.Radios.Radio]::RequestAccessAsync()) ([Windows.Devices.Radios.RadioAccessStatus]))
    $radios = Wait-WinRt ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]])
    $bt = $radios | Where-Object { $_.Kind -eq 'Bluetooth' } | Select-Object -First 1
    if (-not $bt) { 'Este PC no tiene Bluetooth (o está deshabilitado en el Administrador de dispositivos).'; exit 1 }
    return $bt
}

function Set-Radio([string]$estado) {
    $bt = Get-Radio
    $r = Wait-WinRt ($bt.SetStateAsync($estado)) ([Windows.Devices.Radios.RadioAccessStatus])
    if ("$r" -ne 'Allowed') { "Windows no dejó cambiar el Bluetooth ($r)."; exit 1 }
}

switch ("$($args[0])") {
    'estado'   { $bt = Get-Radio; if ("$($bt.State)" -eq 'On') { 'Bluetooth encendido.' } else { 'Bluetooth apagado.' } }
    'encender' { Set-Radio 'On'; 'Bluetooth encendido.' }
    'apagar'   { Set-Radio 'Off'; 'Bluetooth apagado.' }
    'dispositivos' {
        # Los emparejados cuelgan de BTHENUM (clásicos) o BTHLE (Low Energy); el resto son servicios internos.
        $lista = Get-PnpDevice -Class Bluetooth -ErrorAction SilentlyContinue |
            Where-Object { $_.InstanceId -match '^(BTHENUM\\DEV_|BTHLE\\DEV_)' } |
            Sort-Object FriendlyName -Unique
        if (-not $lista) { 'No hay dispositivos Bluetooth emparejados.'; exit 0 }
        foreach ($d in $lista) {
            $estado = if ($d.Status -eq 'OK') { 'disponible' } else { 'no conectado' }
            "• $($d.FriendlyName) ($estado)"
        }
    }
    default { "Modo desconocido: $($args[0])"; exit 1 }
}

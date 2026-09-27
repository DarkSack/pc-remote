# PC Remote · Energía: estado de la batería.
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$baterias = @(Get-CimInstance -ClassName Win32_Battery)
if ($baterias.Count -eq 0) { 'Este PC no tiene batería (o Windows no la ve).'; exit 0 }

$estados = @{
    1 = 'Descargando'; 2 = 'Enchufado'; 3 = 'Carga completa'; 4 = 'Baja'; 5 = 'Crítica'
    6 = 'Cargando'; 7 = 'Cargando (alta)'; 8 = 'Cargando (baja)'; 9 = 'Cargando (crítica)'
    10 = 'Sin definir'; 11 = 'Parcialmente cargada'
}

foreach ($b in $baterias) {
    $estado = $estados[[int]$b.BatteryStatus]
    if (-not $estado) { $estado = "Estado $($b.BatteryStatus)" }
    "$($b.Name): $($b.EstimatedChargeRemaining) % · $estado"

    # 71582788 = "desconocido" (enchufado o calculando).
    if ($b.EstimatedRunTime -and $b.EstimatedRunTime -lt 71582788) {
        $t = [TimeSpan]::FromMinutes($b.EstimatedRunTime)
        "Autonomía estimada: {0} h {1:00} min" -f [int][Math]::Floor($t.TotalHours), $t.Minutes
    }
}

try {
    $diseno = (Get-CimInstance -Namespace root/wmi -ClassName BatteryStaticData -ErrorAction Stop | Select-Object -First 1).DesignedCapacity
    $plena = (Get-CimInstance -Namespace root/wmi -ClassName BatteryFullChargedCapacity -ErrorAction Stop | Select-Object -First 1).FullChargedCapacity
    if ($diseno -gt 0 -and $plena -gt 0) {
        "Salud: {0:0} % de la capacidad original ({1:N0} de {2:N0} mWh)" -f ($plena * 100.0 / $diseno), $plena, $diseno
    }
} catch { }

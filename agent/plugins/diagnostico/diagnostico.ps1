# PC Remote · Diagnóstico.
#   diagnostico.ps1 encendido | reinicios | errores | inicio | temperaturas
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Format-Duracion([TimeSpan]$t) {
    $partes = @()
    if ($t.Days) { $partes += "$($t.Days) d" }
    if ($t.Hours) { $partes += "$($t.Hours) h" }
    $partes += "$($t.Minutes) min"
    return $partes -join ' '
}

switch ("$($args[0])") {
    'encendido' {
        $os = Get-CimInstance Win32_OperatingSystem
        "Encendido desde: $($os.LastBootUpTime.ToString('dd/MM/yy HH:mm'))"
        "Lleva: $(Format-Duracion ((Get-Date) - $os.LastBootUpTime))"
        # Con el inicio rápido, "Apagar" no reinicia el kernel: el contador sigue.
        $rapido = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power' -ErrorAction SilentlyContinue).HiberbootEnabled
        if ($rapido -eq 1) { ''; 'Nota: el inicio rápido está activado; apagar y encender no reinicia este contador. Reiniciar sí.' }
    }

    'reinicios' {
        # 1074 apagado/reinicio pedido · 6008 apagado inesperado · 41 se perdió la energía · 1001 pantallazo azul
        $eventos = Get-WinEvent -FilterHashtable @{ LogName = 'System'; Id = 1074, 6008, 41, 1001 } -MaxEvents 60 -ErrorAction SilentlyContinue |
            Where-Object { $_.Id -ne 1001 -or $_.ProviderName -match 'BugCheck|WER-SystemErrorReporting' } |
            Select-Object -First 15
        if (-not $eventos) { 'No hay reinicios ni apagados registrados.'; exit 0 }
        foreach ($e in $eventos) {
            $que = switch ($e.Id) {
                1074 {
                    $p = $e.Properties
                    $tipo = "$($p[4].Value)"; $proc = [IO.Path]::GetFileName("$($p[0].Value)" -replace ' \(.*$', '')
                    "$tipo (por $proc)"
                }
                6008 { 'Apagado inesperado' }
                41   { 'Se perdió la energía o se colgó (Kernel-Power 41)' }
                1001 { 'Pantallazo azul' }
            }
            "{0:dd/MM/yy HH:mm}  {1}" -f $e.TimeCreated, $que
        }
    }

    'errores' {
        $desde = (Get-Date).AddDays(-1)
        $eventos = @(Get-WinEvent -FilterHashtable @{ LogName = 'System', 'Application'; Level = 1, 2; StartTime = $desde } -MaxEvents 300 -ErrorAction SilentlyContinue)
        if ($eventos.Count -eq 0) { 'Ningún error en las últimas 24 horas.'; exit 0 }
        "$($eventos.Count) error(es) en 24 h, agrupados por origen:"
        ''
        $eventos | Group-Object ProviderName | Sort-Object Count -Descending | Select-Object -First 12 | ForEach-Object {
            $ultimo = $_.Group | Sort-Object TimeCreated -Descending | Select-Object -First 1
            $msg = "$($ultimo.Message)" -split "`n" | Select-Object -First 1
            if ($msg.Length -gt 140) { $msg = $msg.Substring(0, 140) + '…' }
            "• $($_.Name) ×$($_.Count) (último {0:HH:mm})" -f $ultimo.TimeCreated
            if ($msg.Trim()) { "  $($msg.Trim())" }
        }
    }

    'inicio' {
        $filas = Get-CimInstance Win32_StartupCommand | Sort-Object Name -Unique
        # Los desactivados en el Administrador de tareas siguen aquí; su estado está en StartupApproved.
        $aprobado = @{}
        foreach ($r in 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run',
                       'HKLM:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run',
                       'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\StartupFolder') {
            $k = Get-Item $r -ErrorAction SilentlyContinue
            if ($k) { foreach ($n in $k.GetValueNames()) { $aprobado[$n] = ($k.GetValue($n))[0] -eq 2 -or ($k.GetValue($n))[0] -eq 6 } }
        }
        foreach ($f in $filas) {
            $estado = if ($aprobado.ContainsKey($f.Name) -and -not $aprobado[$f.Name]) { '(desactivado)' } else { '' }
            "• $($f.Name) $estado".TrimEnd()
        }
        ''
        'Se activan o desactivan en el Administrador de tareas → Aplicaciones de arranque.'
    }

    'temperaturas' {
        $hay = $false
        try {
            Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature -ErrorAction Stop | ForEach-Object {
                "Zona térmica {0}: {1:0} °C" -f ($_.InstanceName -replace '^.*\\', ''), ($_.CurrentTemperature / 10 - 273.15)
                $hay = $true
            }
        } catch { }
        try {
            Get-CimInstance -Namespace root/cimv2 -ClassName Win32_PerfFormattedData_Counters_ThermalZoneInformation -ErrorAction Stop | ForEach-Object {
                if ($_.Temperature -gt 0) { "{0}: {1:0} °C" -f ($_.Name -replace '^.*\\', ''), ($_.Temperature - 273.15); $hay = $true }
            }
        } catch { }
        Get-CimInstance Win32_Fan -ErrorAction SilentlyContinue | ForEach-Object {
            "Ventilador $($_.Name): $($_.Status)"; $hay = $true
        }
        if (-not $hay) {
            'Este equipo no expone temperaturas a Windows sin programas del fabricante.'
            'Para CPU y GPU, programas como HWiNFO o LibreHardwareMonitor sí las leen.'
        }
    }

    default { "Modo desconocido: $($args[0])"; exit 1 }
}

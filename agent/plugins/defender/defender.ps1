# PC Remote · Seguridad de Windows (Microsoft Defender).
#   defender.ps1 estado | analisis | firmas | amenazas
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Get-Estado {
    try { return Get-MpComputerStatus }
    catch {
        'No se pudo leer el estado de Microsoft Defender. Si usas otro antivirus, Defender queda desactivado y lo gestiona ese programa.'
        exit 1
    }
}

function SiNo($b) { if ($b) { 'sí' } else { 'NO' } }

$mpcmd = Join-Path $env:ProgramFiles 'Windows Defender\MpCmdRun.exe'

switch ("$($args[0])") {
    'estado' {
        $s = Get-Estado
        $otro = Get-CimInstance -Namespace root/SecurityCenter2 -ClassName AntiVirusProduct -ErrorAction SilentlyContinue |
            Where-Object { $_.displayName -notmatch 'Defender' } | Select-Object -ExpandProperty displayName
        if ($otro) { "Antivirus instalado: $($otro -join ', ')" }
        "Antivirus de Windows activo: $(SiNo $s.AntivirusEnabled)"
        "Protección en tiempo real: $(SiNo $s.RealTimeProtectionEnabled)"
        if ($otro -and -not $s.AntivirusEnabled) { 'Defender está en modo pasivo: la protección la da el otro antivirus.' }
        if ($s.AntivirusSignatureLastUpdated) { "Firmas: $($s.AntivirusSignatureVersion), del $($s.AntivirusSignatureLastUpdated.ToString('dd/MM/yy HH:mm'))" }
        if ($s.QuickScanEndTime) { "Último análisis rápido: $($s.QuickScanEndTime.ToString('dd/MM/yy HH:mm'))" }
        if ($s.FullScanEndTime) { "Último análisis completo: $($s.FullScanEndTime.ToString('dd/MM/yy HH:mm'))" }
        if ($s.IsTamperProtected -ne $null) { "Protección contra alteraciones: $(SiNo $s.IsTamperProtected)" }
    }

    'analisis' {
        [void](Get-Estado)
        $reloj = [Diagnostics.Stopwatch]::StartNew()
        $salida = & $mpcmd -Scan -ScanType 1 2>&1
        $codigo = $LASTEXITCODE
        "Análisis rápido terminado en {0:0} min." -f $reloj.Elapsed.TotalMinutes
        $salida | Where-Object { "$_".Trim() } | Select-Object -Last 8
        if ($codigo -eq 2) { ''; 'Se encontraron amenazas: mira «Amenazas encontradas».' }
    }

    'firmas' {
        $antes = (Get-Estado).AntivirusSignatureVersion
        $salida = & $mpcmd -SignatureUpdate 2>&1
        $despues = (Get-MpComputerStatus).AntivirusSignatureVersion
        if ($despues -ne $antes) { "Firmas actualizadas: $antes → $despues" }
        else { "Las firmas ya estaban al día ($despues)."; $salida | Where-Object { "$_".Trim() } | Select-Object -Last 3 }
    }

    'amenazas' {
        [void](Get-Estado)
        $det = @(Get-MpThreatDetection -ErrorAction SilentlyContinue | Sort-Object InitialDetectionTime -Descending | Select-Object -First 10)
        if ($det.Count -eq 0) { 'No hay amenazas registradas.'; exit 0 }
        $nombres = @{}
        Get-MpThreat -ErrorAction SilentlyContinue | ForEach-Object { $nombres[$_.ThreatID] = $_.ThreatName }
        foreach ($d in $det) {
            $nombre = $nombres[$d.ThreatID]; if (-not $nombre) { $nombre = "Amenaza $($d.ThreatID)" }
            "{0:dd/MM/yy HH:mm}  {1}" -f $d.InitialDetectionTime, $nombre
            $d.Resources | Select-Object -First 2 | ForEach-Object { "    $_" }
        }
    }

    default { "Modo desconocido: $($args[0])"; exit 1 }
}

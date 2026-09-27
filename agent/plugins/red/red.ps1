# PC Remote · Red e internet.
#   red.ps1 ip | velocidad | puertos | conexiones
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-Nombres {
    $n = @{}
    Get-Process | ForEach-Object { $n[$_.Id] = $_.ProcessName }
    return $n
}

switch ("$($args[0])") {
    'ip' {
        try {
            $i = Invoke-RestMethod -Uri 'https://ipinfo.io/json' -TimeoutSec 10 -UseBasicParsing
            "IP pública: $($i.ip)"
            if ($i.city) { "Ubicación: $($i.city), $($i.region), $($i.country)" }
            if ($i.org) { "Proveedor: $($i.org)" }
        } catch {
            "IP pública: $((Invoke-RestMethod -Uri 'https://api.ipify.org' -TimeoutSec 10 -UseBasicParsing).ToString().Trim())"
        }
    }

    'velocidad' {
        $pings = Test-Connection -ComputerName 1.1.1.1 -Count 4 -ErrorAction SilentlyContinue
        if ($pings) {
            $t = $pings | Measure-Object -Property ResponseTime -Average -Minimum -Maximum
            "Latencia a 1.1.1.1: {0:0} ms (mín. {1}, máx. {2})" -f $t.Average, $t.Minimum, $t.Maximum
        } else { 'Latencia: sin respuesta al ping.' }

        $bytes = 25000000
        $cliente = New-Object System.Net.WebClient
        $reloj = [Diagnostics.Stopwatch]::StartNew()
        $datos = $cliente.DownloadData("https://speed.cloudflare.com/__down?bytes=$bytes")
        $reloj.Stop()
        $mbps = ($datos.Length * 8 / 1e6) / $reloj.Elapsed.TotalSeconds
        "Descarga: {0:0.0} Mbps ({1:0.0} MB en {2:0.0} s)" -f $mbps, ($datos.Length / 1e6), $reloj.Elapsed.TotalSeconds
    }

    'puertos' {
        $nombres = Get-Nombres
        $tcp = Get-NetTCPConnection -State Listen | Sort-Object LocalPort -Unique
        'TCP'
        foreach ($c in $tcp) {
            $donde = if ($c.LocalAddress -in '127.0.0.1', '::1') { 'solo este PC' } else { 'red' }
            "  {0,-6} {1,-22} ({2})" -f $c.LocalPort, $nombres[[int]$c.OwningProcess], $donde
        }
        ''
        'UDP'
        Get-NetUDPEndpoint | Sort-Object LocalPort -Unique | Select-Object -First 60 | ForEach-Object {
            "  {0,-6} {1}" -f $_.LocalPort, $nombres[[int]$_.OwningProcess]
        }
    }

    'conexiones' {
        $nombres = Get-Nombres
        $externas = Get-NetTCPConnection -State Established |
            Where-Object { $_.RemoteAddress -notmatch '^(127\.|::1|0\.0\.0\.0|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|fe80)' }
        if (-not $externas) { 'Ningún programa tiene conexiones a internet ahora mismo.'; exit 0 }
        $externas | Group-Object { $nombres[[int]$_.OwningProcess] } | Sort-Object Count -Descending | ForEach-Object {
            $destinos = ($_.Group | Select-Object -ExpandProperty RemoteAddress -Unique | Select-Object -First 3) -join ', '
            "{0}: {1} conexión(es) · {2}" -f $_.Name, $_.Count, $destinos
        }
    }

    default { "Modo desconocido: $($args[0])"; exit 1 }
}

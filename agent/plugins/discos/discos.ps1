# PC Remote · Discos.
#   discos.ps1 espacio | salud | carpetas | grandes
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Format-Tamano([double]$b) {
    if ($b -ge 1TB) { return '{0:0.00} TB' -f ($b / 1TB) }
    if ($b -ge 1GB) { return '{0:0.0} GB' -f ($b / 1GB) }
    if ($b -ge 1MB) { return '{0:0.0} MB' -f ($b / 1MB) }
    return '{0:0} KB' -f ($b / 1KB)
}

# Recorre sin seguir enlaces (OneDrive, uniones de AppData…) y saltándose lo que no deja leer.
function Get-Archivos([string]$raiz, [string[]]$saltar = @()) {
    $pendientes = New-Object System.Collections.Generic.Stack[string]
    $pendientes.Push($raiz)
    while ($pendientes.Count -gt 0) {
        $dir = $pendientes.Pop()
        try { $info = New-Object IO.DirectoryInfo $dir; $hijos = $info.GetFileSystemInfos() } catch { continue }
        foreach ($h in $hijos) {
            if ($h.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
            if ($h -is [IO.DirectoryInfo]) { if ($saltar -notcontains $h.FullName) { $pendientes.Push($h.FullName) } }
            else { $h }
        }
    }
}

switch ("$($args[0])") {
    'espacio' {
        foreach ($d in [IO.DriveInfo]::GetDrives() | Where-Object { $_.IsReady -and $_.DriveType -in 'Fixed', 'Removable' }) {
            $usado = 1 - $d.AvailableFreeSpace / $d.TotalSize
            $barra = ('█' * [int][Math]::Round($usado * 10)).PadRight(10, '░')
            $nombre = if ($d.VolumeLabel) { " ($($d.VolumeLabel))" } else { '' }
            "{0}{1}" -f $d.Name.TrimEnd('\'), $nombre
            "  {0} {1:0} % usado · {2} libres de {3}" -f $barra, ($usado * 100), (Format-Tamano $d.AvailableFreeSpace), (Format-Tamano $d.TotalSize)
        }
    }

    'salud' {
        $discos = @(Get-PhysicalDisk | Sort-Object DeviceId)
        if ($discos.Count -eq 0) { 'Windows no devuelve información de los discos físicos.'; exit 1 }
        $salud = @{ Healthy = 'Bien'; Warning = 'Aviso'; Unhealthy = 'Mal'; Unknown = 'Desconocida' }
        foreach ($d in $discos) {
            $s = $salud["$($d.HealthStatus)"]; if (-not $s) { $s = "$($d.HealthStatus)" }
            "$($d.FriendlyName)"
            "  {0} · {1} · {2} · Salud: {3}" -f $d.MediaType, $d.BusType, (Format-Tamano $d.Size), $s
            if ("$($d.OperationalStatus)" -ne 'OK') { "  Estado: $($d.OperationalStatus)" }
        }
    }

    'carpetas' {
        $perfil = $env:USERPROFILE
        $filas = foreach ($c in Get-ChildItem -LiteralPath $perfil -Directory -Force -ErrorAction SilentlyContinue) {
            if ($c.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
            $bytes = 0.0
            foreach ($f in Get-Archivos $c.FullName) { $bytes += $f.Length }
            [pscustomobject]@{ Nombre = $c.Name; Bytes = $bytes }
        }
        $filas | Where-Object { $_.Bytes -gt 0 } | Sort-Object Bytes -Descending | Select-Object -First 20 |
            ForEach-Object { "{0,9}  {1}" -f (Format-Tamano $_.Bytes), $_.Nombre }
    }

    'grandes' {
        $saltar = @(Join-Path $env:USERPROFILE 'AppData')
        Get-Archivos $env:USERPROFILE $saltar | Sort-Object Length -Descending | Select-Object -First 25 |
            ForEach-Object { "{0,9}  {1}" -f (Format-Tamano $_.Length), $_.FullName.Substring($env:USERPROFILE.Length + 1) }
    }

    default { "Modo desconocido: $($args[0])"; exit 1 }
}

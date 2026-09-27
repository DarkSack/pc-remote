# PC Remote · Buscar archivos.
#   archivos.ps1 buscar <parte del nombre> | descargas | recientes
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Format-Tamano([double]$b) {
    if ($b -ge 1GB) { return '{0:0.0} GB' -f ($b / 1GB) }
    if ($b -ge 1MB) { return '{0:0.0} MB' -f ($b / 1MB) }
    return '{0:0} KB' -f ($b / 1KB)
}

switch ("$($args[0])") {
    'buscar' {
        $aguja = "$($args[1])".Trim()
        if ($aguja.Length -lt 2) { 'Escribe al menos 2 caracteres.'; exit 1 }

        $max = 50
        $encontrados = New-Object System.Collections.Generic.List[IO.FileInfo]

        # 1) El índice de Windows Search: instantáneo y cubre las bibliotecas y lo que el usuario indexe.
        try {
            $like = $aguja.Replace("'", "''").Replace('[', '[[]').Replace('%', '[%]').Replace('_', '[_]')
            $conexion = New-Object -ComObject ADODB.Connection
            $filas = New-Object -ComObject ADODB.Recordset
            $conexion.Open("Provider=Search.CollatorDSO;Extended Properties='Application=Windows';")
            $filas.Open("SELECT TOP $max System.ItemPathDisplay FROM SYSTEMINDEX WHERE System.FileName LIKE '%$like%' AND System.ItemType <> 'Directory' ORDER BY System.DateModified DESC", $conexion)
            while (-not $filas.EOF) {
                $ruta = $filas.Fields.Item('System.ItemPathDisplay').Value
                if ($ruta -and (Test-Path -LiteralPath $ruta -PathType Leaf)) { $encontrados.Add((Get-Item -LiteralPath $ruta -Force)) }
                $filas.MoveNext()
            }
            $filas.Close(); $conexion.Close()
        } catch { }

        # 2) Si el índice no da nada (desactivado o la carpeta no está indexada), recorrer a mano
        #    tu usuario y las carpetas conocidas, que pueden estar en otra unidad. Nada de AppData
        #    ni carpetas de dependencias: ahí solo hay ruido.
        $saltar = 'AppData', 'node_modules', '.git', '.gradle', '.nuget', 'obj', 'bin', '$Recycle.Bin'
        $raices = @($env:USERPROFILE) + @('Desktop', 'MyDocuments', 'MyPictures', 'MyVideos', 'MyMusic' | ForEach-Object { [Environment]::GetFolderPath($_) })
        $raices += (New-Object -ComObject Shell.Application).Namespace('shell:Downloads').Self.Path
        $visto = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        $reloj = [Diagnostics.Stopwatch]::StartNew()

        foreach ($raiz in ($raices | Where-Object { $_ } | Select-Object -Unique)) {
            if ($encontrados.Count -gt 0) { break }
            $pendientes = New-Object System.Collections.Generic.Queue[string]
            $pendientes.Enqueue($raiz)
            while ($pendientes.Count -gt 0 -and $encontrados.Count -lt $max -and $reloj.Elapsed.TotalSeconds -lt 60) {
                $dir = $pendientes.Dequeue()
                if (-not $visto.Add($dir)) { continue }
                try { $hijos = (New-Object IO.DirectoryInfo $dir).GetFileSystemInfos() } catch { continue }
                foreach ($h in $hijos) {
                    if ($h.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
                    if ($h -is [IO.DirectoryInfo]) {
                        if ($saltar -notcontains $h.Name -and -not $h.Name.StartsWith('.')) { $pendientes.Enqueue($h.FullName) }
                    } elseif ($h.Name.IndexOf($aguja, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                        $encontrados.Add($h)
                        if ($encontrados.Count -ge $max) { break }
                    }
                }
            }
        }

        if ($encontrados.Count -eq 0) { "Nada con «$aguja» en el nombre."; exit 0 }
        $encontrados | Sort-Object LastWriteTime -Descending | ForEach-Object {
            "{0:dd/MM/yy}  {1,9}  {2}" -f $_.LastWriteTime, (Format-Tamano $_.Length), $_.FullName
        }
        if ($encontrados.Count -ge $max) { ''; "Se muestran los primeros $max. Afina la búsqueda para ver otros." }
        elseif ($reloj.Elapsed.TotalSeconds -ge 60) { ''; 'Búsqueda cortada al minuto: puede haber más.' }
    }

    'descargas' {
        $carpeta = (New-Object -ComObject Shell.Application).Namespace('shell:Downloads').Self.Path
        "En $carpeta"
        Get-ChildItem -LiteralPath $carpeta -File -Force -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 15 |
            ForEach-Object { "{0:dd/MM HH:mm}  {1,9}  {2}" -f $_.LastWriteTime, (Format-Tamano $_.Length), $_.Name }
    }

    'recientes' {
        $shell = New-Object -ComObject WScript.Shell
        $recientes = Join-Path ([Environment]::GetFolderPath('Recent')) '*.lnk'
        Get-ChildItem $recientes -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | ForEach-Object {
            $destino = $shell.CreateShortcut($_.FullName).TargetPath
            if ($destino -and (Test-Path -LiteralPath $destino -PathType Leaf)) { "{0:dd/MM HH:mm}  {1}" -f $_.LastWriteTime, $destino }
        } | Select-Object -First 20
    }

    default { "Modo desconocido: $($args[0])"; exit 1 }
}

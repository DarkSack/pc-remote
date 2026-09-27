# PC Remote · Limpieza.
#   limpieza.ps1 resumen | temporales | papelera | descargas
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Format-Tamano([double]$b) {
    if ($b -ge 1GB) { return '{0:0.0} GB' -f ($b / 1GB) }
    if ($b -ge 1MB) { return '{0:0.0} MB' -f ($b / 1MB) }
    return '{0:0} KB' -f ($b / 1KB)
}

# Get-ChildItem -Recurse corta todo el recorrido con un error terminante en cuanto encuentra un
# enlace roto o una carpeta que desaparece; esto se salta lo que no puede leer y no sigue enlaces.
function Get-Archivos([string]$raiz) {
    $pendientes = New-Object System.Collections.Generic.Stack[string]
    $pendientes.Push($raiz)
    while ($pendientes.Count -gt 0) {
        $dir = $pendientes.Pop()
        try { $hijos = (New-Object IO.DirectoryInfo $dir).GetFileSystemInfos() } catch { continue }
        foreach ($h in $hijos) {
            if ($h.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
            if ($h -is [IO.DirectoryInfo]) { $pendientes.Push($h.FullName) } else { $h }
        }
    }
}

function Get-Temporales {
    $limite = (Get-Date).AddDays(-1)
    Get-Archivos $env:TEMP | Where-Object { $_.LastWriteTime -lt $limite }
}

function Get-Papelera {
    $shell = New-Object -ComObject Shell.Application
    $items = @($shell.Namespace(10).Items())
    $total = 0.0
    foreach ($i in $items) { $total += [double]$i.ExtendedProperty('Size') }
    return @{ Cantidad = $items.Count; Bytes = $total }
}

function Get-Descargas {
    $carpeta = (New-Object -ComObject Shell.Application).Namespace('shell:Downloads').Self.Path
    $limite = (Get-Date).AddDays(-30)
    Get-ChildItem -LiteralPath $carpeta -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $limite } |
        ForEach-Object {
            $bytes = if ($_.PSIsContainer) { (Get-Archivos $_.FullName | Measure-Object Length -Sum).Sum } else { $_.Length }
            [pscustomobject]@{ Nombre = $_.Name; Bytes = [double]$bytes; Fecha = $_.LastWriteTime }
        }
}

switch ("$($args[0])") {
    'resumen' {
        $temp = (Get-Temporales | Measure-Object Length -Sum).Sum
        $pap = Get-Papelera
        $desc = (Get-Descargas | Measure-Object Bytes -Sum).Sum
        "Temporales (más de 1 día): $(Format-Tamano $temp)"
        "Papelera: $(Format-Tamano $pap.Bytes) en $($pap.Cantidad) elemento(s)"
        "Descargas de hace más de 30 días: $(Format-Tamano $desc)"
    }

    'temporales' {
        $borrados = 0; $bytes = 0.0; $saltados = 0
        foreach ($f in Get-Temporales) {
            try { $len = $f.Length; Remove-Item -LiteralPath $f.FullName -Force -ErrorAction Stop; $borrados++; $bytes += $len }
            catch { $saltados++ }
        }
        # Carpetas que quedaron vacías (de abajo arriba).
        $carpetas = New-Object System.Collections.Generic.List[string]
        $pendientes = New-Object System.Collections.Generic.Stack[string]
        $pendientes.Push($env:TEMP)
        while ($pendientes.Count -gt 0) {
            try { $subs = (New-Object IO.DirectoryInfo $pendientes.Pop()).GetDirectories() } catch { continue }
            foreach ($s in $subs) {
                if ($s.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }   # un enlace no se toca
                $carpetas.Add($s.FullName); $pendientes.Push($s.FullName)
            }
        }
        $carpetas | Sort-Object Length -Descending |
            ForEach-Object { try { [IO.Directory]::Delete($_) } catch { } }   # solo borra las que están vacías
        "Liberado: $(Format-Tamano $bytes) ($borrados archivo(s))."
        if ($saltados) { "$saltados archivo(s) en uso se dejaron." }
    }

    'papelera' {
        $pap = Get-Papelera
        if ($pap.Cantidad -eq 0) { 'La papelera ya estaba vacía.'; exit 0 }
        Clear-RecycleBin -Force -ErrorAction SilentlyContinue
        "Papelera vaciada: $(Format-Tamano $pap.Bytes) liberados."
    }

    'descargas' {
        $lista = @(Get-Descargas | Sort-Object Bytes -Descending)
        if ($lista.Count -eq 0) { 'No hay nada en Descargas con más de 30 días.'; exit 0 }
        "Total: $(Format-Tamano (($lista | Measure-Object Bytes -Sum).Sum)) en $($lista.Count) elemento(s)"
        ''
        $lista | Select-Object -First 40 | ForEach-Object { "{0,9}  {1:dd/MM/yy}  {2}" -f (Format-Tamano $_.Bytes), $_.Fecha, $_.Nombre }
        if ($lista.Count -gt 40) { "… y $($lista.Count - 40) más." }
    }

    default { "Modo desconocido: $($args[0])"; exit 1 }
}

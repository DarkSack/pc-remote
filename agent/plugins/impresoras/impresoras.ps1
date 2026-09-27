# PC Remote · Impresoras.
#   impresoras.ps1 cola | cancelar | prueba
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

# Las virtuales (PDF, XPS, OneNote, Fax) no tienen cola real que mirar.
$virtuales = 'Microsoft Print to PDF|Microsoft XPS|OneNote|^Fax$|Enviar a OneNote|Send To OneNote'
$predeterminada = (Get-CimInstance Win32_Printer -Filter 'Default = TRUE' -ErrorAction SilentlyContinue).Name

switch ("$($args[0])") {
    'cola' {
        $impresoras = @(Get-Printer | Where-Object { $_.Name -notmatch $virtuales })
        if ($impresoras.Count -eq 0) { 'No hay impresoras instaladas (aparte de las virtuales).'; exit 0 }
        foreach ($p in $impresoras) {
            $marca = if ($p.Name -eq $predeterminada) { ' (predeterminada)' } else { '' }
            "$($p.Name)$marca · $($p.PrinterStatus)"
            $trabajos = @(Get-PrintJob -PrinterName $p.Name -ErrorAction SilentlyContinue)
            if ($trabajos.Count -eq 0) { '  Sin trabajos en cola.' }
            foreach ($t in $trabajos) { "  • $($t.DocumentName) · $($t.JobStatus) · $($t.UserName)" }
        }
    }

    'cancelar' {
        $n = 0
        foreach ($p in Get-Printer) {
            foreach ($t in @(Get-PrintJob -PrinterName $p.Name -ErrorAction SilentlyContinue)) {
                try { Remove-PrintJob -InputObject $t; $n++ } catch { "No se pudo cancelar «$($t.DocumentName)»: $($_.Exception.Message)" }
            }
        }
        if ($n -eq 0) { 'No había trabajos que cancelar.' } else { "$n trabajo(s) cancelado(s)." }
    }

    'prueba' {
        if (-not $predeterminada) { 'No hay impresora predeterminada.'; exit 1 }
        $r = Get-CimInstance Win32_Printer -Filter 'Default = TRUE' | Invoke-CimMethod -MethodName PrintTestPage
        if ($r.ReturnValue -eq 0) { "Página de prueba enviada a $predeterminada." } else { "La impresora respondió con el código $($r.ReturnValue)."; exit 1 }
    }

    default { "Modo desconocido: $($args[0])"; exit 1 }
}

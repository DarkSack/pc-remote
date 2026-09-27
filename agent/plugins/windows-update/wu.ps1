# PC Remote · Windows Update.
#   wu.ps1 pendientes | historial | reinicio
# La API COM de Windows Update deja buscar e ir al historial sin permisos de administrador.
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

switch ("$($args[0])") {
    'pendientes' {
        $sesion = New-Object -ComObject Microsoft.Update.Session
        $buscador = $sesion.CreateUpdateSearcher()
        $r = $buscador.Search("IsInstalled=0 and IsHidden=0")
        if ($r.Updates.Count -eq 0) { 'Windows está al día.'; exit 0 }
        "$($r.Updates.Count) actualización(es) pendiente(s):"
        foreach ($u in $r.Updates) {
            $mb = [Math]::Round($u.MaxDownloadSize / 1MB)
            $bajada = if ($u.IsDownloaded) { 'descargada' } else { "$mb MB" }
            "  • $($u.Title) ($bajada)"
        }
    }

    'historial' {
        $buscador = (New-Object -ComObject Microsoft.Update.Session).CreateUpdateSearcher()
        $total = $buscador.GetTotalHistoryCount()
        if ($total -eq 0) { 'No hay historial de actualizaciones.'; exit 0 }
        $resultados = @{ 1 = 'en curso'; 2 = 'bien'; 3 = 'con avisos'; 4 = 'FALLÓ'; 5 = 'cancelada' }
        # La Store y Defender repiten la misma entrada varias veces: una por título.
        $buscador.QueryHistory(0, [Math]::Min($total, 100)) |
            Where-Object { $_.Title -and $_.Operation -eq 1 } |
            Group-Object Title | ForEach-Object { $_.Group[0] } |
            Select-Object -First 15 |
            ForEach-Object { "{0:dd/MM/yy}  {1}  [{2}]" -f $_.Date.ToLocalTime(), $_.Title, $resultados[[int]$_.ResultCode] }
    }

    'reinicio' {
        $pendiente = (New-Object -ComObject Microsoft.Update.SystemInfo).RebootRequired
        if ($pendiente) { 'Sí: Windows Update está esperando un reinicio para terminar de instalar.' }
        else { 'No hace falta reiniciar por actualizaciones.' }
    }

    default { "Modo desconocido: $($args[0])"; exit 1 }
}

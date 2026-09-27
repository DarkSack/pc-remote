# PC Remote · Navegador: abre un enlace o una búsqueda en el navegador predeterminado.
#   abrir.ps1 url <https://…> | google|youtube|ytmusic|maps <texto>
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$texto = "$($args[1])".Trim()
if ($texto -eq '') { 'Falta el texto.'; exit 1 }
$q = [Uri]::EscapeDataString($texto)

switch ("$($args[0])") {
    'url' {
        # Solo http(s): nada de file:, ms-settings: ni programas.
        $uri = $null
        if (-not [Uri]::TryCreate($texto, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin 'http', 'https') {
            'Solo se abren enlaces http:// o https://.'; exit 1
        }
        $destino = $uri.AbsoluteUri
    }
    'google'  { $destino = "https://www.google.com/search?q=$q" }
    'youtube' { $destino = "https://www.youtube.com/results?search_query=$q" }
    'ytmusic' { $destino = "https://music.youtube.com/search?q=$q" }
    'maps'    { $destino = "https://www.google.com/maps/search/?api=1&query=$q" }
    default   { "Modo desconocido: $($args[0])"; exit 1 }
}

Start-Process $destino
"Abierto en el PC: $destino"

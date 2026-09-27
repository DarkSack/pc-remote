# PC Remote · Notas rápidas.
#   notas.ps1 apuntar <texto> | ver | abrir
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$carpeta = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'PC Remote'
$archivo = Join-Path $carpeta 'notas.txt'
$utf8 = New-Object Text.UTF8Encoding $true   # con BOM: el Bloc de notas antiguo lo abre bien

switch ("$($args[0])") {
    'apuntar' {
        $texto = "$($args[1])".Trim()
        if ($texto -eq '') { 'La nota está vacía.'; exit 1 }
        [void](New-Item -ItemType Directory -Force -Path $carpeta)
        [IO.File]::AppendAllText($archivo, ('{0:yyyy-MM-dd HH:mm}  {1}' -f (Get-Date), $texto) + [Environment]::NewLine, $utf8)
        'Apuntada.'
    }
    'ver' {
        if (-not (Test-Path $archivo)) { 'Todavía no hay notas.'; exit 0 }
        [IO.File]::ReadAllLines($archivo, $utf8) | Where-Object { $_.Trim() } | Select-Object -Last 20
    }
    'abrir' {
        if (-not (Test-Path $archivo)) {
            [void](New-Item -ItemType Directory -Force -Path $carpeta)
            [IO.File]::WriteAllText($archivo, '', $utf8)
        }
        Start-Process notepad.exe -ArgumentList "`"$archivo`""
        "Abiertas en el PC: $archivo"
    }
    default { "Modo desconocido: $($args[0])"; exit 1 }
}

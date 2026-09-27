# PC Remote · Captura de pantalla.
#   captura.ps1 "Todas las pantallas" | "Pantalla principal" | lista | abrir
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$carpeta = Join-Path ([Environment]::GetFolderPath('MyPictures')) 'PC Remote'
[void](New-Item -ItemType Directory -Force -Path $carpeta)

switch ("$($args[0])") {
    'lista' {
        $fotos = @(Get-ChildItem -LiteralPath $carpeta -Filter *.png | Sort-Object LastWriteTime -Descending | Select-Object -First 10)
        if ($fotos.Count -eq 0) { 'Todavía no hay capturas.'; exit 0 }
        "En $carpeta"
        $fotos | ForEach-Object { "  {0:dd/MM HH:mm}  {1}  ({2:0.0} MB)" -f $_.LastWriteTime, $_.Name, ($_.Length / 1MB) }
        exit 0
    }
    'abrir' { Start-Process explorer.exe -ArgumentList "`"$carpeta`""; "Abierta en el PC: $carpeta"; exit 0 }
}

# Sin esto, con escalado al 125 % o más, la captura sale recortada.
Add-Type -Namespace PcRemote -Name Dpi -MemberDefinition '[DllImport("user32.dll")] public static extern bool SetProcessDPIAware();'
[void][PcRemote.Dpi]::SetProcessDPIAware()
Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$zona = if ("$($args[0])" -eq 'Pantalla principal') {
    [Windows.Forms.Screen]::PrimaryScreen.Bounds
} else {
    [Windows.Forms.SystemInformation]::VirtualScreen
}

$imagen = New-Object Drawing.Bitmap $zona.Width, $zona.Height
$lienzo = [Drawing.Graphics]::FromImage($imagen)
try {
    $lienzo.CopyFromScreen($zona.Left, $zona.Top, 0, 0, $imagen.Size)
    $archivo = Join-Path $carpeta ('captura-{0:yyyyMMdd-HHmmss}.png' -f (Get-Date))
    $imagen.Save($archivo, [Drawing.Imaging.ImageFormat]::Png)
} finally {
    $lienzo.Dispose(); $imagen.Dispose()
}

"Captura guardada ($($zona.Width)×$($zona.Height)):"
$archivo

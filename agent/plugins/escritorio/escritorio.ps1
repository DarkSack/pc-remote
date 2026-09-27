# PC Remote · Escritorio y apariencia.
#   escritorio.ps1 escritorio | tema Alternar|Oscuro|Claro | explorer
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

switch ("$($args[0])") {
    'escritorio' {
        (New-Object -ComObject Shell.Application).ToggleDesktop()
        'Hecho.'
    }

    'tema' {
        $clave = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize'
        $claroAhora = (Get-ItemProperty -Path $clave -Name AppsUseLightTheme -ErrorAction SilentlyContinue).AppsUseLightTheme -ne 0
        $claro = switch ("$($args[1])") {
            'Oscuro' { $false }
            'Claro'  { $true }
            default  { -not $claroAhora }
        }
        $valor = [int]$claro
        Set-ItemProperty -Path $clave -Name AppsUseLightTheme -Value $valor -Type DWord
        Set-ItemProperty -Path $clave -Name SystemUsesLightTheme -Value $valor -Type DWord

        # Avisar a las ventanas abiertas para que cambien ya, sin cerrar sesión.
        Add-Type -Namespace PcRemote -Name Settings -MemberDefinition @'
[DllImport("user32.dll", CharSet = CharSet.Unicode)]
public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint msg, UIntPtr wParam, string lParam, uint flags, uint timeout, out UIntPtr result);
'@
        $r = [UIntPtr]::Zero
        [void][PcRemote.Settings]::SendMessageTimeout([IntPtr]0xffff, 0x001A, [UIntPtr]::Zero, 'ImmersiveColorSet', 0x0002, 3000, [ref]$r)
        if ($claro) { 'Tema claro activado.' } else { 'Tema oscuro activado.' }
    }

    'explorer' {
        Get-Process explorer -ErrorAction SilentlyContinue | Stop-Process -Force
        # Windows lo vuelve a abrir solo; si no lo ha hecho en unos segundos, lo abrimos nosotros.
        for ($i = 0; $i -lt 10 -and -not (Get-Process explorer -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Milliseconds 500 }
        if (-not (Get-Process explorer -ErrorAction SilentlyContinue)) { Start-Process explorer.exe }
        'Explorador reiniciado.'
    }

    default { "Modo desconocido: $($args[0])"; exit 1 }
}

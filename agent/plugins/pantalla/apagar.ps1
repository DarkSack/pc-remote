# PC Remote · Pantalla: apaga los monitores (SC_MONITORPOWER).
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

Add-Type -Namespace PcRemote -Name Monitor -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
'@

# PostMessage y no SendMessage: a HWND_BROADCAST, SendMessage espera a todas las ventanas
# y una colgada lo bloquearía.
[void][PcRemote.Monitor]::PostMessage([IntPtr]0xffff, 0x0112, [IntPtr]0xF170, [IntPtr]2)
'Pantalla apagada. Se enciende al mover el ratón o pulsar una tecla (también desde el móvil).'

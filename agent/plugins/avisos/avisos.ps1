# PC Remote · Avisos en el PC.
#   avisos.ps1 notificacion|mensaje|voz <texto>
#   avisos.ps1 sonar <segundos>
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$texto = "$($args[1])".Trim()

switch ("$($args[0])") {
    'notificacion' {
        if ($texto -eq '') { 'Falta el texto.'; exit 1 }
        [void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime]
        [void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime]
        $xml = New-Object Windows.Data.Xml.Dom.XmlDocument
        $xml.LoadXml("<toast><visual><binding template='ToastGeneric'><text>PC Remote</text><text>$([Security.SecurityElement]::Escape($texto))</text></binding></visual></toast>")
        # Se muestra con la identidad de PowerShell: una app sin registrar no puede enviar notificaciones.
        $app = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe'
        [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($app).Show([Windows.UI.Notifications.ToastNotification]::new($xml))
        'Notificación enviada.'
    }

    'mensaje' {
        if ($texto -eq '') { 'Falta el mensaje.'; exit 1 }
        # En otro proceso: la ventana se queda abierta sin tener esperando al móvil.
        # El texto va como literal entre comillas simples (con ' duplicadas): nunca se interpreta.
        $literal = "'" + $texto.Replace("'", "''") + "'"
        $codigo = @"
Add-Type -AssemblyName System.Windows.Forms
[void][System.Windows.Forms.MessageBox]::Show($literal, 'Mensaje desde el móvil', 'OK', 'Information', 'Button1', 'ServiceNotification')
"@
        $cifrado = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($codigo))
        Start-Process powershell.exe -WindowStyle Hidden -ArgumentList '-NoProfile', '-NonInteractive', '-EncodedCommand', $cifrado
        'Mensaje mostrado en el PC.'
    }

    'voz' {
        if ($texto -eq '') { 'Falta el texto.'; exit 1 }
        Add-Type -AssemblyName System.Speech
        $voz = New-Object System.Speech.Synthesis.SpeechSynthesizer
        try {
            $es = $voz.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'es' } | Select-Object -First 1
            if ($es) { $voz.SelectVoice($es.VoiceInfo.Name) }
            $voz.Volume = 100
            $voz.Speak($texto)
            "Dicho con la voz «$($voz.Voice.Name)»."
        } finally { $voz.Dispose() }
    }

    'sonar' {
        $segundos = [int][Math]::Round([double]::Parse("$($args[1])", [Globalization.CultureInfo]::InvariantCulture))
        $wav = Join-Path $env:WINDIR 'Media\Alarm01.wav'
        if (-not (Test-Path $wav)) { $wav = Join-Path $env:WINDIR 'Media\Windows Ding.wav' }
        $sonido = New-Object System.Media.SoundPlayer $wav
        $sonido.PlayLooping()
        Start-Sleep -Seconds $segundos
        $sonido.Stop()
        "Sonó durante $segundos s. (Si no se oyó, revisa que el PC no esté silenciado.)"
    }

    default { "Modo desconocido: $($args[0])"; exit 1 }
}

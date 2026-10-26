# SackDesk · Escritorios virtuales (tienda de plugins).
#   escritorios.ps1 siguiente | anterior | nuevo | vista | cerrar | probar
# Pulsa los mismos atajos que harías con el teclado (Win+Ctrl+flechas, Win+Ctrl+D…).
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

Add-Type -Namespace SackDesk -Name Teclas -MemberDefinition @'
[DllImport("user32.dll")]
public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
'@

$WIN = 0x5B; $CTRL = 0x11; $TAB = 0x09; $D = 0x44; $F4 = 0x73; $IZQUIERDA = 0x25; $DERECHA = 0x27
# Win y las flechas son teclas extendidas: sin la marca, Windows las toma por el teclado numérico.
$extendidas = @($WIN, $IZQUIERDA, $DERECHA)

function Pulsar([int[]]$teclas) {
    foreach ($t in $teclas) { [SackDesk.Teclas]::keybd_event($t, 0, [uint32]($t -in $extendidas), [UIntPtr]::Zero) }
    [array]::Reverse($teclas)
    foreach ($t in $teclas) { [SackDesk.Teclas]::keybd_event($t, 0, [uint32](2 -bor [int]($t -in $extendidas)), [UIntPtr]::Zero) }
}

switch ("$($args[0])") {
    'siguiente' { Pulsar @($WIN, $CTRL, $DERECHA); 'Escritorio siguiente.' }
    'anterior'  { Pulsar @($WIN, $CTRL, $IZQUIERDA); 'Escritorio anterior.' }
    'nuevo'     { Pulsar @($WIN, $CTRL, $D); 'Escritorio nuevo creado.' }
    'vista'     { Pulsar @($WIN, $TAB); 'Vista de tareas abierta.' }
    'cerrar'    { Pulsar @($WIN, $CTRL, $F4); 'Escritorio cerrado.' }
    'probar'    { 'Listo.' }  # carga el script sin pulsar nada (para comprobarlo)
    default     { "Acción desconocida: $($args[0])"; exit 1 }
}

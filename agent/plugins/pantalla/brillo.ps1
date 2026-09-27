# PC Remote · Pantalla: lee o cambia el brillo.
#   brillo.ps1        → muestra el brillo de cada pantalla
#   brillo.ps1 <0-100> → lo cambia
# Pantalla de portátil: WMI. Monitores externos: DDC/CI (el monitor tiene que tenerlo activado).
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$nivel = -1
if ($args.Count -gt 0 -and "$($args[0])" -ne '') {
    $nivel = [int][Math]::Round([double]::Parse("$($args[0])", [Globalization.CultureInfo]::InvariantCulture))
    $nivel = [Math]::Max(0, [Math]::Min(100, $nivel))
}

$lineas = New-Object System.Collections.Generic.List[string]

# Pantalla integrada (portátiles, tablets).
try {
    $metodos = Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightnessMethods -ErrorAction Stop
    if ($metodos) {
        if ($nivel -ge 0) {
            $metodos | ForEach-Object { [void](Invoke-CimMethod -InputObject $_ -MethodName WmiSetBrightness -Arguments @{ Timeout = [uint32]1; Brightness = [byte]$nivel }) }
            Start-Sleep -Milliseconds 300
        }
        Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightness | ForEach-Object {
            $lineas.Add("Pantalla integrada: $($_.CurrentBrightness) %")
        }
    }
} catch { }

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class PcRemoteDdc
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct PHYSICAL_MONITOR
    {
        public IntPtr Handle;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Description;
    }

    private delegate bool MonitorEnumProc(IntPtr monitor, IntPtr hdc, IntPtr rect, IntPtr data);

    [DllImport("user32.dll")] private static extern bool EnumDisplayMonitors(IntPtr hdc, IntPtr clip, MonitorEnumProc callback, IntPtr data);
    [DllImport("dxva2.dll")] private static extern bool GetNumberOfPhysicalMonitorsFromHMONITOR(IntPtr monitor, out uint count);
    [DllImport("dxva2.dll")] private static extern bool GetPhysicalMonitorsFromHMONITOR(IntPtr monitor, uint count, [Out] PHYSICAL_MONITOR[] monitors);
    [DllImport("dxva2.dll")] private static extern bool DestroyPhysicalMonitors(uint count, PHYSICAL_MONITOR[] monitors);
    [DllImport("dxva2.dll")] private static extern bool GetVCPFeatureAndVCPFeatureReply(IntPtr monitor, byte code, IntPtr type, out uint current, out uint maximum);
    [DllImport("dxva2.dll")] private static extern bool SetVCPFeature(IntPtr monitor, byte code, uint value);

    private const byte Luminance = 0x10;

    /// <summary>level &lt; 0: solo leer.</summary>
    public static List<string> Run(int level)
    {
        var handles = new List<IntPtr>();
        MonitorEnumProc callback = (monitor, hdc, rect, data) => { handles.Add(monitor); return true; };
        EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, callback, IntPtr.Zero);
        GC.KeepAlive(callback);

        var result = new List<string>();
        foreach (var handle in handles)
        {
            uint count;
            if (!GetNumberOfPhysicalMonitorsFromHMONITOR(handle, out count) || count == 0) continue;
            var monitors = new PHYSICAL_MONITOR[count];
            if (!GetPhysicalMonitorsFromHMONITOR(handle, count, monitors)) continue;
            try
            {
                foreach (var m in monitors)
                {
                    uint current, maximum;
                    if (!GetVCPFeatureAndVCPFeatureReply(m.Handle, Luminance, IntPtr.Zero, out current, out maximum) || maximum == 0)
                        continue; // la integrada o un monitor sin DDC/CI
                    if (level >= 0)
                    {
                        current = (uint)Math.Round(level * maximum / 100.0);
                        SetVCPFeature(m.Handle, Luminance, current);
                    }
                    var name = string.IsNullOrWhiteSpace(m.Description) ? "Monitor" : m.Description.Trim();
                    result.Add(name + ": " + (int)Math.Round(current * 100.0 / maximum) + " %");
                }
            }
            finally { DestroyPhysicalMonitors(count, monitors); }
        }
        return result;
    }
}
'@

foreach ($l in [PcRemoteDdc]::Run($nivel)) { $lineas.Add($l) }

if ($lineas.Count -eq 0) {
    'No hay ninguna pantalla con brillo ajustable. En los monitores externos hay que activar DDC/CI en su menú.'
    exit 1
}
if ($nivel -ge 0) { "Brillo al $nivel %:" }
$lineas

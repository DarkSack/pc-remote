# PC Remote · Salida de audio.
#   audio.ps1 listar
#   audio.ps1 siguiente
#   audio.ps1 elegir <n>
# Core Audio (MMDevice) para listar y IPolicyConfig —la interfaz que usa el propio
# panel de sonido de Windows— para cambiar la salida predeterminada.
trap { Write-Output "Error: $($_.Exception.Message)"; exit 1 }
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace PcRemoteAudio
{
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceCollection
    {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int Item(int index, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int context, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object result);
        [PreserveSig] int OpenPropertyStore(int access, out IPropertyStore store);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out int state);
    }

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore
    {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int GetAt(int index, out PropertyKey key);
        [PreserveSig] int GetValue(ref PropertyKey key, out PropVariant value);
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PropertyKey { public Guid FormatId; public int PropertyId; }

    // 24 bytes en x64: vt + 3 reservados + unión de 16.
    [StructLayout(LayoutKind.Explicit)]
    struct PropVariant
    {
        [FieldOffset(0)] public short Type;
        [FieldOffset(8)] public IntPtr Pointer;
        [FieldOffset(16)] public long Padding;
    }

    // Solo importa el orden de la vtable: SetDefaultEndpoint es la undécima.
    [ComImport, Guid("F8679F50-850A-41CF-9C72-430F290290C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPolicyConfig
    {
        [PreserveSig] int GetMixFormat();
        [PreserveSig] int GetDeviceFormat();
        [PreserveSig] int ResetDeviceFormat();
        [PreserveSig] int SetDeviceFormat();
        [PreserveSig] int GetProcessingPeriod();
        [PreserveSig] int SetProcessingPeriod();
        [PreserveSig] int GetShareMode();
        [PreserveSig] int SetShareMode();
        [PreserveSig] int GetPropertyValue();
        [PreserveSig] int SetPropertyValue();
        [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string deviceId, int role);
        [PreserveSig] int SetEndpointVisibility();
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumerator { }
    [ComImport, Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9")] class PolicyConfigClient { }

    public class Device
    {
        public string Id;
        public string Name;
        public bool IsDefault;
    }

    public static class Outputs
    {
        const int Render = 0, Active = 1, Multimedia = 1;

        [DllImport("ole32.dll")] static extern int PropVariantClear(ref PropVariant value);

        public static List<Device> List()
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            string defaultId = null;
            IMMDevice def;
            if (enumerator.GetDefaultAudioEndpoint(Render, Multimedia, out def) == 0) def.GetId(out defaultId);

            IMMDeviceCollection collection;
            Marshal.ThrowExceptionForHR(enumerator.EnumAudioEndpoints(Render, Active, out collection));
            int count;
            collection.GetCount(out count);

            var nameKey = new PropertyKey { FormatId = new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"), PropertyId = 14 };
            var result = new List<Device>();
            for (int i = 0; i < count; i++)
            {
                IMMDevice device;
                if (collection.Item(i, out device) != 0) continue;
                string id;
                device.GetId(out id);
                string name = id;
                IPropertyStore store;
                if (device.OpenPropertyStore(0, out store) == 0)
                {
                    PropVariant value;
                    if (store.GetValue(ref nameKey, out value) == 0)
                    {
                        if (value.Type == 31 && value.Pointer != IntPtr.Zero) name = Marshal.PtrToStringUni(value.Pointer);
                        PropVariantClear(ref value);
                    }
                }
                result.Add(new Device { Id = id, Name = name, IsDefault = id == defaultId });
            }
            result.Sort((a, b) => string.Compare(a.Name, b.Name, StringComparison.CurrentCultureIgnoreCase));
            return result;
        }

        public static void SetDefault(string id)
        {
            var policy = (IPolicyConfig)new PolicyConfigClient();
            for (int role = 0; role <= 2; role++) Marshal.ThrowExceptionForHR(policy.SetDefaultEndpoint(id, role));
        }
    }
}
'@

$modo = "$($args[0])"
$salidas = [PcRemoteAudio.Outputs]::List()
if ($salidas.Count -eq 0) { 'No hay ninguna salida de audio activa.'; exit 1 }

function Show-Lista {
    for ($i = 0; $i -lt $salidas.Count; $i++) {
        $marca = if ($salidas[$i].IsDefault) { '  ← actual' } else { '' }
        "{0}. {1}{2}" -f ($i + 1), $salidas[$i].Name, $marca
    }
}

switch ($modo) {
    'listar' { Show-Lista; exit 0 }
    'siguiente' {
        $actual = -1
        for ($i = 0; $i -lt $salidas.Count; $i++) { if ($salidas[$i].IsDefault) { $actual = $i } }
        $elegida = $salidas[($actual + 1) % $salidas.Count]
    }
    'elegir' {
        $n = [int][Math]::Round([double]::Parse("$($args[1])", [Globalization.CultureInfo]::InvariantCulture))
        if ($n -lt 1 -or $n -gt $salidas.Count) { "No hay salida número $n. Hay $($salidas.Count):"; Show-Lista; exit 1 }
        $elegida = $salidas[$n - 1]
    }
    default { "Modo desconocido: $modo"; exit 1 }
}

[PcRemoteAudio.Outputs]::SetDefault($elegida.Id)
"Salida de audio: $($elegida.Name)"

using System.Runtime.InteropServices;
using SharpGen.Runtime;

namespace PcRemote.Modules.Screen.Encoding;

/// <summary>Media Foundation GUIDs (mfapi.h, codecapi.h). Vortice does not expose all of them.</summary>
internal static class MfGuids
{
    public static readonly Guid CategoryVideoEncoder = new("f79eac7d-e545-4387-bdee-d647d7bde42a");
    public static readonly Guid MediaTypeVideo       = new("73646976-0000-0010-8000-00AA00389B71");
    public static readonly Guid H264                 = new("34363248-0000-0010-8000-00AA00389B71");
    public static readonly Guid NV12                 = new("3231564E-0000-0010-8000-00AA00389B71");

    public static readonly Guid MajorType        = new("48eba18e-f8c9-4687-bf11-0a74c9f96a8f");
    public static readonly Guid Subtype          = new("f7e34c9a-42e8-4714-b74b-cb29d72c35e5");
    public static readonly Guid FrameSize        = new("1652c33d-d6b2-4012-b834-72030849a37d");
    public static readonly Guid FrameRate        = new("c459a2e8-3d2c-4e44-b132-fee5156c7bb0");
    public static readonly Guid PixelAspect      = new("c6376a1e-8d0a-4027-be45-6d9a0ad39bb6");
    public static readonly Guid InterlaceMode    = new("e2724bb8-e676-4806-b4b2-a8d6efb44ccd");
    public static readonly Guid AvgBitrate       = new("20332624-fb0d-4d9e-bd0d-cbf6786c102e");
    public static readonly Guid Mpeg2Profile     = new("ad76a80b-2d5c-4e0b-b375-64e520137036");
    public static readonly Guid YuvMatrix        = new("3e23d450-2c75-4d25-a00e-b91670d12327");
    public static readonly Guid NominalRange     = new("c21b8ee5-b956-4071-8daf-325edf5cab11");
    public static readonly Guid Primaries        = new("dbfbe4d7-0740-4ee0-8192-850ab0e21935");
    public static readonly Guid TransferFunction = new("5fb0fce9-be5c-4935-a811-ec838f8eed93");

    public static readonly Guid AsyncUnlock  = new("e5666d6b-3422-4eb6-a421-da7db1f8e207");
    public static readonly Guid FriendlyName = new("314ffbae-5b41-4c95-9c19-4e7d586face3");

    // ICodecAPI properties
    public static readonly Guid LowLatency      = new("9c27891a-ed7a-40e1-88e8-b22727a024ee");
    public static readonly Guid RateControlMode = new("1c0608e9-370c-4710-8a58-cb6181c42423");
    public static readonly Guid MeanBitRate     = new("f7222374-2144-4815-b550-a37f8e12ee52");
    public static readonly Guid MaxBitRate      = new("9651eae4-39b9-4ebf-85ef-d7f444ec7465");
    public static readonly Guid GopSize         = new("95f31b26-95a4-41aa-9303-246a7fc6eef1");
    public static readonly Guid BPictureCount   = new("8d390aac-dc5c-4200-b57f-814d04babab2");
    public static readonly Guid ForceKeyFrame   = new("398c1b98-8353-475a-9ef2-8f265d260345");
    public static readonly Guid QualityVsSpeed  = new("98332df8-03cd-476b-89fa-3f9e442dec9f");

    public static readonly Guid IID_ICodecAPI = new("901db4c7-31ce-41a2-85dc-8fa0bf41b8da");

    public const uint RateControlPeakConstrainedVbr = 1;
    public const uint ProfileMain = 77;

    public const int MF_E_TRANSFORM_NEED_MORE_INPUT = unchecked((int)0xC00D6D72);
    public const int MF_E_NO_EVENTS_AVAILABLE       = unchecked((int)0xC00D3E80);
    public const int MF_E_TRANSFORM_STREAM_CHANGE   = unchecked((int)0xC00D6D61);
}

/// <summary>
/// ICodecAPI::SetValue through the vtable: Vortice does not wrap ICodecAPI. Every
/// property is optional for an encoder, so a failure is reported, not thrown.
/// </summary>
internal static unsafe class CodecApi
{
    private const int SetValueSlot = 9; // IUnknown(3) + IsSupported, IsModifiable, GetParameterRange, GetParameterValues, GetDefaultValue, GetValue

    public static bool SetUInt32(ComObject transform, Guid key, uint value)
    {
        var iid = MfGuids.IID_ICodecAPI;
        if (Marshal.QueryInterface(transform.NativePointer, in iid, out var api) < 0) return false;
        try
        {
            var variant = stackalloc byte[24];
            new Span<byte>(variant, 24).Clear();
            *(ushort*)variant = 19; // VT_UI4
            *(uint*)(variant + 8) = value;
            var vtbl = *(IntPtr**)api;
            var setValue = (delegate* unmanaged[Stdcall]<IntPtr, Guid*, byte*, int>)vtbl[SetValueSlot];
            return setValue(api, &key, variant) >= 0;
        }
        finally { Marshal.Release(api); }
    }
}

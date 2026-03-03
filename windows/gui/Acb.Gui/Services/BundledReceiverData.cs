namespace Acb.Gui.Services;

internal static partial class BundledReceiverData
{
    private static partial byte[] GetEmbeddedBytes();

    public static byte[]? TryGetBytes()
    {
#if ACB_EMBED_RECEIVER
        return GetEmbeddedBytes();
#else
        return null;
#endif
    }
}

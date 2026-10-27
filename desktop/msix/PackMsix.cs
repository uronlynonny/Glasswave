// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
//
// Packs a folder into an MSIX package for the Microsoft Store, using the packaging engine built
// into Windows (the same one MakeAppx uses). It also checks the AppxManifest.xml as it goes.
//   PackMsix.exe <folder with AppxManifest.xml> <output .msix>
// Built by the desktop build (packageMsix) with the C# compiler that comes with Windows.

using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;

static class PackMsix
{
    [StructLayout(LayoutKind.Sequential)]
    struct APPX_PACKAGE_SETTINGS
    {
        public int forceZip32;
        public IntPtr hashMethod; // IUri*
    }

    [ComImport, Guid("beb94909-e451-438b-b5a7-d79e767b75d8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAppxFactory
    {
        IAppxPackageWriter CreatePackageWriter(IStream outputStream, ref APPX_PACKAGE_SETTINGS settings);
    }

    [ComImport, Guid("9099e33b-246f-41e4-881a-008eb613f858"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAppxPackageWriter
    {
        void AddPayloadFile([MarshalAs(UnmanagedType.LPWStr)] string fileName, [MarshalAs(UnmanagedType.LPWStr)] string contentType, int compressionOption, IStream inputStream);
        void Close(IStream manifest);
    }

    [ComImport, Guid("5842a140-ff9f-4166-8f5c-62f5b7b0c781")]
    class AppxFactory { }

    [DllImport("shlwapi.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
    static extern IStream SHCreateStreamOnFileEx(string file, uint mode, uint attributes, bool create, IStream template);

    [DllImport("urlmon.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
    static extern IntPtr CreateUri(string uri, uint flags, IntPtr reserved);

    const uint STGM_READ = 0x0, STGM_SHARE_DENY_WRITE = 0x20, STGM_CREATE = 0x1000, STGM_WRITE = 0x1;
    const int COMPRESSION_NORMAL = 1;

    static string ContentType(string path)
    {
        switch (Path.GetExtension(path).ToLowerInvariant())
        {
            case ".exe": case ".dll": return "application/x-msdownload";
            case ".png": return "image/png";
            case ".xml": return "application/xml";
            case ".jar": return "application/java-archive";
            case ".txt": return "text/plain";
            case ".pri": return "application/octet-stream";
            default: return "application/octet-stream";
        }
    }

    static int Main(string[] args)
    {
        if (args.Length != 2) { Console.Error.WriteLine("PackMsix <folder> <output.msix>"); return 2; }
        string root = Path.GetFullPath(args[0]);
        string output = Path.GetFullPath(args[1]);
        string manifestPath = Path.Combine(root, "AppxManifest.xml");
        try
        {
            if (File.Exists(output)) File.Delete(output);
            var factory = (IAppxFactory)new AppxFactory();
            var settings = new APPX_PACKAGE_SETTINGS { forceZip32 = 0, hashMethod = CreateUri("http://www.w3.org/2001/04/xmlenc#sha256", 0, IntPtr.Zero) };
            IStream outStream = SHCreateStreamOnFileEx(output, STGM_CREATE | STGM_WRITE, 0x80, true, null);
            IAppxPackageWriter writer = factory.CreatePackageWriter(outStream, ref settings);
            int count = 0;
            foreach (string file in Directory.GetFiles(root, "*", SearchOption.AllDirectories))
            {
                string rel = file.Substring(root.Length).TrimStart('\\');
                if (rel.Equals("AppxManifest.xml", StringComparison.OrdinalIgnoreCase)) continue;
                IStream input = SHCreateStreamOnFileEx(file, STGM_READ | STGM_SHARE_DENY_WRITE, 0, false, null);
                writer.AddPayloadFile(rel, ContentType(rel), COMPRESSION_NORMAL, input);
                Marshal.ReleaseComObject(input);
                count++;
            }
            IStream manifest = SHCreateStreamOnFileEx(manifestPath, STGM_READ | STGM_SHARE_DENY_WRITE, 0, false, null);
            writer.Close(manifest);
            Marshal.ReleaseComObject(manifest);
            Marshal.ReleaseComObject(writer);
            Marshal.ReleaseComObject(outStream);
            Console.WriteLine("Packed " + count + " files into " + output);
            return 0;
        }
        catch (COMException e)
        {
            Console.Error.WriteLine("Packing failed (0x" + e.ErrorCode.ToString("X8") + "): " + e.Message);
            try { if (File.Exists(output)) File.Delete(output); } catch { }
            return 1;
        }
    }
}

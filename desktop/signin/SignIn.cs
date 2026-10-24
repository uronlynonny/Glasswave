// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
//
// Glasswave for Windows' YouTube Music sign-in window. It shows Google's own sign-in page in
// Microsoft's built-in Edge web view (WebView2), and once the page lands on YouTube Music it
// writes that site's cookies to a file for the app (the same thing the phone app keeps after
// signing in) and closes. Glasswave never sees the password: you type it into Google's page.
//
// Usage: GlasswaveSignIn.exe <sign-in address> <data folder> <result file>
// The result file gets "COOKIE <cookies>", "BLOCKED" (Google refused this window) or
// "NORUNTIME" (WebView2 isn't installed). Nothing is written if the window is just closed.
// Built with the C# compiler that comes with Windows (see desktop/build.gradle.kts). C# 5 only.

using System;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

static class SignIn
{
    [DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    static string resultFile;
    static bool done;

    // GW_SIGNIN_LOG=<file> (automated checks only): writes what happens, without any cookie values.
    static void Log(string m)
    {
        string f = Environment.GetEnvironmentVariable("GW_SIGNIN_LOG");
        if (string.IsNullOrEmpty(f)) return;
        try { File.AppendAllText(f, DateTime.Now.ToString("HH:mm:ss.fff") + " " + m + Environment.NewLine); } catch { }
    }

    static void Finish(Form form, string result)
    {
        if (done) return;
        done = true;
        try { File.WriteAllText(resultFile, result); } catch { }
        form.Close();
    }

    [STAThread]
    static int Main(string[] args)
    {
        if (args.Length < 3) return 1;
        string startUrl = args[0], dataDir = args[1];
        resultFile = args[2];
        Application.EnableVisualStyles();

        var form = new Form();
        form.Text = "Sign in to YouTube Music";
        form.BackColor = Color.FromArgb(10, 11, 16);
        form.StartPosition = FormStartPosition.CenterScreen;
        try { form.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
        float scale = form.DeviceDpi / 96f;
        form.ClientSize = new Size((int)(480 * scale), (int)(700 * scale));
        form.HandleCreated += delegate
        {
            // Dark, see-through title bar like Glasswave's (Windows 11; ignored elsewhere).
            int on = 1, acrylic = 3;
            DwmSetWindowAttribute(form.Handle, 20, ref on, 4);
            DwmSetWindowAttribute(form.Handle, 38, ref acrylic, 4);
        };

        var web = new WebView2();
        web.Dock = DockStyle.Fill;
        web.DefaultBackgroundColor = Color.FromArgb(10, 11, 16);
        form.Controls.Add(web);

        form.Shown += async delegate
        {
            CoreWebView2Environment env;
            try
            {
                env = await CoreWebView2Environment.CreateAsync(null, dataDir);
                await web.EnsureCoreWebView2Async(env);
            }
            catch (Exception ex)
            {
                Log("webview2 failed: " + ex.GetType().Name + ": " + ex.Message);
                Finish(form, "NORUNTIME");
                return;
            }
            var core = web.CoreWebView2;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.AreDevToolsEnabled = false;
            // Links that would open a new window open here instead.
            core.NewWindowRequested += delegate (object s, CoreWebView2NewWindowRequestedEventArgs e)
            {
                e.Handled = true;
                core.Navigate(e.Uri);
            };
            core.NavigationStarting += delegate (object s, CoreWebView2NavigationStartingEventArgs e)
            {
                if (e.Uri.Contains("/signin/rejected") || e.Uri.Contains("disallowed_useragent")) Finish(form, "BLOCKED");
            };
            // Done once YouTube Music is open and signed in. Checked after each page load, and every
            // second and a half too, in case the site moves on without a full page load.
            bool checking = false;
            string lastLogged = null;
            Action check = async delegate
            {
                string url = core.Source ?? "";
                if (url != lastLogged) { lastLogged = url; Log("at " + url.Split('?')[0]); }
                // GW_SIGNIN_TEST=1 (automated checks only): any YouTube page counts, and the
                // signed-out cookies are taken too, to check the hand-off without an account.
                bool test = Environment.GetEnvironmentVariable("GW_SIGNIN_TEST") == "1";
                Uri at;
                bool onYouTube = Uri.TryCreate(url, UriKind.Absolute, out at) && at.Host.EndsWith("youtube.com");
                if (done || checking || !(url.StartsWith("https://music.youtube.com/") || (test && onYouTube))) return;
                checking = true;
                try
                {
                    var cookies = await core.CookieManager.GetCookiesAsync("https://music.youtube.com");
                    Log("cookies: " + string.Join(", ", cookies.Select(c => c.Name).ToArray()));
                    if (cookies.Count == 0 || (!test && !cookies.Any(c => c.Name == "SAPISID" || c.Name == "__Secure-3PAPISID"))) return;
                    string header = string.Join("; ", cookies.Select(c => c.Name + "=" + c.Value).ToArray());
                    Finish(form, "COOKIE " + header);
                }
                finally { checking = false; }
            };
            core.NavigationCompleted += delegate { check(); };
            var timer = new Timer();
            timer.Interval = 1500;
            timer.Tick += delegate { check(); };
            timer.Start();
            Log("webview2 ready " + env.BrowserVersionString);
            core.Navigate(startUrl);
        };

        Application.Run(form);
        return done ? 0 : 1;
    }
}

// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
//
// Glasswave for Mac's YouTube Music sign-in window: the Mac twin of SignIn.cs. It shows Google's own
// sign-in page in Apple's built-in web view (WebKit, the engine Safari uses), and once the page lands
// on YouTube Music it writes that site's cookies to a file for the app (the same thing the phone app
// keeps after signing in) and closes. Glasswave never sees the password: you type it into Google's page.
//
// Usage: GlasswaveSignIn <sign-in address> <data folder> <result file>
// The result file gets "COOKIE <cookies>" or "BLOCKED" (Google refused this window). Nothing is
// written if the window is just closed. (The data folder is unused: each sign-in starts with a fresh,
// private web view that forgets everything when it closes, so there's no Google session to wipe.)
// Built with the Swift compiler that comes with Xcode's command line tools (see desktop/build.gradle.kts).

import AppKit
import WebKit

// GW_SIGNIN_LOG=<file> (automated checks only): writes what happens, without any cookie values.
func log(_ m: String) {
    guard let f = ProcessInfo.processInfo.environment["GW_SIGNIN_LOG"], !f.isEmpty else { return }
    let stamp = ISO8601DateFormatter().string(from: Date())
    let line = (stamp + " " + m + "\n").data(using: .utf8)!
    if let h = FileHandle(forWritingAtPath: f) { h.seekToEndOfFile(); h.write(line); h.closeFile() }
    else { FileManager.default.createFile(atPath: f, contents: line) }
}

// GW_SIGNIN_TEST=1 (automated checks only): any YouTube page counts, and the signed-out cookies are
// taken too, to check the hand-off without an account.
let testMode = ProcessInfo.processInfo.environment["GW_SIGNIN_TEST"] == "1"

final class SignIn: NSObject, NSApplicationDelegate, NSWindowDelegate, WKNavigationDelegate, WKUIDelegate {
    let startUrl: URL
    let resultFile: String
    var window: NSWindow!
    var web: WKWebView!
    var done = false
    var checking = false
    var lastLogged: String?
    var timer: Timer?

    init(startUrl: URL, resultFile: String) {
        self.startUrl = startUrl
        self.resultFile = resultFile
    }

    func applicationDidFinishLaunching(_ n: Notification) {
        let config = WKWebViewConfiguration()
        // Private and fresh each time: nothing is kept on this Mac after the window closes.
        config.websiteDataStore = .nonPersistent()
        // Says it's Safari (which it is, underneath), so Google shows its normal sign-in page.
        config.applicationNameForUserAgent = "Version/18.0 Safari/605.1.15"

        web = WKWebView(frame: NSRect(x: 0, y: 0, width: 480, height: 700), configuration: config)
        web.navigationDelegate = self
        web.uiDelegate = self
        web.setValue(false, forKey: "drawsBackground")

        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 480, height: 700),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered, defer: false)
        window.title = "Sign in to YouTube Music"
        window.appearance = NSAppearance(named: .darkAqua)
        window.backgroundColor = NSColor(red: 10 / 255, green: 11 / 255, blue: 16 / 255, alpha: 1)
        window.minSize = NSSize(width: 380, height: 500)
        window.contentView = web
        window.delegate = self
        window.isReleasedWhenClosed = false
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        // Done once YouTube Music is open and signed in. Checked after each page load, and every
        // second and a half too, in case the site moves on without a full page load.
        timer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in self?.check() }
        log("webkit ready")
        web.load(URLRequest(url: startUrl))
    }

    func finish(_ result: String) {
        if done { return }
        done = true
        try? result.write(toFile: resultFile, atomically: true, encoding: .utf8)
        window.close()
    }

    func check() {
        let url = web.url?.absoluteString ?? ""
        if url != lastLogged { lastLogged = url; log("at " + (url.components(separatedBy: "?").first ?? "")) }
        let onYouTube = (web.url?.host ?? "").hasSuffix("youtube.com")
        if done || checking || !(url.hasPrefix("https://music.youtube.com/") || (testMode && onYouTube)) { return }
        checking = true
        web.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] all in
            guard let self = self else { return }
            defer { self.checking = false }
            // The cookies a request to https://music.youtube.com would carry.
            let cookies = all.filter { c in
                let d = c.domain.hasPrefix(".") ? String(c.domain.dropFirst()) : c.domain
                return "music.youtube.com" == d || "music.youtube.com".hasSuffix("." + d)
            }
            log("cookies: " + cookies.map { $0.name }.joined(separator: ", "))
            if cookies.isEmpty || (!testMode && !cookies.contains { $0.name == "SAPISID" || $0.name == "__Secure-3PAPISID" }) { return }
            let header = cookies.map { $0.name + "=" + $0.value }.joined(separator: "; ")
            self.finish("COOKIE " + header)
        }
    }

    // Google refused to sign in from this window.
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let u = action.request.url?.absoluteString ?? ""
        if u.contains("/signin/rejected") || u.contains("disallowed_useragent") { finish("BLOCKED") }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { check() }

    // Links that would open a new window open here instead.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let u = action.request.url { webView.load(URLRequest(url: u)) }
        return nil
    }

    func windowWillClose(_ n: Notification) {
        timer?.invalidate()
        NSApp.terminate(nil)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ app: NSApplication) -> Bool { true }
}

/// A small menu bar: Quit, and Edit so ⌘V pastes (a password from a password manager, say) and ⌘C copies.
func makeMenu() -> NSMenu {
    let bar = NSMenu()
    let appItem = NSMenuItem()
    bar.addItem(appItem)
    let appMenu = NSMenu()
    appMenu.addItem(withTitle: "Close Sign-in", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
    appItem.submenu = appMenu

    let editItem = NSMenuItem()
    bar.addItem(editItem)
    let edit = NSMenu(title: "Edit")
    edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
    edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
    edit.addItem(.separator())
    edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
    edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
    edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
    edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
    editItem.submenu = edit
    return bar
}

let args = CommandLine.arguments
guard args.count >= 4, let url = URL(string: args[1]) else { exit(1) }
let app = NSApplication.shared
app.setActivationPolicy(.regular)
// Glasswave's icon in the Dock while the window is open (shipped next to this program).
let iconPath = URL(fileURLWithPath: args[0]).deletingLastPathComponent().appendingPathComponent("glasswave.icns").path
if let icon = NSImage(contentsOfFile: iconPath) { app.applicationIconImage = icon }
app.mainMenu = makeMenu()
let delegate = SignIn(startUrl: url, resultFile: args[3])
app.delegate = delegate
// Runs until the window closes (that quits this program).
app.run()

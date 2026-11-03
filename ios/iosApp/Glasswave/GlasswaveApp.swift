// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
import SwiftUI
import GlasswaveKit

/// Glasswave for iPhone. The screens are the Kotlin part (GlasswaveKit); this just shows them.
@main
struct GlasswaveApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeHost()
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
        }
    }
}

struct ComposeHost: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainKt.MainViewController(audio: AudioEngine.shared)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

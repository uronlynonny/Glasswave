// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
import AVFoundation
import GlasswaveKit
import MediaPlayer
import UIKit
import YouTubeKit

/// The sound: finds a song's audio with YouTubeKit and plays it with Apple's player, with the lock
/// screen and Control Centre controls. The Kotlin side (Player.kt) decides what to play.
final class AudioEngine: NSObject, NativeAudio {
    static let shared = AudioEngine()

    private let player = AVPlayer()
    private var listener: AudioListener?
    private var current = ""
    private var lookup: Task<Void, Never>?
    private var endObserver: NSObjectProtocol?
    private var statusObserver: NSKeyValueObservation?
    private var stateObserver: NSKeyValueObservation?
    private var info: [String: Any] = [:]

    private struct NoAudio: LocalizedError {
        var errorDescription: String? { "No playable audio for this song" }
    }

    override init() {
        super.init()
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        stateObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                self.listener?.onPlayingChanged(playing: p.timeControlStatus != .paused)
                self.updateClock()
            }
        }
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in self?.play(); return .success }
        c.pauseCommand.addTarget { [weak self] _ in self?.pause(); return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            if self.player.timeControlStatus == .paused { self.play() } else { self.pause() }
            return .success
        }
        c.nextTrackCommand.addTarget { [weak self] _ in self?.listener?.onRemoteNext(); return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in self?.listener?.onRemotePrevious(); return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seekTo(seconds: e.positionTime)
            return .success
        }
        // A phone call or Siri pauses the song; carry on afterwards if iOS says so.
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .ended,
                  let opts = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt,
                  AVAudioSession.InterruptionOptions(rawValue: opts).contains(.shouldResume) else { return }
            self?.play()
        }
    }

    func setListener(listener: AudioListener) {
        self.listener = listener
    }

    func load(videoId: String, title: String, artist: String, artUrl: String?, seconds: Double) {
        lookup?.cancel()
        player.pause()
        current = videoId
        info = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: artist,
            MPMediaItemPropertyPlaybackDuration: seconds,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: 0.0,
            MPNowPlayingInfoPropertyPlaybackRate: 0.0,
        ]
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        if let artUrl, let url = URL(string: artUrl) { loadArt(url, for: videoId) }
        lookup = Task { [weak self] in
            do {
                let url = try await AudioEngine.audioURL(videoId)
                await MainActor.run {
                    guard let self, self.current == videoId else { return }
                    self.start(url)
                }
            } catch {
                await MainActor.run {
                    guard let self, self.current == videoId, !Task.isCancelled else { return }
                    self.listener?.onFailed(message: "\(error)")
                }
            }
        }
    }

    /// The best audio-only stream iOS can play by itself (AAC in .m4a first).
    private static func audioURL(_ videoId: String) async throws -> URL {
        let streams = try await YouTube(videoID: videoId, methods: [.local, .remote]).streams
        let audio = streams.filterAudioOnly().filter { $0.isNativelyPlayable }
        guard let best = audio.filter({ $0.fileExtension == .m4a }).highestAudioBitrateStream() ?? audio.highestAudioBitrateStream() else {
            throw NoAudio()
        }
        return best.url
    }

    private func start(_ url: URL) {
        try? AVAudioSession.sharedInstance().setActive(true)
        let item = AVPlayerItem(url: url)
        if let o = endObserver { NotificationCenter.default.removeObserver(o) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            self?.listener?.onEnded()
        }
        statusObserver = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            DispatchQueue.main.async {
                switch it.status {
                case .readyToPlay:
                    self?.listener?.onReady()
                    self?.updateClock()
                case .failed:
                    self?.listener?.onFailed(message: it.error?.localizedDescription ?? "The song couldn't be played")
                default:
                    break
                }
            }
        }
        player.replaceCurrentItem(with: item)
        player.play()
    }

    private func loadArt(_ url: URL, for videoId: String) {
        URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
            guard let data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard let self, self.current == videoId else { return }
                self.info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = self.info
            }
        }.resume()
    }

    /// Keeps the lock screen's progress bar in step.
    private func updateClock() {
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position()
        info[MPNowPlayingInfoPropertyPlaybackRate] = player.timeControlStatus == .paused ? 0.0 : 1.0
        let d = duration()
        if d > 0 { info[MPMediaItemPropertyPlaybackDuration] = d }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    func play() {
        try? AVAudioSession.sharedInstance().setActive(true)
        player.play()
    }

    func pause() {
        player.pause()
    }

    func seekTo(seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600)) { [weak self] _ in
            DispatchQueue.main.async { self?.updateClock() }
        }
    }

    func position() -> Double {
        let s = player.currentTime().seconds
        return s.isFinite ? s : 0
    }

    func duration() -> Double {
        guard let s = player.currentItem?.duration.seconds, s.isFinite else { return 0 }
        return s
    }
}

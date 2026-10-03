// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

/// Dejavu's golden yellow look, on warm charcoal in dark mode and warm paper in light mode, the same colors as the
/// Android app's `DejavuColors`. Private browsing keeps Firefox's purple.
enum DejavuColors {
    private static func rgb(_ value: UInt32) -> UIColor {
        UIColor(argb: argbInt(value))
    }

    private static let gold10 = rgb(0xFF26_1A00)
    private static let gold20 = rgb(0xFF3F_2E00)
    private static let gold30 = rgb(0xFF5C_4200)
    private static let gold45 = rgb(0xFF8C_6300)
    private static let gold80 = rgb(0xFFFF_C83D)
    private static let gold90 = rgb(0xFFFF_DF9E)
    private static let sand10 = rgb(0xFF2A_2010)
    private static let sand30 = rgb(0xFF4A_3F2C)
    private static let sand90 = rgb(0xFFF3_E5C8)
    private static let warm0 = rgb(0xFFFF_FBF5)
    private static let warm5 = rgb(0xFFFA_F6EF)
    private static let warm10 = rgb(0xFFF2_EDE4)
    private static let warm15 = rgb(0xFFE8_E2D7)
    private static let warm45 = rgb(0xFF82_7B70)
    private static let warm60 = rgb(0xFF40_3B34)
    private static let warm65 = rgb(0xFF32_2E28)
    private static let warm70 = rgb(0xFF26_231E)
    private static let warm75 = rgb(0xFF1D_1A16)
    private static let warm80 = rgb(0xFF18_1613)
    private static let ink = rgb(0xFF1F_1B13)
    private static let inkA70 = rgb(0xB21F_1B13)
    private static let paper = rgb(0xFFF5_F0E6)
    private static let paperA70 = rgb(0xB2F5_F0E6)

    static let primary = UIColor.dynamic(light: gold45, dark: gold80)
    static let onPrimary = UIColor.dynamic(light: .white, dark: gold20)
    static let primaryContainer = UIColor.dynamic(light: gold90, dark: gold30)
    static let onPrimaryContainer = UIColor.dynamic(light: gold10, dark: gold90)
    static let secondaryContainer = UIColor.dynamic(light: sand90, dark: sand30)
    static let onSecondaryContainer = UIColor.dynamic(light: sand10, dark: sand90)
    static let surface = UIColor.dynamic(light: warm5, dark: warm75)
    static let onSurface = UIColor.dynamic(light: ink, dark: paper)
    static let onSurfaceVariant = UIColor.dynamic(light: inkA70, dark: paperA70)
    static let surfaceContainerLow = UIColor.dynamic(light: warm0, dark: warm80)
    static let surfaceContainer = UIColor.dynamic(light: warm5, dark: warm75)
    static let surfaceContainerHigh = UIColor.dynamic(light: warm10, dark: warm70)
    static let surfaceContainerHighest = UIColor.dynamic(light: warm15, dark: warm65)
    static let outline = warm45
    static let outlineVariant = UIColor.dynamic(light: warm15, dark: warm60)
    static let error = UIColor.dynamic(light: rgb(0xFFC5_2D4F), dark: rgb(0xFFFF_8090))

    /// The purple of Firefox's private browsing.
    static let privateAccent = rgb(0xFF75_42E5)
}

/// Dejavu's colors for SwiftUI.
enum DejavuColor {
    static let primary = Color(uiColor: DejavuColors.primary)
    static let onPrimary = Color(uiColor: DejavuColors.onPrimary)
    static let primaryContainer = Color(uiColor: DejavuColors.primaryContainer)
    static let onPrimaryContainer = Color(uiColor: DejavuColors.onPrimaryContainer)
    static let secondaryContainer = Color(uiColor: DejavuColors.secondaryContainer)
    static let onSecondaryContainer = Color(uiColor: DejavuColors.onSecondaryContainer)
    static let surface = Color(uiColor: DejavuColors.surface)
    static let onSurface = Color(uiColor: DejavuColors.onSurface)
    static let onSurfaceVariant = Color(uiColor: DejavuColors.onSurfaceVariant)
    static let surfaceContainerLow = Color(uiColor: DejavuColors.surfaceContainerLow)
    static let surfaceContainer = Color(uiColor: DejavuColors.surfaceContainer)
    static let surfaceContainerHigh = Color(uiColor: DejavuColors.surfaceContainerHigh)
    static let surfaceContainerHighest = Color(uiColor: DejavuColors.surfaceContainerHighest)
    static let outline = Color(uiColor: DejavuColors.outline)
    static let outlineVariant = Color(uiColor: DejavuColors.outlineVariant)
    static let error = Color(uiColor: DejavuColors.error)
    static let privateAccent = Color(uiColor: DejavuColors.privateAccent)
}

/// Dejavu's translucent "glass" surfaces, which let the background show through: a little brighter at the top to
/// catch the light, with a hairline around them.
struct GlassBackground<S: InsettableShape>: View {
    let shape: S

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let dark = colorScheme == .dark
        shape
            .fill(
                LinearGradient(
                    colors: [Color.white.opacity(dark ? 0.16 : 0.72), Color.white.opacity(dark ? 0.08 : 0.5)],
                    startPoint: .top,
                    endPoint: .bottom)
            )
            .overlay(shape.strokeBorder(Color.white.opacity(dark ? 0.14 : 0.8), lineWidth: 1))
    }
}

extension View {
    /// Draws a glass surface in `shape` behind the view.
    func glass<S: InsettableShape>(_ shape: S) -> some View {
        background(GlassBackground(shape: shape))
    }
}

/// The background of the private workspace, in the purple of Firefox's private browsing.
let privateWorkspaceTheme = WorkspaceTheme(
    colors: [argbInt(0xFF75_42E5), argbInt(0xFF25_003E)], opacity: 0.35, texture: 0.2)

/// Draws a workspace's theme over the plain background, the way the home screen shows the workspace: its gradient,
/// then its grain. While swiping it blends `fraction` of the way to the theme of the next workspace; a workspace
/// without a theme fades the gradient out.
struct WorkspaceBackground: View {
    let theme: WorkspaceTheme?
    var next: WorkspaceTheme? = nil
    var fraction: Double = 0

    private static let stops = 3
    private static let grainStrength = 0.5

    var body: some View {
        if let blend = blended {
            ZStack {
                LinearGradient(colors: blend.colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                if blend.texture > 0 {
                    Image(uiImage: GrainImage.shared)
                        .resizable(resizingMode: .tile)
                        .opacity(blend.texture * Self.grainStrength)
                }
            }
            .allowsHitTesting(false)
        } else {
            Color.clear
        }
    }

    private var blended: (colors: [Color], texture: Double)? {
        let faded: (WorkspaceTheme) -> WorkspaceTheme = { theme in
            WorkspaceTheme(colors: theme.colors, opacity: 0, texture: 0)
        }
        guard let from = theme ?? next.map(faded), let to = next ?? theme.map(faded) else { return nil }
        let amount = fraction.clamped(0, 1)
        let opacity = from.opacity + (to.opacity - from.opacity) * amount
        let texture = from.texture + (to.texture - from.texture) * amount
        let colors = (0..<Self.stops).map { stop -> Color in
            let start = UIColor(argb: from.colors[min(stop, from.colors.count - 1)])
            let end = UIColor(argb: to.colors[min(stop, to.colors.count - 1)])
            return Color(uiColor: Self.mix(start, end, amount)).opacity(opacity)
        }
        return (colors: colors, texture: texture)
    }

    private static func mix(_ start: UIColor, _ end: UIColor, _ amount: Double) -> UIColor {
        var (r1, g1, b1, a1): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        var (r2, g2, b2, a2): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        start.getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        end.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        let t = CGFloat(amount)
        return UIColor(
            red: r1 + (r2 - r1) * t, green: g1 + (g2 - g1) * t, blue: b1 + (b2 - b1) * t, alpha: a1 + (a2 - a1) * t)
    }
}

/// A tile of fine noise, repeated over the background to give a theme its grain like Zen's texture.
enum GrainImage {
    private static let size = 128
    private static let maxAlpha: UInt8 = 90

    static let shared: UIImage = {
        var generator = SeededRandom(seed: UInt64(size))
        var pixels = [UInt8](repeating: 0, count: size * size * 4)
        for index in 0..<(size * size) {
            let shade: UInt8 = generator.next() % 2 == 0 ? 255 : 0
            let alpha = UInt8(generator.next() % UInt64(maxAlpha))
            // Premultiplied RGBA.
            let value = UInt8((UInt32(shade) * UInt32(alpha)) / 255)
            pixels[index * 4] = value
            pixels[index * 4 + 1] = value
            pixels[index * 4 + 2] = value
            pixels[index * 4 + 3] = alpha
        }
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        guard let provider = CGDataProvider(data: Data(pixels) as CFData),
            let image = CGImage(
                width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: size * 4,
                space: colorSpace, bitmapInfo: info, provider: provider, decode: nil, shouldInterpolate: false,
                intent: .defaultIntent)
        else {
            return UIImage()
        }
        return UIImage(cgImage: image)
    }()
}

/// A small random number generator with a fixed seed, so the grain looks the same every time.
struct SeededRandom: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed &+ 0x9E37_79B9_7F4A_7C15
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var value = state
        value = (value ^ (value >> 30)) &* 0xBF58_476D_1CE4_E5B9
        value = (value ^ (value >> 27)) &* 0x94D0_49BB_1331_11EB
        return value ^ (value >> 31)
    }
}

/// The favicon of a site: its /favicon.ico, fetched once without cookies and kept on disk, or its first letter in a
/// circle when it has none.
struct FaviconView: View {
    let url: String
    var size: CGFloat = 20

    @StateObject private var loader = FaviconLoader()

    var body: some View {
        Group {
            if let image = loader.image {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .aspectRatio(contentMode: .fit)
                    .clipShape(Circle())
            } else {
                ZStack {
                    Circle().fill(DejavuColor.surfaceContainerHighest)
                    Text(letter)
                        .font(.system(size: size * 0.55, weight: .semibold))
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                }
            }
        }
        .frame(width: size, height: size)
        .onAppear { loader.load(url) }
        .onChange(of: url) { _, newValue in loader.load(newValue) }
    }

    private var letter: String {
        let host = displayUrl(url)
        return host.first.map { String($0).uppercased() } ?? "•"
    }
}

/// Loads and caches favicons for `FaviconView`.
final class FaviconLoader: ObservableObject {
    @Published var image: UIImage?

    private var host: String?

    func load(_ url: String) {
        guard let host = URL(string: url)?.host, url.hasPrefix("http") else {
            image = nil
            self.host = nil
            return
        }
        guard host != self.host else { return }
        self.host = host
        image = FaviconCache.shared.cached(host)
        if image == nil {
            FaviconCache.shared.fetch(host) { [weak self] fetched in
                guard let self, self.host == host else { return }
                self.image = fetched
            }
        }
    }
}

/// Favicons by host, in memory and in Caches/Favicons.
final class FaviconCache {
    static let shared = FaviconCache()

    private let memory = NSCache<NSString, UIImage>()
    private var missing = Set<String>()
    private var waiting: [String: [(UIImage?) -> Void]] = [:]
    private let directory: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("Favicons", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.timeoutIntervalForRequest = 15
        return URLSession(configuration: configuration)
    }()

    func cached(_ host: String) -> UIImage? {
        if let image = memory.object(forKey: host as NSString) {
            return image
        }
        if let data = try? Data(contentsOf: file(host)), let image = UIImage(data: data) {
            memory.setObject(image, forKey: host as NSString)
            return image
        }
        return nil
    }

    /// Fetches the favicon of `host` and calls `completion` on the main thread with it, or with `nil`.
    func fetch(_ host: String, completion: @escaping (UIImage?) -> Void) {
        if missing.contains(host) {
            completion(nil)
            return
        }
        if waiting[host] != nil {
            waiting[host]?.append(completion)
            return
        }
        waiting[host] = [completion]
        guard let url = URL(string: "https://\(host)/favicon.ico") else { return }
        session.dataTask(with: url) { [weak self] data, response, _ in
            let ok = ((response as? HTTPURLResponse)?.statusCode ?? 0) == 200
            let image = ok ? data.flatMap { UIImage(data: $0) } : nil
            DispatchQueue.main.async {
                guard let self else { return }
                if let image, let data {
                    self.memory.setObject(image, forKey: host as NSString)
                    try? data.write(to: self.file(host))
                } else {
                    self.missing.insert(host)
                }
                let callbacks = self.waiting.removeValue(forKey: host) ?? []
                callbacks.forEach { $0(image) }
            }
        }.resume()
    }

    private func file(_ host: String) -> URL {
        directory.appendingPathComponent(host.replacingOccurrences(of: "/", with: "_"))
    }
}

/// A small round button with an icon, at least 44 points to tap.
struct IconButton: View {
    let icon: Icon
    let label: String
    var tint: Color = DejavuColor.onSurface
    var size: CGFloat = 20
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(icon: icon)
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
                .foregroundStyle(tint)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// The icon of a container in its color.
struct ContainerIconView: View {
    let record: ContainerRecord
    var size: CGFloat = 20

    var body: some View {
        Image(icon: record.icon.icon)
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .foregroundStyle(record.color.color)
            .frame(width: size, height: size)
            .accessibilityLabel(record.name)
    }
}

/// Stands for "no container" wherever containers can be picked, so it never looks like one of them.
struct NoContainerIconView: View {
    var size: CGFloat = 20

    var body: some View {
        Image(icon: .asset("dejavu-no-container"))
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .foregroundStyle(DejavuColor.onSurfaceVariant)
            .frame(width: size, height: size)
            .accessibilityLabel(L10n.workspaceNoContainer)
    }
}

/// Stands for a new temporary container.
struct TemporaryContainerIconView: View {
    var size: CGFloat = 20

    var body: some View {
        Image(icon: .asset("dejavu-temporary-container"))
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .foregroundStyle(DejavuColor.onSurfaceVariant)
            .frame(width: size, height: size)
            .accessibilityLabel(L10n.temporaryContainer)
    }
}

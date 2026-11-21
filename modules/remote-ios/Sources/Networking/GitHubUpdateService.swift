import Foundation
import Observation

struct GitHubReleaseInfo: Equatable, Sendable {
    let version: String
    let buildNumber: Int
    let notes: String
    let ipaURL: URL
    let releaseURL: URL
}

/// Checks GitHub Releases for newer IPA builds and provides 1-tap iPhone-only
/// OTA installation links for SideStore, AltStore, and direct IPA download.
@MainActor
@Observable
final class GitHubUpdateService {
    static let repoSlug = "luxfajah/hy300-remote-ios"
    static let latestReleaseAPI = URL(string: "https://api.github.com/repos/luxfajah/hy300-remote-ios/releases/latest")!
    static let fallbackIPAURL = URL(string: "https://github.com/luxfajah/hy300-remote-ios/releases/latest/download/HY300Remote.ipa")!
    static let fallbackReleaseURL = URL(string: "https://github.com/luxfajah/hy300-remote-ios/releases/latest")!
    static let altStoreSourceURL = "https://raw.githubusercontent.com/luxfajah/hy300-remote-ios/main/altstore-source.json"

    let currentVersion: String
    let currentBuild: Int

    var latestRelease: GitHubReleaseInfo? = nil
    var hasUpdate: Bool = false
    var isChecking: Bool = false
    var lastCheckedAt: Date? = nil
    var lastError: String? = nil

    init() {
        let info = Bundle.main.infoDictionary
        self.currentVersion = (info?["CFBundleShortVersionString"] as? String) ?? "1.0.0"
        let buildString = (info?["CFBundleVersion"] as? String) ?? "1"
        self.currentBuild = Int(buildString) ?? 1
    }

    // MARK: - 1-Tap iPhone OTA URLs

    var sideStoreInstallURL: URL? {
        guard let encoded = Self.altStoreSourceURL.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return nil
        }
        return URL(string: "sidestore://source?url=\(encoded)")
    }

    var altStoreInstallURL: URL? {
        guard let encoded = Self.altStoreSourceURL.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return nil
        }
        return URL(string: "altstore://source?url=\(encoded)")
    }

    var directIPAURL: URL {
        latestRelease?.ipaURL ?? Self.fallbackIPAURL
    }

    // MARK: - Check Latest GitHub Release

    func checkForUpdates() async {
        guard !isChecking else { return }
        isChecking = true
        lastError = nil
        defer {
            isChecking = false
            lastCheckedAt = Date()
        }

        var request = URLRequest(url: Self.latestReleaseAPI)
        request.httpMethod = "GET"
        request.timeoutInterval = 8
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("HY300Remote-iOS/\(currentVersion)", forHTTPHeaderField: "User-Agent")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                lastError = http.statusCode == 404
                    ? "Nenhuma release publicada ainda"
                    : "Erro GitHub (HTTP \(http.statusCode))"
                return
            }

            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let tagName = json["tag_name"] as? String else {
                lastError = "Resposta inválida do GitHub"
                return
            }

            let remoteVersion = Self.normalizeVersion(tagName)
            let remoteBuild = Self.extractBuildNumber(from: remoteVersion)
            let notes = (json["body"] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let releaseURL = (json["html_url"] as? String).flatMap(URL.init(string:))
                ?? Self.fallbackReleaseURL

            var ipaURL = Self.fallbackIPAURL
            if let assets = json["assets"] as? [[String: Any]] {
                for asset in assets {
                    if let name = asset["name"] as? String,
                       name.lowercased().hasSuffix(".ipa"),
                       let dlStr = asset["browser_download_url"] as? String,
                       let url = URL(string: dlStr) {
                        ipaURL = url
                        break
                    }
                }
            }

            let release = GitHubReleaseInfo(
                version: remoteVersion,
                buildNumber: remoteBuild,
                notes: notes,
                ipaURL: ipaURL,
                releaseURL: releaseURL
            )
            latestRelease = release
            hasUpdate = Self.isRemoteNewer(
                remoteVersion: remoteVersion,
                remoteBuild: remoteBuild,
                localVersion: currentVersion,
                localBuild: currentBuild
            )
        } catch {
            lastError = "Sem conexão com GitHub"
        }
    }

    // MARK: - Version Comparison

    private static func normalizeVersion(_ tag: String) -> String {
        let trimmed = tag.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.lowercased().hasPrefix("v") {
            return String(trimmed.dropFirst())
        }
        return trimmed
    }

    private static func extractBuildNumber(from version: String) -> Int {
        let parts = version.split(separator: ".").compactMap { Int($0) }
        return parts.last ?? 0
    }

    private static func isRemoteNewer(
        remoteVersion: String,
        remoteBuild: Int,
        localVersion: String,
        localBuild: Int
    ) -> Bool {
        let cmp = compareSemver(remoteVersion, localVersion)
        if cmp != 0 {
            return cmp > 0
        }
        return remoteBuild > localBuild
    }

    private static func compareSemver(_ lhs: String, _ rhs: String) -> Int {
        let lParts = lhs.split(separator: ".").map { Int($0) ?? 0 }
        let rParts = rhs.split(separator: ".").map { Int($0) ?? 0 }
        let count = max(lParts.count, rParts.count)
        for i in 0..<count {
            let l = i < lParts.count ? lParts[i] : 0
            let r = i < rParts.count ? rParts[i] : 0
            if l != r { return l > r ? 1 : -1 }
        }
        return 0
    }
}

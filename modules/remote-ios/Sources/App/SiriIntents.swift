import AppIntents
import Foundation

/// Siri Voice Commands for HY300Pro Projector — dynamically uses the saved/discovered IP.
struct WakeProjectorIntent: AppIntent {
    static var title: LocalizedStringResource = "Ligar o Projetor"
    static var description = IntentDescription("Acorda o projetor HY300Pro do modo Standby.")

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await callBridge("/api/power/wake")
        return .result(dialog: "Projetor HY300 ligado.")
    }
}

struct StandbyProjectorIntent: AppIntent {
    static var title: LocalizedStringResource = "Colocar Projetor em Standby"
    static var description = IntentDescription("Coloca o projetor HY300Pro em Standby mantendo o Wi-Fi ativo.")

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await callBridge("/api/power/standby")
        return .result(dialog: "Projetor em modo standby.")
    }
}

struct OpenStremioIntent: AppIntent {
    static var title: LocalizedStringResource = "Abrir Stremio no Projetor"
    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await callBridge("/api/app/stremio")
        return .result(dialog: "Abrindo Stremio no projetor.")
    }
}

struct OpenUniTVIntent: AppIntent {
    static var title: LocalizedStringResource = "Abrir UniTV no Projetor"
    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await callBridge("/api/app/unitv")
        return .result(dialog: "Abrindo UniTV no projetor.")
    }
}

struct OpenSmartTubeIntent: AppIntent {
    static var title: LocalizedStringResource = "Abrir SmartTube no Projetor"
    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await callBridge("/api/app/smarttube")
        return .result(dialog: "Abrindo SmartTube no projetor.")
    }
}

struct OpenURLOnProjectorIntent: AppIntent {
    static var title: LocalizedStringResource = "Enviar Link para o Projetor"
    static var description = IntentDescription("Abre um link do YouTube, Stremio, Twitch, Apple Music ou navegador diretamente no projetor HY300Pro.")

    @Parameter(title: "Link ou URL")
    var url: String

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let host = RemoteStorage.savedHost()
        guard let endpoint = URL(string: "http://\(host):8090/api/open_url") else {
            return .result(dialog: "IP do projetor não configurado.")
        }
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["url": url])
        req.timeoutInterval = 5
        _ = try await URLSession.shared.data(for: req)
        return .result(dialog: "Link enviado para o projetor.")
    }
}

struct HY300Shortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: WakeProjectorIntent(),
            phrases: [
                "Ligar o projetor com \(.applicationName)",
                "Acordar projetor com \(.applicationName)"
            ],
            shortTitle: "Ligar Projetor",
            systemImageName: "power"
        )
        AppShortcut(
            intent: StandbyProjectorIntent(),
            phrases: [
                "Desligar o projetor com \(.applicationName)",
                "Standby no projetor com \(.applicationName)"
            ],
            shortTitle: "Standby Projetor",
            systemImageName: "moon.zzz"
        )
        AppShortcut(
            intent: OpenStremioIntent(),
            phrases: ["Abrir Stremio no projetor com \(.applicationName)"],
            shortTitle: "Abrir Stremio",
            systemImageName: "film.stack"
        )
        AppShortcut(
            intent: OpenUniTVIntent(),
            phrases: ["Abrir UniTV no projetor com \(.applicationName)"],
            shortTitle: "Abrir UniTV",
            systemImageName: "tv"
        )
        AppShortcut(
            intent: OpenSmartTubeIntent(),
            phrases: ["Abrir SmartTube no projetor com \(.applicationName)"],
            shortTitle: "Abrir SmartTube",
            systemImageName: "play.rectangle.fill"
        )
    }
}

private func callBridge(_ path: String) async throws {
    let host = RemoteStorage.savedHost()
    guard let url = URL(string: "http://\(host):8090" + path) else { return }
    var req = URLRequest(url: url)
    req.httpMethod = "POST"
    req.timeoutInterval = 5
    _ = try await URLSession.shared.data(for: req)
}

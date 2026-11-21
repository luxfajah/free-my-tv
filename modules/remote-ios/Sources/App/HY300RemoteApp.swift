import SwiftUI

@main
struct HY300RemoteApp: App {
    @State private var client = ProjectorClient()
    @State private var discovery = BonjourDiscoveryService()
    @State private var updateService = GitHubUpdateService()

    var body: some Scene {
        WindowGroup {
            RemoteRootView(
                client: client,
                discovery: discovery,
                updateService: updateService
            )
            .preferredColorScheme(.dark)
            .onOpenURL { incoming in
                if let comps = URLComponents(url: incoming, resolvingAgainstBaseURL: false),
                   let target = comps.queryItems?.first(where: { $0.name == "url" || $0.name == "u" })?.value,
                   !target.isEmpty {
                    client.openURL(target)
                }
            }
        }
    }
}

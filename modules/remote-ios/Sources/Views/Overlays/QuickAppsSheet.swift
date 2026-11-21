import SwiftUI

/// Quick Apps launcher sheet:
/// 1. Prominent "Fechar Apps em 2º Plano" RAM cleaner action card
/// 2. Dynamic 3-column "Dock do Projetor" with real projector PNG icons via AsyncImage
/// 3. Always-accessible "Atalhos do Projetor" row (HDMI, Keystone, Bluetooth, Ajustes)
struct QuickAppsSheet: View {
    let client: ProjectorClient
    @Environment(\.dismiss) private var dismiss

    private let dockColumns = [
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12)
    ]

    private let shortcutColumns = [
        GridItem(.flexible(), spacing: 10),
        GridItem(.flexible(), spacing: 10)
    ]

    private let systemShortcuts: [ProjectorApp] = [.hdmi, .keystone, .bluetooth, .settings]
    private let fallbackDockApps: [ProjectorApp] = [.stremio, .unitv, .smarttube]

    var body: some View {
        NavigationStack {
            ZStack {
                DesignTokens.obsidianBG.ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        killBackgroundAppsCard
                        dockSection
                        projectorShortcutsSection
                    }
                    .padding(18)
                }
            }
            .navigationTitle("Aplicativos")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Fechar") {
                        dismiss()
                    }
                    .foregroundStyle(DesignTokens.labelPrimary)
                }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            client.requestDockApps()
        }
    }

    // MARK: - 1. Close All Background Apps Card

    private var killBackgroundAppsCard: some View {
        Button {
            client.killBackgroundApps()
        } label: {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.orange.opacity(0.20))
                        .frame(width: 44, height: 44)

                    if client.isKillingBackgroundApps {
                        ProgressView()
                            .tint(.orange)
                    } else {
                        Image(systemName: "xmark.app.fill")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(.orange)
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(client.isKillingBackgroundApps ? "Limpando memória…" : "Fechar Apps em 2º Plano")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(DesignTokens.labelPrimary)
                    Text("Libera memória RAM no projetor")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(DesignTokens.labelSecondary)
                }

                Spacer()

                Image(systemName: "sparkles")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.orange.opacity(0.85))
            }
            .padding(14)
        }
        .buttonStyle(.plain)
        .disabled(client.isKillingBackgroundApps)
        .hyGlassRounded(cornerRadius: 22, interactive: true)
        .accessibilityLabel("Fechar aplicativos em segundo plano no projetor")
    }

    // MARK: - 2. Dynamic Dock do Projetor (Real PNG Icons)

    private var dockSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("DOCK DO PROJETOR")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(DesignTokens.labelTertiary)

                Spacer()

                Button {
                    HapticEngine.shared.selection()
                    client.requestDockApps()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 11, weight: .semibold))
                        Text("Sincronizar")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundStyle(DesignTokens.labelSecondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Sincronizar aplicativos da dock")
            }

            if !client.dockApps.isEmpty {
                LazyVGrid(columns: dockColumns, spacing: 12) {
                    ForEach(client.dockApps) { app in
                        dockAppTile(app)
                    }
                }
            } else {
                LazyVGrid(columns: dockColumns, spacing: 12) {
                    ForEach(fallbackDockApps) { app in
                        fallbackAppTile(app)
                    }
                }
            }
        }
    }

    private func dockAppTile(_ app: DockAppItem) -> some View {
        let tint = app.parsedColor
        return Button {
            HapticEngine.shared.tapMedium()
            client.launchDockApp(app.pkg, label: app.label)
            dismiss()
        } label: {
            VStack(spacing: 8) {
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(tint.opacity(0.18))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(tint.opacity(0.32), lineWidth: 1)
                        )
                        .shadow(color: tint.opacity(0.28), radius: 8, x: 0, y: 4)

                    if client.activeTransport == .wifi, let url = client.iconURL(for: app) {
                        AsyncImage(url: url) { phase in
                            switch phase {
                            case .success(let image):
                                image
                                    .resizable()
                                    .interpolation(.high)
                                    .aspectRatio(contentMode: .fill)
                                    .frame(width: 58, height: 58)
                                    .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                            case .empty, .failure:
                                dockFallbackGlyph(for: app, tint: tint)
                            @unknown default:
                                dockFallbackGlyph(for: app, tint: tint)
                            }
                        }
                    } else {
                        dockFallbackGlyph(for: app, tint: tint)
                    }
                }
                .frame(width: 60, height: 60)

                Text(app.label)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(DesignTokens.labelPrimary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .padding(.vertical, 10)
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .hyGlassRounded(cornerRadius: 20, interactive: true)
        .accessibilityLabel("Abrir \(app.label)")
    }

    private func dockFallbackGlyph(for app: DockAppItem, tint: Color) -> some View {
        let initial = String(app.label.prefix(1)).uppercased()
        return Text(initial.isEmpty ? "A" : initial)
            .font(.system(size: 24, weight: .bold, design: .rounded))
            .foregroundStyle(tint)
    }

    private func fallbackAppTile(_ app: ProjectorApp) -> some View {
        Button {
            HapticEngine.shared.tapMedium()
            client.launchApp(app)
            dismiss()
        } label: {
            VStack(spacing: 8) {
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(app.accentColor.opacity(0.20))
                        .frame(width: 58, height: 58)
                    Image(systemName: app.systemImage)
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundStyle(app.accentColor)
                }

                Text(app.title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(DesignTokens.labelPrimary)
                    .lineLimit(1)
            }
            .padding(.vertical, 10)
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .hyGlassRounded(cornerRadius: 20, interactive: true)
        .accessibilityLabel("Abrir \(app.title)")
    }

    // MARK: - 3. Always-Accessible Projector Shortcuts

    private var projectorShortcutsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("ATALHOS DO PROJETOR")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(DesignTokens.labelTertiary)

            LazyVGrid(columns: shortcutColumns, spacing: 10) {
                ForEach(systemShortcuts) { app in
                    Button {
                        HapticEngine.shared.tapMedium()
                        client.launchApp(app)
                        dismiss()
                    } label: {
                        HStack(spacing: 10) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .fill(app.accentColor.opacity(0.18))
                                    .frame(width: 34, height: 34)
                                Image(systemName: app.systemImage)
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(app.accentColor)
                            }

                            VStack(alignment: .leading, spacing: 1) {
                                Text(app.title)
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(DesignTokens.labelPrimary)
                                    .lineLimit(1)
                                Text(app.subtitle)
                                    .font(.system(size: 11))
                                    .foregroundStyle(DesignTokens.labelSecondary)
                                    .lineLimit(1)
                            }

                            Spacer(minLength: 0)
                        }
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    .hyGlassRounded(cornerRadius: 16, interactive: true)
                    .accessibilityLabel("Abrir \(app.title)")
                }
            }
        }
    }
}

import SwiftUI

/// Bottom compact glass action bar: Apps, Keystone, and Touchpad / D-Pad toggle.
struct RemoteBottomBar: View {
    let padMode: PadMode
    let height: CGFloat
    let onOpenApps: () -> Void
    let onOpenKeystone: () -> Void
    let onTogglePadMode: () -> Void

    var body: some View {
        HYGlassContainer(spacing: 10) {
            HStack(spacing: 10) {
                actionPill(
                    icon: "square.grid.2x2.fill",
                    title: "Apps",
                    accessibilityLabel: "Aplicativos rápidos",
                    action: onOpenApps
                )

                actionPill(
                    icon: "viewfinder",
                    title: "Keystone",
                    accessibilityLabel: "Keystone e resolução",
                    action: onOpenKeystone
                )

                actionPill(
                    icon: padMode.toggleIcon,
                    title: padMode.toggleLabel,
                    accessibilityLabel: padMode == .touchpad ? "Alternar para Clickpad" : "Alternar para Touchpad",
                    action: onTogglePadMode
                )
            }
        }
    }

    private func actionPill(
        icon: String,
        title: String,
        accessibilityLabel: String,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            HapticEngine.shared.selection()
            action()
        } label: {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .semibold))
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(DesignTokens.labelPrimary)
            .frame(maxWidth: .infinity)
            .frame(height: height)
        }
        .buttonStyle(.plain)
        .hyGlassCapsule(interactive: true)
        .accessibilityLabel(accessibilityLabel)
    }
}

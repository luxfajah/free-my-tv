import SwiftUI

/// Native Apple TV Remote Top Bar:
/// Left = Keyboard, Center = Device Picker Pill with live status dot, Right = Power.
struct RemoteTopBar: View {
    let client: ProjectorClient
    var hasUpdate: Bool = false
    let onToggleKeyboard: () -> Void
    let onOpenDevicePicker: () -> Void
    let onPowerTap: () -> Void
    let onPowerLongPress: () -> Void

    @State private var isPowerPressed = false
    @State private var powerLongPressTask: Task<Void, Never>?
    @State private var powerLongPressFired = false
    @State private var pulseStatus = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HYGlassContainer(spacing: 12) {
            HStack(spacing: 12) {
                // Left: Keyboard button (44x44)
                Button {
                    HapticEngine.shared.tapLight()
                    onToggleKeyboard()
                } label: {
                    Image(systemName: "keyboard")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(DesignTokens.labelPrimary)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .hyGlassCircle(interactive: true)
                .accessibilityLabel("Teclado")

                Spacer(minLength: 4)

                // Center: Device Picker Pill (Capsule, 40pt height)
                Button {
                    HapticEngine.shared.selection()
                    onOpenDevicePicker()
                } label: {
                    HStack(spacing: 7) {
                        statusDot
                        if client.connectionState.isConnected {
                            Image(systemName: client.activeTransport.systemImage)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(
                                    client.activeTransport == .bluetooth
                                        ? Color(red: 0.32, green: 0.64, blue: 1.0)
                                        : DesignTokens.labelSecondary
                                )
                        }
                        Text(client.deviceName)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(DesignTokens.labelPrimary)
                            .lineLimit(1)
                        if hasUpdate {
                            Circle()
                                .fill(Color(red: 0.28, green: 0.62, blue: 1.0))
                                .frame(width: 6, height: 6)
                                .shadow(color: Color.blue.opacity(0.8), radius: 3)
                        }
                        Image(systemName: "chevron.down")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(DesignTokens.labelSecondary)
                    }
                    .padding(.horizontal, 15)
                    .frame(height: 40)
                }
                .buttonStyle(.plain)
                .hyGlassCapsule(interactive: true)
                .accessibilityLabel("Projetor \(client.deviceName), \(statusAccessibilityText), via \(client.activeTransport.label)")
                .accessibilityHint("Toque para trocar de projetor ou alternar entre Wi-Fi e Bluetooth")

                Spacer(minLength: 4)

                // Right: Power button (44x44) — tap to wake/confirm, long-press for direct standby
                Image(systemName: "power")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(powerIconColor)
                    .frame(width: 44, height: 44)
                    .hyGlassCircle(interactive: true, tint: powerTint)
                    .scaleEffect(isPowerPressed && !reduceMotion ? 0.91 : 1.0)
                    .animation(.interactiveSpring(response: 0.18, dampingFraction: 0.72), value: isPowerPressed)
                    .contentShape(Circle())
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { _ in
                                if !isPowerPressed {
                                    isPowerPressed = true
                                    powerLongPressFired = false
                                    HapticEngine.shared.tapLight()
                                    powerLongPressTask?.cancel()
                                    powerLongPressTask = Task {
                                        try? await Task.sleep(nanoseconds: 480_000_000)
                                        guard !Task.isCancelled else { return }
                                        powerLongPressFired = true
                                        HapticEngine.shared.tapHeavy()
                                        onPowerLongPress()
                                    }
                                }
                            }
                            .onEnded { _ in
                                guard isPowerPressed else { return }
                                isPowerPressed = false
                                powerLongPressTask?.cancel()
                                powerLongPressTask = nil
                                if !powerLongPressFired {
                                    onPowerTap()
                                }
                                powerLongPressFired = false
                            }
                    )
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(client.isAwake ? "Energia, projetor ligado" : "Ligar projetor")
                    .accessibilityHint(client.isAwake ? "Toque para opções ou pressione para standby" : "Toque para acordar o projetor")
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction {
                        onPowerTap()
                    }
            }
        }
        .onAppear {
            if !reduceMotion {
                withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) {
                    pulseStatus = true
                }
            }
        }
    }

    private var statusColor: Color {
        if !client.connectionState.isConnected {
            return DesignTokens.statusOffline
        }
        return client.isAwake ? DesignTokens.statusAwake : DesignTokens.statusStandby
    }

    private var statusAccessibilityText: String {
        if !client.connectionState.isConnected {
            return "offline"
        }
        return client.isAwake ? "ligado" : "em standby"
    }

    private var powerIconColor: Color {
        if !client.connectionState.isConnected {
            return DesignTokens.labelSecondary
        }
        return client.isAwake ? DesignTokens.labelPrimary : DesignTokens.statusAwake
    }

    private var powerTint: Color? {
        guard client.connectionState.isConnected else { return nil }
        return client.isAwake ? nil : DesignTokens.statusAwake
    }

    private var statusDot: some View {
        ZStack {
            if !reduceMotion && client.connectionState.isConnected {
                Circle()
                    .fill(statusColor.opacity(0.35))
                    .frame(width: 13, height: 13)
                    .scaleEffect(pulseStatus ? 1.15 : 0.72)
                    .opacity(pulseStatus ? 0.25 : 0.75)
            }
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
                .shadow(color: statusColor.opacity(0.65), radius: 3)
        }
        .frame(width: 12, height: 12)
    }
}

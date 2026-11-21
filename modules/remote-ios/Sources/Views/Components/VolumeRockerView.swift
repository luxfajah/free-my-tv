import SwiftUI

/// Vertical Apple TV Volume Rocker Pill with true native `down`/`up` press-and-hold.
struct VolumeRockerView: View {
    let width: CGFloat
    let height: CGFloat
    let iconSize: CGFloat
    let onVolumeUpDown: () -> Void
    let onVolumeUpRelease: () -> Void
    let onVolumeDownDown: () -> Void
    let onVolumeDownRelease: () -> Void

    @State private var activeHalf: Half? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Half {
        case up
        case down
    }

    var body: some View {
        ZStack {
            Capsule(style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [DesignTokens.buttonTop, DesignTokens.buttonBottom],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .overlay(pressedHalfOverlay)
                .overlay(
                    Capsule(style: .continuous)
                        .strokeBorder(
                            LinearGradient(
                                colors: [Color.white.opacity(0.16), Color.white.opacity(0.04)],
                                startPoint: .top,
                                endPoint: .bottom
                            ),
                            lineWidth: 1
                        )
                )
                .shadow(color: .black.opacity(0.45), radius: 8, x: 0, y: 5)

            VStack(spacing: 0) {
                // Volume Up (+)
                Image(systemName: "plus")
                    .font(.system(size: iconSize, weight: .semibold))
                    .foregroundStyle(activeHalf == .up ? .white : DesignTokens.labelPrimary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { _ in
                                if activeHalf != .up {
                                    releaseCurrentIfNeeded()
                                    activeHalf = .up
                                    HapticEngine.shared.tapLight()
                                    onVolumeUpDown()
                                }
                            }
                            .onEnded { _ in
                                releaseCurrentIfNeeded()
                            }
                    )
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Aumentar volume")
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction {
                        HapticEngine.shared.tapLight()
                        onVolumeUpDown()
                        onVolumeUpRelease()
                    }

                // Subtle center hairline divider
                Rectangle()
                    .fill(Color.white.opacity(0.07))
                    .frame(width: width * 0.46, height: 1)

                // Volume Down (-)
                Image(systemName: "minus")
                    .font(.system(size: iconSize, weight: .semibold))
                    .foregroundStyle(activeHalf == .down ? .white : DesignTokens.labelPrimary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { _ in
                                if activeHalf != .down {
                                    releaseCurrentIfNeeded()
                                    activeHalf = .down
                                    HapticEngine.shared.tapLight()
                                    onVolumeDownDown()
                                }
                            }
                            .onEnded { _ in
                                releaseCurrentIfNeeded()
                            }
                    )
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Diminuir volume")
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction {
                        HapticEngine.shared.tapLight()
                        onVolumeDownDown()
                        onVolumeDownRelease()
                    }
            }
            .clipShape(Capsule(style: .continuous))
        }
        .frame(width: width, height: height)
        .scaleEffect(activeHalf != nil && !reduceMotion ? 0.975 : 1.0)
        .animation(.interactiveSpring(response: 0.16, dampingFraction: 0.75), value: activeHalf)
        .onDisappear {
            releaseCurrentIfNeeded()
        }
    }

    @ViewBuilder
    private var pressedHalfOverlay: some View {
        VStack(spacing: 0) {
            Color.white.opacity(activeHalf == .up ? 0.11 : 0)
            Color.white.opacity(activeHalf == .down ? 0.11 : 0)
        }
        .clipShape(Capsule(style: .continuous))
    }

    private func releaseCurrentIfNeeded() {
        guard let current = activeHalf else { return }
        activeHalf = nil
        switch current {
        case .up:
            onVolumeUpRelease()
        case .down:
            onVolumeDownRelease()
        }
    }
}

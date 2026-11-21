import SwiftUI

enum TactileButtonInteraction {
    case tap(() -> Void)
    case tapAndLongPress(onTap: () -> Void, onLongPress: () -> Void)
    case pressAndHold(onDown: () -> Void, onUp: () -> Void)
}

/// Tactile circular button modeled after Apple's native iOS Apple TV Remote.
struct TactileCircleButton: View {
    let systemImage: String
    let accessibilityLabel: String
    let diameter: CGFloat
    let iconSize: CGFloat
    var isActive: Bool = false
    var activeTint: Color = DesignTokens.statusOffline
    let interaction: TactileButtonInteraction

    @State private var isPressed: Bool = false
    @State private var longPressTask: Task<Void, Never>?
    @State private var longPressFired: Bool = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle()
                .fill(backgroundFill)
                .overlay(
                    Circle()
                        .strokeBorder(borderGradient, lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.45), radius: isPressed ? 2 : 8, x: 0, y: isPressed ? 1 : 5)

            Image(systemName: systemImage)
                .font(.system(size: iconSize, weight: .semibold))
                .foregroundStyle(iconForeground)
        }
        .frame(width: diameter, height: diameter)
        .scaleEffect(isPressed && !reduceMotion ? 0.93 : 1.0)
        .animation(.interactiveSpring(response: 0.18, dampingFraction: 0.72), value: isPressed)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !isPressed {
                        handleTouchDown()
                    }
                }
                .onEnded { _ in
                    handleTouchUp()
                }
        )
        .onDisappear {
            cancelIfNeeded()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction {
            triggerAccessibilityTap()
        }
    }

    private var backgroundFill: LinearGradient {
        if isActive {
            return LinearGradient(
                colors: [activeTint.opacity(0.85), activeTint.opacity(0.62)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        if isPressed {
            return LinearGradient(
                colors: [DesignTokens.buttonPressed, DesignTokens.buttonTop],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        return LinearGradient(
            colors: [DesignTokens.buttonTop, DesignTokens.buttonBottom],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    private var borderGradient: LinearGradient {
        LinearGradient(
            colors: [
                isActive ? Color.white.opacity(0.35) : Color.white.opacity(isPressed ? 0.24 : 0.14),
                Color.white.opacity(0.03)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    private var iconForeground: Color {
        isActive ? .white : DesignTokens.labelPrimary
    }

    private func handleTouchDown() {
        isPressed = true
        longPressFired = false

        switch interaction {
        case .tap:
            HapticEngine.shared.tapLight()

        case .tapAndLongPress(_, let onLongPress):
            HapticEngine.shared.tapLight()
            longPressTask?.cancel()
            longPressTask = Task {
                try? await Task.sleep(nanoseconds: 450_000_000)
                guard !Task.isCancelled else { return }
                longPressFired = true
                HapticEngine.shared.tapHeavy()
                onLongPress()
            }

        case .pressAndHold(let onDown, _):
            HapticEngine.shared.tapLight()
            onDown()
        }
    }

    private func handleTouchUp() {
        guard isPressed else { return }
        isPressed = false
        longPressTask?.cancel()
        longPressTask = nil

        switch interaction {
        case .tap(let onTap):
            onTap()

        case .tapAndLongPress(let onTap, _):
            if !longPressFired {
                onTap()
            }
            longPressFired = false

        case .pressAndHold(_, let onUp):
            onUp()
        }
    }

    private func cancelIfNeeded() {
        longPressTask?.cancel()
        longPressTask = nil
        if isPressed {
            isPressed = false
            if case .pressAndHold(_, let onUp) = interaction {
                onUp()
            }
        }
    }

    private func triggerAccessibilityTap() {
        HapticEngine.shared.tapLight()
        switch interaction {
        case .tap(let onTap):
            onTap()
        case .tapAndLongPress(let onTap, _):
            onTap()
        case .pressAndHold(let onDown, let onUp):
            onDown()
            onUp()
        }
    }
}

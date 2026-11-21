import SwiftUI

/// Authentic Apple TV Siri Remote circular Clickpad Ring + recessed center OK button.
/// Uses polar hit-testing and TRUE native `down`/`up` events so the projector's
/// SystemRemoteDaemon handles zero-jitter server-side Android hold-to-repeat.
struct ClickpadRingView: View {
    let client: ProjectorClient

    @State private var activeKey: RemoteKey? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geo in
            let outerDiameter = max(168, min(geo.size.width - 42, geo.size.height - 34))
            let centerDiameter = outerDiameter * 0.44
            let outerRadius = outerDiameter / 2.0
            let centerRadius = centerDiameter / 2.0

            ZStack {
                // Matching hardware backdrop frame
                RoundedRectangle(cornerRadius: DesignTokens.surfaceCornerRadius, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [DesignTokens.surfaceTop, DesignTokens.surfaceBottom],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: DesignTokens.surfaceCornerRadius, style: .continuous)
                            .strokeBorder(
                                LinearGradient(
                                    colors: [Color.white.opacity(0.15), Color.white.opacity(0.03)],
                                    startPoint: .top,
                                    endPoint: .bottom
                                ),
                                lineWidth: 1
                            )
                    )
                    .shadow(color: .black.opacity(0.55), radius: 18, x: 0, y: 10)

                // Circular Clickpad Ring
                ZStack {
                    // Outer Tactile Ring
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [DesignTokens.ringOuterTop, DesignTokens.ringOuterBottom],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        .overlay(directionalHighlight(outerDiameter: outerDiameter))
                        .overlay(
                            Circle()
                                .strokeBorder(
                                    LinearGradient(
                                        colors: [Color.white.opacity(0.18), Color.white.opacity(0.04)],
                                        startPoint: .top,
                                        endPoint: .bottom
                                    ),
                                    lineWidth: 1.2
                                )
                        )
                        .shadow(color: .black.opacity(0.55), radius: 14, x: 0, y: 8)
                        .frame(width: outerDiameter, height: outerDiameter)

                    // Cardinal direction indicators on ring
                    ringMarkers(outerDiameter: outerDiameter)
                        .allowsHitTesting(false)

                    // Recessed Center OK Button
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: activeKey == .ok
                                    ? [DesignTokens.buttonPressed, DesignTokens.buttonTop]
                                    : [DesignTokens.ringCenterTop, DesignTokens.ringCenterBottom],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        .overlay(
                            Circle()
                                .strokeBorder(
                                    LinearGradient(
                                        colors: [
                                            Color.white.opacity(activeKey == .ok ? 0.28 : 0.12),
                                            Color.white.opacity(0.02)
                                        ],
                                        startPoint: .top,
                                        endPoint: .bottom
                                    ),
                                    lineWidth: 1
                                )
                        )
                        .shadow(color: .black.opacity(0.65), radius: activeKey == .ok ? 2 : 6, x: 0, y: 3)
                        .frame(width: centerDiameter, height: centerDiameter)
                        .scaleEffect(activeKey == .ok && !reduceMotion ? 0.94 : 1.0)
                        .animation(.interactiveSpring(response: 0.16, dampingFraction: 0.72), value: activeKey)
                }
                .frame(width: outerDiameter, height: outerDiameter)
                .contentShape(Circle())
                .gesture(
                    DragGesture(minimumDistance: 0, coordinateSpace: .local)
                        .onChanged { value in
                            let center = CGPoint(x: outerRadius, y: outerRadius)
                            let hit = hitTest(
                                point: value.location,
                                center: center,
                                centerRadius: centerRadius,
                                outerRadius: outerRadius + 20
                            )
                            if activeKey == nil, let hit {
                                beginPress(hit)
                            }
                        }
                        .onEnded { _ in
                            endPress()
                        }
                )
                .onDisappear {
                    endPress()
                }

                // Floating transient HUD pill at top-center
                VStack {
                    if let hud = client.activeHUD {
                        SurfaceHUDPill(message: hud)
                            .padding(.top, 12)
                    }
                    Spacer()
                }
                .allowsHitTesting(false)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Clickpad Direcional")
        .accessibilityHint("Use o anel para cima, baixo, esquerda, direita e o centro para OK")
        .accessibilityAddTraits(.allowsDirectInteraction)
    }

    // MARK: - Polar Hit Testing

    private func hitTest(
        point: CGPoint,
        center: CGPoint,
        centerRadius: CGFloat,
        outerRadius: CGFloat
    ) -> RemoteKey? {
        let dx = point.x - center.x
        let dy = point.y - center.y
        let distance = hypot(dx, dy)

        if distance <= centerRadius {
            return .ok
        }
        guard distance <= outerRadius else { return nil }

        let angle = atan2(dy, dx)
        let quarterPi = Double.pi / 4.0

        if angle >= -quarterPi && angle < quarterPi {
            return .right
        } else if angle >= quarterPi && angle < 3.0 * quarterPi {
            return .down
        } else if angle >= -3.0 * quarterPi && angle < -quarterPi {
            return .up
        } else {
            return .left
        }
    }

    // MARK: - Native Down/Up Press Lifecycle

    private func beginPress(_ key: RemoteKey) {
        activeKey = key
        if key == .ok {
            HapticEngine.shared.tapMedium()
        } else {
            HapticEngine.shared.tapLight()
        }
        client.sendKey(key, action: .down)
    }

    private func endPress() {
        guard let key = activeKey else { return }
        activeKey = nil
        client.sendKey(key, action: .up)
    }

    // MARK: - Visual Ring Markers & Directional Glow

    private func ringMarkers(outerDiameter: CGFloat) -> some View {
        let offset = outerDiameter * 0.36
        return ZStack {
            markerIcon("chevron.up", key: .up)
                .offset(y: -offset)
            markerIcon("chevron.down", key: .down)
                .offset(y: offset)
            markerIcon("chevron.left", key: .left)
                .offset(x: -offset)
            markerIcon("chevron.right", key: .right)
                .offset(x: offset)
        }
    }

    private func markerIcon(_ symbol: String, key: RemoteKey) -> some View {
        let isActive = activeKey == key
        return Image(systemName: symbol)
            .font(.system(size: 13, weight: .bold))
            .foregroundStyle(isActive ? Color.white : Color.white.opacity(0.32))
            .scaleEffect(isActive && !reduceMotion ? 1.22 : 1.0)
            .shadow(color: isActive ? Color.white.opacity(0.55) : .clear, radius: 6)
            .animation(.easeOut(duration: 0.12), value: isActive)
    }

    @ViewBuilder
    private func directionalHighlight(outerDiameter: CGFloat) -> some View {
        let offset = outerDiameter * 0.34
        let highlightOffset: CGSize? = {
            switch activeKey {
            case .up: return CGSize(width: 0, height: -offset)
            case .down: return CGSize(width: 0, height: offset)
            case .left: return CGSize(width: -offset, height: 0)
            case .right: return CGSize(width: offset, height: 0)
            default: return nil
            }
        }()

        if let highlightOffset {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [Color.white.opacity(0.18), .clear],
                        center: .center,
                        startRadius: 2,
                        endRadius: outerDiameter * 0.32
                    )
                )
                .frame(width: outerDiameter * 0.65, height: outerDiameter * 0.65)
                .offset(highlightOffset)
                .clipShape(Circle())
                .allowsHitTesting(false)
        }
    }
}

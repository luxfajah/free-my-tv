import SwiftUI
import Observation

// MARK: - 1. Reactive State Model (@Observable — SwiftUI Skill §03)

@Observable
final class VolumeHUDModel {
    /// Normalized volume level (0.0 ... 1.0)
    var level: CGFloat = 0.65
    /// Mute toggle state
    var isMuted: Bool = false
    /// Whether the HUD is in expanded (interactive/initial press) or compact (slim edge pill) mode
    var isExpanded: Bool = true
    /// Elastic rubber-band overshoot when pressing Vol+ at 100% or Vol- at 0% (-1.0 ... 1.0)
    var overshoot: CGFloat = 0.0
    /// Visibility of the floating HUD overlay
    var isVisible: Bool = true
    /// Number of consecutive hardware button presses in the current session
    private var pressCount: Int = 0

    var effectiveLevel: CGFloat {
        isMuted ? 0.0 : min(max(level, 0.0), 1.0)
    }

    /// Dynamic SF Symbol matching Apple HIG Volume states
    var speakerSymbol: String {
        let current = effectiveLevel
        if current <= 0.001 {
            return "speaker.slash.fill"
        } else if current < 0.33 {
            return "speaker.wave.1.fill"
        } else if current < 0.66 {
            return "speaker.wave.2.fill"
        } else {
            return "speaker.wave.3.fill"
        }
    }

    func stepVolume(delta: CGFloat) {
        isVisible = true
        pressCount += 1

        if isMuted && delta > 0 {
            isMuted = false
        }

        let next = level + delta
        if next > 1.0 {
            level = 1.0
            triggerOvershoot(direction: 1.0)
        } else if next < 0.0 {
            level = 0.0
            triggerOvershoot(direction: -1.0)
        } else {
            level = next
            overshoot = 0.0
        }

        // Apple iOS behavior: first press shows expanded pill (52pt), subsequent rapid presses slim down (16pt)
        if pressCount > 2 {
            isExpanded = false
        }
    }

    private func triggerOvershoot(direction: CGFloat) {
        withAnimation(.spring(response: 0.22, dampingFraction: 0.55)) {
            overshoot = direction
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) {
            withAnimation(.spring(response: 0.32, dampingFraction: 0.75)) {
                self.overshoot = 0.0
            }
        }
    }
}

// MARK: - 2. Apple Liquid Glass Vertical Volume HUD (iOS 26 / iOS 27 Spec)

struct AppleLiquidGlassVolumeHUD: View {
    @Bindable var model: VolumeHUDModel
    @Namespace private var glassNS

    // Accessibility environment tokens (Apple HIG Foundations & iOS 27 Design Skill)
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    // iOS 27 Kit 27.0.2 Dimensions
    private let expandedWidth: CGFloat = 52
    private let compactWidth: CGFloat = 16
    private let pillHeight: CGFloat = 204

    var body: some View {
        let currentWidth = model.isExpanded ? expandedWidth : compactWidth
        let stretchY = 1.0 + abs(model.overshoot) * 0.07
        let squashX = 1.0 - abs(model.overshoot) * 0.05
        let anchor: UnitPoint = model.overshoot >= 0 ? .bottom : .top

        GlassEffectContainer(spacing: 16) {
            ZStack(alignment: .bottom) {
                // 1. Liquid Glass Track Backing + Inner Shadow Rim (iOS 27 Kit 27.0.2)
                Capsule(style: .continuous)
                    .fill(trackBackgroundStyle)

                // 2. Luminous Level Fill Bar (Bottom-to-Top)
                GeometryReader { geo in
                    let fillHeight = max(0, geo.size.height * model.effectiveLevel)
                    VStack(spacing: 0) {
                        Spacer(minLength: 0)
                        Rectangle()
                            .fill(levelFillGradient)
                            .frame(height: fillHeight)
                    }
                }
                .clipShape(Capsule(style: .continuous))

                // 3. Specular Rim Ring & Inner Lit Shadow (iOS 27 `--ios27-glass-rim` #A6A6A6 / #DBDBDB)
                Capsule(style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            stops: [
                                .init(color: .white.opacity(colorScheme == .dark ? 0.45 : 0.75), location: 0.0),
                                .init(color: .white.opacity(0.14), location: 0.45),
                                .init(color: .white.opacity(0.28), location: 1.0)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 0.8
                    )

                // 4. Adaptive SF Symbol Speaker Glyph (Shown when expanded)
                if model.isExpanded {
                    Image(systemName: model.speakerSymbol)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(glyphColor)
                        .contentTransition(.symbolEffect(.replace))
                        .symbolEffect(.bounce, value: model.speakerSymbol)
                        .padding(.bottom, 16)
                        .transition(.opacity.combined(with: .scale(scale: 0.75)))
                }
            }
            .frame(width: currentWidth, height: pillHeight)
            .clipShape(Capsule(style: .continuous))
            .modifier(LiquidGlassCapsuleModifier(reduceTransparency: reduceTransparency))
            .glassEffectID("appleVolumeHUD", in: glassNS)
            .scaleEffect(x: squashX, y: stretchY, anchor: anchor)
            .shadow(color: .black.opacity(colorScheme == .dark ? 0.45 : 0.25), radius: 24, x: 0, y: 8)
            .contentShape(Capsule(style: .continuous))
            // Direct Drag Gesture (Touch manipulation — Apple HIG Controls)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !model.isExpanded {
                            withAnimation(springAnimation) {
                                model.isExpanded = true
                            }
                        }
                        let progress = 1.0 - (value.location.y / pillHeight)
                        if progress > 1.0 {
                            model.level = 1.0
                            model.overshoot = min((progress - 1.0) * 2.5, 1.0)
                        } else if progress < 0.0 {
                            model.level = 0.0
                            model.overshoot = max(progress * 2.5, -1.0)
                        } else {
                            model.isMuted = false
                            model.level = progress
                            model.overshoot = 0.0
                        }
                    }
                    .onEnded { _ in
                        withAnimation(springAnimation) {
                            model.overshoot = 0.0
                        }
                    }
            )
            .animation(springAnimation, value: model.effectiveLevel)
            .animation(springAnimation, value: model.isExpanded)
            // Apple HIG Accessibility
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Volume")
            .accessibilityValue(model.isMuted ? "Silenciado" : "\(Int(round(model.effectiveLevel * 100))) por cento")
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: model.stepVolume(delta: 0.0625)
                case .decrement: model.stepVolume(delta: -0.0625)
                @unknown default: break
                }
            }
        }
    }

    // iOS 27 Snappy Spring (response: 0.3, damping: 0.8)
    private var springAnimation: Animation {
        reduceMotion
            ? .easeOut(duration: 0.15)
            : .spring(response: 0.30, dampingFraction: 0.80)
    }

    private var trackBackgroundStyle: AnyShapeStyle {
        if reduceTransparency {
            return AnyShapeStyle(Color(white: 0.15))
        }
        return AnyShapeStyle(
            LinearGradient(
                colors: [
                    Color.black.opacity(0.38),
                    Color(white: 0.12).opacity(0.52)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private var levelFillGradient: LinearGradient {
        LinearGradient(
            stops: [
                .init(color: .white.opacity(0.98), location: 0.0),
                .init(color: Color(red: 0.94, green: 0.96, blue: 1.0).opacity(0.92), location: 1.0)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    /// Dynamically flips glyph contrast when the white volume fill passes behind the bottom icon
    private var glyphColor: Color {
        model.effectiveLevel > 0.18
            ? Color(red: 0.10, green: 0.12, blue: 0.16)
            : Color.white.opacity(0.95)
    }
}

// MARK: - 3. Native iOS 26/27 `.glassEffect` Wrapper with Accessibility Fallback

private struct LiquidGlassCapsuleModifier: ViewModifier {
    let reduceTransparency: Bool

    func body(content: Content) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, macOS 26.0, tvOS 26.0, *) {
            content
                .glassEffect(
                    reduceTransparency ? .identity : .regular.interactive(),
                    in: Capsule(style: .continuous)
                )
        } else {
            content.background(.ultraThinMaterial, in: Capsule(style: .continuous))
        }
        #else
        content.background(.ultraThinMaterial, in: Capsule(style: .continuous))
        #endif
    }
}

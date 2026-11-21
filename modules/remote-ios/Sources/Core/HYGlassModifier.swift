import SwiftUI

/// Liquid Glass container that uses native iOS 26 `GlassEffectContainer` when compiled
/// with Swift 6.2+ on iOS 26+, and falls back cleanly on Xcode 16 / iOS 18.
struct HYGlassContainer<Content: View>: View {
    let spacing: CGFloat
    @ViewBuilder let content: () -> Content

    init(spacing: CGFloat = 12, @ViewBuilder content: @escaping () -> Content) {
        self.spacing = spacing
        self.content = content
    }

    var body: some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) {
                content()
            }
        } else {
            content()
        }
        #else
        content()
        #endif
    }
}

// MARK: - Accessibility-Aware Glass Surface Modifiers

private struct HYGlassCapsuleModifier: ViewModifier {
    let interactive: Bool
    let tint: Color?
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        if reduceTransparency {
            content
                .background(
                    Capsule(style: .continuous)
                        .fill(tint?.opacity(0.28) ?? DesignTokens.buttonTop)
                )
                .overlay(
                    Capsule(style: .continuous)
                        .strokeBorder(DesignTokens.specularRimBright, lineWidth: 1)
                )
        } else {
            #if compiler(>=6.2)
            if #available(iOS 26.0, *) {
                if let tint {
                    if interactive {
                        content.glassEffect(.regular.tint(tint).interactive(), in: .capsule)
                    } else {
                        content.glassEffect(.regular.tint(tint), in: .capsule)
                    }
                } else {
                    if interactive {
                        content.glassEffect(.regular.interactive(), in: .capsule)
                    } else {
                        content.glassEffect(.regular, in: .capsule)
                    }
                }
            } else {
                fallback(content)
            }
            #else
            fallback(content)
            #endif
        }
    }

    @ViewBuilder
    private func fallback(_ content: Content) -> some View {
        content
            .background {
                ZStack {
                    Capsule(style: .continuous)
                        .fill(.ultraThinMaterial)
                    Capsule(style: .continuous)
                        .fill(tint?.opacity(0.18) ?? Color.white.opacity(0.05))
                }
            }
            .overlay(
                Capsule(style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color.white.opacity(0.22), Color.white.opacity(0.06)],
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        lineWidth: 1
                    )
            )
    }
}

private struct HYGlassCircleModifier: ViewModifier {
    let interactive: Bool
    let tint: Color?
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        if reduceTransparency {
            content
                .background(
                    Circle()
                        .fill(tint?.opacity(0.28) ?? DesignTokens.buttonTop)
                )
                .overlay(
                    Circle()
                        .strokeBorder(DesignTokens.specularRimBright, lineWidth: 1)
                )
        } else {
            #if compiler(>=6.2)
            if #available(iOS 26.0, *) {
                if let tint {
                    if interactive {
                        content.glassEffect(.regular.tint(tint).interactive(), in: .circle)
                    } else {
                        content.glassEffect(.regular.tint(tint), in: .circle)
                    }
                } else {
                    if interactive {
                        content.glassEffect(.regular.interactive(), in: .circle)
                    } else {
                        content.glassEffect(.regular, in: .circle)
                    }
                }
            } else {
                fallback(content)
            }
            #else
            fallback(content)
            #endif
        }
    }

    @ViewBuilder
    private func fallback(_ content: Content) -> some View {
        content
            .background {
                ZStack {
                    Circle().fill(.ultraThinMaterial)
                    Circle().fill(tint?.opacity(0.18) ?? Color.white.opacity(0.05))
                }
            }
            .overlay(
                Circle()
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color.white.opacity(0.22), Color.white.opacity(0.06)],
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        lineWidth: 1
                    )
            )
    }
}

private struct HYGlassRoundedModifier: ViewModifier {
    let cornerRadius: CGFloat
    let interactive: Bool
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        if reduceTransparency {
            content
                .background(
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .fill(DesignTokens.surfaceTop)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .strokeBorder(DesignTokens.specularRimBright, lineWidth: 1)
                )
        } else {
            #if compiler(>=6.2)
            if #available(iOS 26.0, *) {
                if interactive {
                    content.glassEffect(.regular.interactive(), in: .rect(cornerRadius: cornerRadius))
                } else {
                    content.glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
                }
            } else {
                fallback(content)
            }
            #else
            fallback(content)
            #endif
        }
    }

    @ViewBuilder
    private func fallback(_ content: Content) -> some View {
        content
            .background {
                ZStack {
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .fill(.ultraThinMaterial)
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .fill(Color.white.opacity(0.04))
                }
            }
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color.white.opacity(0.18), Color.white.opacity(0.05)],
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        lineWidth: 1
                    )
            )
    }
}

// MARK: - View Extension Helpers

extension View {
    @ViewBuilder
    func hyBackgroundExtension() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.backgroundExtensionEffect()
        } else {
            self
        }
        #else
        self
        #endif
    }

    @ViewBuilder
    func hyGlassID(_ id: String, in ns: Namespace.ID) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.glassEffectID(id, in: ns)
        } else {
            self
        }
        #else
        self
        #endif
    }

    func hyGlassCapsule(interactive: Bool = true, tint: Color? = nil) -> some View {
        modifier(HYGlassCapsuleModifier(interactive: interactive, tint: tint))
    }

    func hyGlassCircle(interactive: Bool = true, tint: Color? = nil) -> some View {
        modifier(HYGlassCircleModifier(interactive: interactive, tint: tint))
    }

    func hyGlassRounded(cornerRadius: CGFloat, interactive: Bool = false) -> some View {
        modifier(HYGlassRoundedModifier(cornerRadius: cornerRadius, interactive: interactive))
    }

    @ViewBuilder
    func hyGlassButton() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glass)
        } else {
            self.buttonStyle(.bordered)
                .clipShape(Capsule())
        }
        #else
        self.buttonStyle(.bordered)
            .clipShape(Capsule())
        #endif
    }

    @ViewBuilder
    func hyGlassProminentButton() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glassProminent)
        } else {
            self.buttonStyle(.borderedProminent)
                .clipShape(Capsule())
        }
        #else
        self.buttonStyle(.borderedProminent)
            .clipShape(Capsule())
        #endif
    }
}

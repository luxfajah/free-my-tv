import SwiftUI

/// Authentic 3-row asymmetric Apple TV Native Button Deck:
/// - Row 1: Back | TV/Home (tap = home, long-press = apps) | Menu
/// - Rows 2 & 3:
///   - Col 1: Play/Pause + Mute (highlights when muted)
///   - Col 2: -10s (rewind down/up hold) + +10s (forward down/up hold)
///   - Col 3: Tall vertical Volume Rocker Pill (`+` / `-` with native down/up hold)
struct RemoteButtonDeck: View {
    let client: ProjectorClient
    let metrics: RemoteLayoutMetrics

    var body: some View {
        VStack(spacing: metrics.deckRowGap) {
            // Row 1: Back | TV/Home | Menu
            HStack(spacing: metrics.deckColumnGap) {
                TactileCircleButton(
                    systemImage: "chevron.backward",
                    accessibilityLabel: "Voltar",
                    diameter: metrics.buttonDiameter,
                    iconSize: metrics.iconSize,
                    interaction: .tap {
                        client.sendKey(.back, action: .tap)
                    }
                )

                TactileCircleButton(
                    systemImage: "tv",
                    accessibilityLabel: "Início ou Aplicativos",
                    diameter: metrics.buttonDiameter,
                    iconSize: metrics.iconSize,
                    interaction: .tapAndLongPress(
                        onTap: {
                            client.sendKey(.home, action: .tap)
                        },
                        onLongPress: {
                            client.sendKey(.apps, action: .tap)
                            client.showHUD(icon: "square.grid.2x2.fill", title: "Aplicativos")
                        }
                    )
                )

                TactileCircleButton(
                    systemImage: "ellipsis",
                    accessibilityLabel: "Menu",
                    diameter: metrics.buttonDiameter,
                    iconSize: metrics.iconSize,
                    interaction: .tap {
                        client.sendKey(.menu, action: .tap)
                    }
                )
            }

            // Rows 2 & 3: Asymmetric Apple TV Media + Volume Rocker Grid
            HStack(alignment: .center, spacing: metrics.deckColumnGap) {
                // Column 1: Play/Pause + Mute
                VStack(spacing: metrics.deckRowGap) {
                    TactileCircleButton(
                        systemImage: "playpause.fill",
                        accessibilityLabel: "Reproduzir ou Pausar",
                        diameter: metrics.buttonDiameter,
                        iconSize: metrics.iconSize - 2,
                        interaction: .tap {
                            client.sendKey(.playPause, action: .tap)
                        }
                    )

                    TactileCircleButton(
                        systemImage: "speaker.slash.fill",
                        accessibilityLabel: client.isMuted ? "Ativar som" : "Silenciar",
                        diameter: metrics.buttonDiameter,
                        iconSize: metrics.iconSize - 2,
                        isActive: client.isMuted,
                        activeTint: DesignTokens.statusOffline,
                        interaction: .tap {
                            client.sendKey(.mute, action: .tap)
                        }
                    )
                }

                // Column 2: -10s (Rewind hold) + +10s (Forward hold)
                VStack(spacing: metrics.deckRowGap) {
                    TactileCircleButton(
                        systemImage: "gobackward.10",
                        accessibilityLabel: "Retroceder 10 segundos",
                        diameter: metrics.buttonDiameter,
                        iconSize: metrics.iconSize - 1,
                        interaction: .pressAndHold(
                            onDown: { client.sendKey(.rewind, action: .down) },
                            onUp: { client.sendKey(.rewind, action: .up) }
                        )
                    )

                    TactileCircleButton(
                        systemImage: "goforward.10",
                        accessibilityLabel: "Avançar 10 segundos",
                        diameter: metrics.buttonDiameter,
                        iconSize: metrics.iconSize - 1,
                        interaction: .pressAndHold(
                            onDown: { client.sendKey(.forward, action: .down) },
                            onUp: { client.sendKey(.forward, action: .up) }
                        )
                    )
                }

                // Column 3: Tall Vertical Volume Rocker Pill
                VolumeRockerView(
                    width: metrics.buttonDiameter,
                    height: metrics.volumeRockerHeight,
                    iconSize: metrics.iconSize - 1,
                    onVolumeUpDown: {
                        client.sendKey(.volUp, action: .down)
                    },
                    onVolumeUpRelease: {
                        client.sendKey(.volUp, action: .up)
                    },
                    onVolumeDownDown: {
                        client.sendKey(.volDown, action: .down)
                    },
                    onVolumeDownRelease: {
                        client.sendKey(.volDown, action: .up)
                    }
                )
            }
        }
    }
}

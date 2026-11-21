import SwiftUI

/// Keystone, Resolution, and Direct Volume precision sheet.
struct KeystoneSheet: View {
    let client: ProjectorClient
    @Environment(\.dismiss) private var dismiss
    @State private var sliderVolume: Double = 10

    var body: some View {
        NavigationStack {
            ZStack {
                DesignTokens.obsidianBG.ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        // Resolution Presets
                        VStack(alignment: .leading, spacing: 10) {
                            Text("RESOLUÇÃO DE SAÍDA")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(DesignTokens.labelTertiary)

                            VStack(spacing: 8) {
                                ForEach(ResolutionPreset.allCases) { preset in
                                    let isActive = client.resolution.contains(preset.matchesResolutionString)
                                    Button {
                                        HapticEngine.shared.tapMedium()
                                        client.setResolution(preset)
                                    } label: {
                                        HStack {
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(preset.title)
                                                    .font(.system(size: 15, weight: .semibold))
                                                    .foregroundStyle(DesignTokens.labelPrimary)
                                                Text(preset.dimensions)
                                                    .font(.system(size: 12).monospacedDigit())
                                                    .foregroundStyle(DesignTokens.labelSecondary)
                                            }
                                            Spacer()
                                            if isActive {
                                                Image(systemName: "checkmark.circle.fill")
                                                    .foregroundStyle(DesignTokens.statusAwake)
                                            }
                                        }
                                        .padding(14)
                                    }
                                    .buttonStyle(.plain)
                                    .hyGlassRounded(cornerRadius: 18, interactive: true)
                                }
                            }
                        }

                        // Keystone Actions
                        VStack(alignment: .leading, spacing: 10) {
                            Text("GEOMETRIA KEYSTONE")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(DesignTokens.labelTertiary)

                            HStack(spacing: 10) {
                                Button {
                                    HapticEngine.shared.tapHeavy()
                                    client.resetKeystone()
                                } label: {
                                    Label("Resetar 100%", systemImage: "arrow.counterclockwise")
                                        .font(.system(size: 14, weight: .semibold))
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 12)
                                }
                                .hyGlassProminentButton()
                                .tint(.orange)

                                Button {
                                    HapticEngine.shared.tapMedium()
                                    client.launchApp(.keystone)
                                    dismiss()
                                } label: {
                                    Label("4 Pontos", systemImage: "viewfinder")
                                        .font(.system(size: 14, weight: .semibold))
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 12)
                                }
                                .hyGlassButton()
                            }
                        }

                        // Direct Volume Precision Slider
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Text("VOLUME DIRETO")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(DesignTokens.labelTertiary)
                                Spacer()
                                Text("\(Int(sliderVolume.rounded())) / \(client.maxVolume)")
                                    .font(.system(size: 13, weight: .semibold).monospacedDigit())
                                    .foregroundStyle(DesignTokens.labelSecondary)
                            }

                            HStack(spacing: 12) {
                                Image(systemName: "speaker.fill")
                                    .font(.system(size: 13))
                                    .foregroundStyle(DesignTokens.labelSecondary)

                                Slider(
                                    value: $sliderVolume,
                                    in: 0...Double(max(1, client.maxVolume)),
                                    step: 1
                                ) { editing in
                                    if !editing {
                                        HapticEngine.shared.selection()
                                        client.setVolume(Int(sliderVolume.rounded()))
                                    }
                                }
                                .tint(.white)

                                Image(systemName: "speaker.wave.3.fill")
                                    .font(.system(size: 13))
                                    .foregroundStyle(DesignTokens.labelSecondary)
                            }
                            .padding(14)
                            .hyGlassRounded(cornerRadius: 18, interactive: false)
                        }
                    }
                    .padding(18)
                }
            }
            .navigationTitle("Imagem & Keystone")
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
            sliderVolume = Double(client.volume)
        }
        .onChange(of: client.volume) { _, newVol in
            sliderVolume = Double(newVol)
        }
    }
}

import SwiftUI

/// Floating live-streaming keyboard overlay: streams typed characters,
/// Backspace, and Enter directly to the projector over persistent TCP NDJSON.
struct KeyboardOverlayView: View {
    let client: ProjectorClient
    let onDismiss: () -> Void

    @State private var text: String = ""
    @State private var suppressStreaming: Bool = false
    @FocusState private var isFocused: Bool

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.opacity(0.62)
                .ignoresSafeArea()
                .onTapGesture {
                    dismiss()
                }

            VStack(spacing: 14) {
                // Header
                HStack {
                    Label("Teclado ao Vivo", systemImage: "keyboard")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(DesignTokens.labelPrimary)

                    Spacer()

                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(DesignTokens.labelSecondary)
                            .frame(width: 30, height: 30)
                    }
                    .buttonStyle(.plain)
                    .hyGlassCircle(interactive: true)
                    .accessibilityLabel("Fechar teclado")
                }

                // Live Text Field
                HStack(spacing: 10) {
                    TextField("Digite para enviar ao projetor…", text: $text)
                        .focused($isFocused)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                        .submitLabel(.send)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(DesignTokens.labelPrimary)
                        .tint(.white)
                        .onSubmit {
                            HapticEngine.shared.tapMedium()
                            client.sendKey(.enter, action: .tap)
                        }
                        .onChange(of: text) { oldValue, newValue in
                            guard !suppressStreaming else { return }
                            streamDelta(from: oldValue, to: newValue)
                        }

                    if !text.isEmpty {
                        Button {
                            suppressStreaming = true
                            text = ""
                            suppressStreaming = false
                            HapticEngine.shared.tapLight()
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(DesignTokens.labelTertiary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Limpar campo local")
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(DesignTokens.obsidianBG.opacity(0.85))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.14), lineWidth: 1)
                        )
                )

                // Quick Remote Keyboard Actions
                HYGlassContainer(spacing: 8) {
                    HStack(spacing: 8) {
                        quickKeyButton(icon: "magnifyingglass", title: "Buscar") {
                            HapticEngine.shared.tapLight()
                            client.sendKey(.search, action: .tap)
                        }

                        quickKeyButton(icon: "delete.left", title: "Apagar") {
                            HapticEngine.shared.tapLight()
                            client.sendKey(.backspace, action: .tap)
                        }

                        quickKeyButton(icon: "link", title: "Abrir Link") {
                            let candidate = text.trimmingCharacters(in: .whitespacesAndNewlines)
                            let target = candidate.isEmpty ? (UIPasteboard.general.string ?? "") : candidate
                            if !target.isEmpty {
                                HapticEngine.shared.tapMedium()
                                client.openURL(target)
                                dismiss()
                            }
                        }

                        quickKeyButton(icon: "return", title: "Enter") {
                            HapticEngine.shared.tapMedium()
                            client.sendKey(.enter, action: .tap)
                        }
                    }
                }
            }
            .padding(18)
            .hyGlassRounded(cornerRadius: 28, interactive: false)
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .shadow(color: .black.opacity(0.6), radius: 24, x: 0, y: 12)
        }
        .onAppear {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 80_000_000)
                isFocused = true
            }
        }
    }

    private func quickKeyButton(icon: String, title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .semibold))
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(DesignTokens.labelPrimary)
            .frame(maxWidth: .infinity)
            .frame(height: 36)
        }
        .buttonStyle(.plain)
        .hyGlassCapsule(interactive: true)
    }

    private func streamDelta(from oldValue: String, to newValue: String) {
        if newValue.hasPrefix(oldValue) {
            let added = String(newValue.dropFirst(oldValue.count))
            if !added.isEmpty {
                client.sendText(added)
            }
        } else if oldValue.hasPrefix(newValue) {
            let removedCount = oldValue.count - newValue.count
            for _ in 0..<min(removedCount, 32) {
                client.sendKey(.backspace, action: .tap)
            }
        } else {
            // Find common prefix for mid-string edits or paste
            let commonPrefixLen = zip(oldValue, newValue).prefix { $0 == $1 }.count
            let deletions = oldValue.count - commonPrefixLen
            for _ in 0..<min(deletions, 32) {
                client.sendKey(.backspace, action: .tap)
            }
            let added = String(newValue.dropFirst(commonPrefixLen))
            if !added.isEmpty {
                client.sendText(added)
            }
        }
    }

    private func dismiss() {
        isFocused = false
        onDismiss()
    }
}

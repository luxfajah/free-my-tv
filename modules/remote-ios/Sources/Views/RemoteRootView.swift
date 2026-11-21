import SwiftUI

/// Zero-scroll single-screen Apple TV Native Remote root layout.
/// Scales cleanly from iPhone SE to Pro Max inside `GeometryReader`.
struct RemoteRootView: View {
    let client: ProjectorClient
    let discovery: BonjourDiscoveryService
    let updateService: GitHubUpdateService

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var padMode: PadMode = RemoteStorage.savedPadMode()
    @State private var showKeyboard: Bool = false
    @State private var showDevicePicker: Bool = false
    @State private var showQuickApps: Bool = false
    @State private var showKeystoneSheet: Bool = false
    @State private var showStandbyConfirmation: Bool = false

    var body: some View {
        GeometryReader { geo in
            let metrics = RemoteLayoutMetrics(containerSize: geo.size)

            ZStack {
                // Deep OLED obsidian canvas + subtle top radial ambient light
                obsidianBackdrop

                // Zero-scroll vertical remote chassis
                VStack(spacing: metrics.sectionSpacing) {
                    RemoteTopBar(
                        client: client,
                        hasUpdate: updateService.hasUpdate,
                        onToggleKeyboard: {
                            withAnimation(.snappy(duration: 0.24)) {
                                showKeyboard.toggle()
                            }
                        },
                        onOpenDevicePicker: {
                            showDevicePicker = true
                        },
                        onPowerTap: {
                            handlePowerTap()
                        },
                        onPowerLongPress: {
                            client.sendPower(.standby)
                        }
                    )

                    // Centerpiece: Dual-Mode Touchpad / Clickpad Ring
                    ZStack {
                        if padMode == .touchpad {
                            TouchSurfaceView(client: client)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.97)))
                        } else {
                            ClickpadRingView(client: client)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.97)))
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .animation(.snappy(duration: 0.26), value: padMode)

                    // Authentic Apple TV Native Button Deck
                    RemoteButtonDeck(client: client, metrics: metrics)

                    // Compact Glass Action Bar
                    RemoteBottomBar(
                        padMode: padMode,
                        height: metrics.bottomBarHeight,
                        onOpenApps: { showQuickApps = true },
                        onOpenKeystone: { showKeystoneSheet = true },
                        onTogglePadMode: { togglePadMode() }
                    )
                }
                .padding(.horizontal, metrics.horizontalPadding)
                .padding(.top, 10)
                .padding(.bottom, 12)
                .frame(maxWidth: 430)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                // Live Streaming Keyboard Overlay
                if showKeyboard {
                    KeyboardOverlayView(client: client) {
                        withAnimation(.easeOut(duration: 0.2)) {
                            showKeyboard = false
                        }
                    }
                    .transition(.opacity)
                    .zIndex(10)
                }
            }
        }
        .sheet(isPresented: $showDevicePicker) {
            DevicePickerSheet(
                client: client,
                discovery: discovery,
                updateService: updateService
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showQuickApps) {
            QuickAppsSheet(client: client)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showKeystoneSheet) {
            KeystoneSheet(client: client)
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
        }
        .confirmationDialog(
            "Colocar \(client.deviceName) em Standby?",
            isPresented: $showStandbyConfirmation,
            titleVisibility: .visible
        ) {
            Button("Modo Standby", role: .destructive) {
                HapticEngine.shared.tapHeavy()
                client.sendPower(.standby)
            }
            Button("Alternar Energia") {
                HapticEngine.shared.tapMedium()
                client.sendPower(.toggle)
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("O projetor desligará a lâmpada mantendo o Wi-Fi ativo para religar pelo app ou Siri.")
        }
        .onChange(of: scenePhase) { _, newPhase in
            client.handleScenePhase(newPhase)
        }
        .onChange(of: discovery.discovered) { _, found in
            // Auto-adopt discovered Wi-Fi projector if currently disconnected on Wi-Fi
            if client.activeTransport == .wifi,
               !client.connectionState.isConnected,
               let first = found.first,
               first.host != client.host {
                client.connect(to: first.host, tcpPort: first.tcpPort, httpPort: first.httpPort)
            }
        }
        .task {
            HapticEngine.shared.prepare()
            client.connectDefault()
            discovery.startScan()
            await updateService.checkForUpdates()
        }
    }

    // MARK: - Backdrop

    private var obsidianBackdrop: some View {
        ZStack {
            DesignTokens.obsidianBG
            RadialGradient(
                colors: [DesignTokens.obsidianAmbient, DesignTokens.obsidianBG.opacity(0.0)],
                center: .top,
                startRadius: 20,
                endRadius: 420
            )
        }
        .ignoresSafeArea()
        .hyBackgroundExtension()
    }

    // MARK: - Actions

    private func handlePowerTap() {
        if !client.isAwake || !client.connectionState.isConnected {
            HapticEngine.shared.tapMedium()
            client.sendPower(.wake)
        } else {
            showStandbyConfirmation = true
        }
    }

    private func togglePadMode() {
        padMode = (padMode == .touchpad) ? .clickpad : .touchpad
        RemoteStorage.savePadMode(padMode)
        client.showHUD(
            icon: padMode == .touchpad ? "hand.draw.fill" : "dpad.fill",
            title: padMode.title
        )
    }
}

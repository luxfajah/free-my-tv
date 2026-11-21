import SwiftUI

/// Floating transient HUD pill rendered at top-center of the touchpad / clickpad surface.
struct SurfaceHUDPill: View {
    let message: HUDMessage

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: message.icon)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(DesignTokens.labelPrimary)

            Text(message.title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(DesignTokens.labelPrimary)
                .lineLimit(1)

            if let progress = message.volumeProgress {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color.white.opacity(0.18))
                        Capsule()
                            .fill(Color.white.opacity(0.92))
                            .frame(width: max(4, geo.size.width * min(1, max(0, progress))))
                    }
                }
                .frame(width: 56, height: 5)
            }

            if let subtitle = message.subtitle {
                Text(subtitle)
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(DesignTokens.labelSecondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .hyGlassCapsule(interactive: false)
        .shadow(color: .black.opacity(0.35), radius: 10, x: 0, y: 4)
        .transition(.opacity.combined(with: .scale(scale: 0.92)))
    }
}

/// Native Apple TV Remote Touchpad Surface with continuous swipe stepping,
/// flick momentum, edge-hold native auto-repeat, tap-OK, and long-press-OK.
struct TouchSurfaceView: View {
    let client: ProjectorClient

    // Touch & gesture state
    @State private var touchLocation: CGPoint? = nil
    @State private var dragStartLocation: CGPoint? = nil
    @State private var lastStepLocation: CGPoint? = nil
    @State private var dragStartTime: Date? = nil
    @State private var hasSwiped: Bool = false
    @State private var lastDirection: RemoteKey? = nil

    // Long-press & edge-hold state
    @State private var longPressTask: Task<Void, Never>?
    @State private var longPressFired: Bool = false
    @State private var edgeHoldTask: Task<Void, Never>?
    @State private var pendingEdgeDirection: RemoteKey? = nil
    @State private var activeEdgeKey: RemoteKey? = nil

    // Momentum tasks (cancelled immediately on next touch-down)
    @State private var momentumTasks: [Task<Void, Never>] = []

    // Visual feedback state
    @State private var flashedDirection: RemoteKey? = nil
    @State private var flashClearTask: Task<Void, Never>?
    @State private var rippleLocation: CGPoint? = nil
    @State private var rippleScale: CGFloat = 0.2
    @State private var rippleOpacity: Double = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    // Gesture tuning constants
    private let tapMaxDistance: CGFloat = 10
    private let stepThreshold: CGFloat = 36
    private let axisLockRatio: CGFloat = 1.25
    private let edgeZoneInset: CGFloat = 34

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // Matte graphite hardware surface
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
                                    colors: [
                                        Color.white.opacity(0.16),
                                        Color.white.opacity(0.05),
                                        Color.white.opacity(0.02)
                                    ],
                                    startPoint: .top,
                                    endPoint: .bottom
                                ),
                                lineWidth: 1
                            )
                    )
                    .shadow(color: .black.opacity(0.55), radius: 18, x: 0, y: 10)

                // Soft radial finger-tracking glow
                if !reduceMotion, let loc = touchLocation {
                    RadialGradient(
                        colors: [Color.white.opacity(0.14), Color.white.opacity(0.03), .clear],
                        center: .center,
                        startRadius: 2,
                        endRadius: 84
                    )
                    .frame(width: 168, height: 168)
                    .position(loc)
                    .allowsHitTesting(false)
                }

                // Center/tap ripple on OK
                if !reduceMotion, let rLoc = rippleLocation {
                    Circle()
                        .stroke(Color.white.opacity(rippleOpacity), lineWidth: 2)
                        .frame(width: 72, height: 72)
                        .scaleEffect(rippleScale)
                        .position(rLoc)
                        .allowsHitTesting(false)
                }

                // Micro-etched cardinal chevrons on N/S/E/W
                cardinalChevrons
                    .allowsHitTesting(false)

                // Floating transient HUD pill at top-center
                VStack {
                    if let hud = client.activeHUD {
                        SurfaceHUDPill(message: hud)
                            .padding(.top, 14)
                    }
                    Spacer()
                }
                .allowsHitTesting(false)
            }
            .clipShape(RoundedRectangle(cornerRadius: DesignTokens.surfaceCornerRadius, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: DesignTokens.surfaceCornerRadius, style: .continuous))
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .local)
                    .onChanged { value in
                        handleDragChanged(value, in: geo.size)
                    }
                    .onEnded { value in
                        handleDragEnded(value, in: geo.size)
                    }
            )
            .onDisappear {
                cleanupAllActiveGestures()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Superfície Touchpad")
        .accessibilityHint("Deslize para navegar, toque para selecionar OK")
        .accessibilityAddTraits(.allowsDirectInteraction)
    }

    // MARK: - Cardinal Chevrons

    private var cardinalChevrons: some View {
        ZStack {
            VStack {
                chevronIcon("chevron.up", for: .up)
                    .padding(.top, 18)
                Spacer()
                chevronIcon("chevron.down", for: .down)
                    .padding(.bottom, 18)
            }
            HStack {
                chevronIcon("chevron.left", for: .left)
                    .padding(.leading, 18)
                Spacer()
                chevronIcon("chevron.right", for: .right)
                    .padding(.trailing, 18)
            }
        }
    }

    private func chevronIcon(_ symbol: String, for key: RemoteKey) -> some View {
        let isHighlighted = flashedDirection == key || activeEdgeKey == key
        return Image(systemName: symbol)
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(isHighlighted ? Color.white.opacity(0.95) : Color.white.opacity(0.16))
            .scaleEffect(isHighlighted && !reduceMotion ? 1.25 : 1.0)
            .shadow(color: isHighlighted ? Color.white.opacity(0.55) : .clear, radius: 6)
            .animation(.easeOut(duration: 0.14), value: isHighlighted)
    }

    // MARK: - Gesture Engine

    private func handleDragChanged(_ value: DragGesture.Value, in size: CGSize) {
        let current = value.location
        touchLocation = current

        // Touch-down initialization
        if dragStartLocation == nil {
            cancelMomentumTasks()
            dragStartLocation = value.startLocation
            lastStepLocation = value.startLocation
            dragStartTime = Date()
            hasSwiped = false
            longPressFired = false
            lastDirection = nil
            startLongPressTimer()
        }

        guard let start = dragStartLocation, let stepOrigin = lastStepLocation else { return }

        let totalDX = current.x - start.x
        let totalDY = current.y - start.y
        let totalDist = hypot(totalDX, totalDY)

        if totalDist >= tapMaxDistance {
            longPressTask?.cancel()
            longPressTask = nil
        }

        // Continuous swipe stepping with 1.25x axis lock
        let stepDX = current.x - stepOrigin.x
        let stepDY = current.y - stepOrigin.y

        if abs(stepDX) >= stepThreshold && abs(stepDX) >= abs(stepDY) * axisLockRatio {
            let dir: RemoteKey = stepDX > 0 ? .right : .left
            triggerDirectionalStep(dir)
            lastStepLocation = current
            hasSwiped = true
            lastDirection = dir
            cancelEdgeHold()
        } else if abs(stepDY) >= stepThreshold && abs(stepDY) >= abs(stepDX) * axisLockRatio {
            let dir: RemoteKey = stepDY > 0 ? .down : .up
            triggerDirectionalStep(dir)
            lastStepLocation = current
            hasSwiped = true
            lastDirection = dir
            cancelEdgeHold()
        }

        // Edge-hold native auto-repeat
        updateEdgeHold(at: current, in: size)
    }

    private func handleDragEnded(_ value: DragGesture.Value, in size: CGSize) {
        let start = dragStartLocation ?? value.startLocation
        let totalDist = hypot(value.location.x - start.x, value.location.y - start.y)
        let elapsed = Date().timeIntervalSince(dragStartTime ?? Date())

        longPressTask?.cancel()
        longPressTask = nil

        // 1. Release active edge-hold
        if let edgeKey = activeEdgeKey {
            client.sendKey(edgeKey, action: .up)
            activeEdgeKey = nil
            cancelEdgeHold()
        } else if longPressFired {
            // 2. Release long-press OK
            client.sendKey(.ok, action: .up)
            longPressFired = false
        } else if !hasSwiped && totalDist < tapMaxDistance {
            // 3. Clean tap -> OK
            HapticEngine.shared.tapMedium()
            client.sendKey(.ok, action: .tap)
            triggerRipple(at: value.location)
        } else if hasSwiped, let dir = lastDirection {
            // 4. Fast flick momentum (1-2 extra steps)
            let predictedDX = value.predictedEndTranslation.width - value.translation.width
            let predictedDY = value.predictedEndTranslation.height - value.translation.height
            let projectedSpeed = hypot(predictedDX, predictedDY)
            let directVelocity = elapsed > 0.01 ? (totalDist / elapsed) : 0

            if projectedSpeed > 260 || directVelocity > 1100 {
                scheduleMomentum(direction: dir, steps: 2)
            } else if projectedSpeed > 130 || directVelocity > 650 {
                scheduleMomentum(direction: dir, steps: 1)
            }
        }

        // Reset transient drag state
        touchLocation = nil
        dragStartLocation = nil
        lastStepLocation = nil
        dragStartTime = nil
        hasSwiped = false
        lastDirection = nil
        cancelEdgeHold()
    }

    // MARK: - Helpers

    private func triggerDirectionalStep(_ direction: RemoteKey) {
        HapticEngine.shared.tapLight()
        client.sendKey(direction, action: .tap)
        flashChevron(direction)
    }

    private func flashChevron(_ direction: RemoteKey) {
        flashClearTask?.cancel()
        flashedDirection = direction
        flashClearTask = Task {
            try? await Task.sleep(nanoseconds: 190_000_000)
            guard !Task.isCancelled else { return }
            flashedDirection = nil
        }
    }

    private func startLongPressTimer() {
        longPressTask?.cancel()
        longPressTask = Task {
            try? await Task.sleep(nanoseconds: 450_000_000)
            guard !Task.isCancelled, !hasSwiped, touchLocation != nil else { return }
            longPressFired = true
            HapticEngine.shared.tapHeavy()
            client.sendKey(.ok, action: .down)
        }
    }

    private func updateEdgeHold(at point: CGPoint, in size: CGSize) {
        guard hasSwiped else { return }

        let detectedEdge: RemoteKey?
        if point.y <= edgeZoneInset {
            detectedEdge = .up
        } else if point.y >= size.height - edgeZoneInset {
            detectedEdge = .down
        } else if point.x <= edgeZoneInset {
            detectedEdge = .left
        } else if point.x >= size.width - edgeZoneInset {
            detectedEdge = .right
        } else {
            detectedEdge = nil
        }

        if let currentActive = activeEdgeKey {
            if detectedEdge != currentActive {
                client.sendKey(currentActive, action: .up)
                activeEdgeKey = nil
            } else {
                return
            }
        }

        if detectedEdge == pendingEdgeDirection { return }

        edgeHoldTask?.cancel()
        edgeHoldTask = nil
        pendingEdgeDirection = detectedEdge

        guard let targetDir = detectedEdge else { return }

        edgeHoldTask = Task {
            try? await Task.sleep(nanoseconds: 320_000_000)
            guard !Task.isCancelled, touchLocation != nil else { return }
            activeEdgeKey = targetDir
            HapticEngine.shared.tapLight()
            client.sendKey(targetDir, action: .down)
        }
    }

    private func cancelEdgeHold() {
        edgeHoldTask?.cancel()
        edgeHoldTask = nil
        pendingEdgeDirection = nil
        if let active = activeEdgeKey {
            client.sendKey(active, action: .up)
            activeEdgeKey = nil
        }
    }

    private func scheduleMomentum(direction: RemoteKey, steps: Int) {
        cancelMomentumTasks()
        let delaysNs: [UInt64] = steps >= 2 ? [65_000_000, 145_000_000] : [65_000_000]
        for delay in delaysNs {
            let task = Task {
                try? await Task.sleep(nanoseconds: delay)
                guard !Task.isCancelled else { return }
                triggerDirectionalStep(direction)
            }
            momentumTasks.append(task)
        }
    }

    private func cancelMomentumTasks() {
        for task in momentumTasks {
            task.cancel()
        }
        momentumTasks.removeAll()
    }

    private func triggerRipple(at point: CGPoint) {
        guard !reduceMotion else { return }
        rippleLocation = point
        rippleScale = 0.25
        rippleOpacity = 0.55
        withAnimation(.easeOut(duration: 0.34)) {
            rippleScale = 1.65
            rippleOpacity = 0
        }
    }

    private func cleanupAllActiveGestures() {
        longPressTask?.cancel()
        longPressTask = nil
        cancelMomentumTasks()
        cancelEdgeHold()
        if longPressFired {
            client.sendKey(.ok, action: .up)
            longPressFired = false
        }
    }
}

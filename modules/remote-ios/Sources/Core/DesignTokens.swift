import SwiftUI

enum DesignTokens {
    // MARK: - Deep OLED Obsidian & Tactile Graphite Palette
    static let obsidianBG = Color(red: 10.0 / 255.0, green: 11.0 / 255.0, blue: 14.0 / 255.0)
    static let obsidianAmbient = Color(red: 18.0 / 255.0, green: 20.0 / 255.0, blue: 27.0 / 255.0)

    static let surfaceTop = Color(red: 26.0 / 255.0, green: 27.0 / 255.0, blue: 33.0 / 255.0)
    static let surfaceBottom = Color(red: 18.0 / 255.0, green: 19.0 / 255.0, blue: 24.0 / 255.0)

    static let buttonTop = Color(red: 40.0 / 255.0, green: 42.0 / 255.0, blue: 51.0 / 255.0)
    static let buttonBottom = Color(red: 29.0 / 255.0, green: 31.0 / 255.0, blue: 38.0 / 255.0)
    static let buttonPressed = Color(red: 56.0 / 255.0, green: 59.0 / 255.0, blue: 72.0 / 255.0)

    static let ringOuterTop = Color(red: 36.0 / 255.0, green: 38.0 / 255.0, blue: 47.0 / 255.0)
    static let ringOuterBottom = Color(red: 24.0 / 255.0, green: 26.0 / 255.0, blue: 33.0 / 255.0)
    static let ringCenterTop = Color(red: 22.0 / 255.0, green: 24.0 / 255.0, blue: 30.0 / 255.0)
    static let ringCenterBottom = Color(red: 16.0 / 255.0, green: 17.0 / 255.0, blue: 22.0 / 255.0)

    static let specularRim = Color.white.opacity(0.12)
    static let specularRimSubtle = Color.white.opacity(0.06)
    static let specularRimBright = Color.white.opacity(0.24)

    static let labelPrimary = Color.white.opacity(0.94)
    static let labelSecondary = Color.white.opacity(0.62)
    static let labelTertiary = Color.white.opacity(0.36)

    // MARK: - Status Colors
    static let statusAwake = Color(red: 0.22, green: 0.86, blue: 0.46)
    static let statusStandby = Color(red: 0.98, green: 0.68, blue: 0.18)
    static let statusOffline = Color(red: 0.94, green: 0.30, blue: 0.32)

    static let surfaceCornerRadius: CGFloat = 34
}

// MARK: - Responsive Single-Screen Layout Metrics (iPhone SE -> Pro Max)

struct RemoteLayoutMetrics {
    let horizontalPadding: CGFloat
    let sectionSpacing: CGFloat
    let buttonDiameter: CGFloat
    let deckRowGap: CGFloat
    let deckColumnGap: CGFloat
    let topBarHeight: CGFloat
    let bottomBarHeight: CGFloat
    let iconSize: CGFloat

    var volumeRockerHeight: CGFloat {
        (buttonDiameter * 2.0) + deckRowGap
    }

    init(containerSize: CGSize) {
        let h = containerSize.height
        let w = containerSize.width

        if h < 690 || w < 380 {
            // Compact (iPhone SE / mini)
            self.horizontalPadding = 18
            self.sectionSpacing = 12
            self.buttonDiameter = 56
            self.deckRowGap = 12
            self.deckColumnGap = 18
            self.topBarHeight = 44
            self.bottomBarHeight = 40
            self.iconSize = 20
        } else if h < 810 {
            // Standard (iPhone 15 / 16 / 16 Pro)
            self.horizontalPadding = 24
            self.sectionSpacing = 16
            self.buttonDiameter = 66
            self.deckRowGap = 16
            self.deckColumnGap = 24
            self.topBarHeight = 44
            self.bottomBarHeight = 42
            self.iconSize = 23
        } else {
            // Large (iPhone Plus / Pro Max / iPad)
            self.horizontalPadding = 26
            self.sectionSpacing = 20
            self.buttonDiameter = 72
            self.deckRowGap = 18
            self.deckColumnGap = 28
            self.topBarHeight = 46
            self.bottomBarHeight = 44
            self.iconSize = 24
        }
    }
}

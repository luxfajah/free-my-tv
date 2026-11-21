import Foundation
#if canImport(UIKit)
import UIKit
#endif

@MainActor
final class HapticEngine {
    static let shared = HapticEngine()

    #if canImport(UIKit)
    private let lightImpact = UIImpactFeedbackGenerator(style: .light)
    private let mediumImpact = UIImpactFeedbackGenerator(style: .medium)
    private let heavyImpact = UIImpactFeedbackGenerator(style: .heavy)
    private let selectionGenerator = UISelectionFeedbackGenerator()
    private let notificationGenerator = UINotificationFeedbackGenerator()
    #endif

    private init() {
        prepare()
    }

    func prepare() {
        #if canImport(UIKit)
        lightImpact.prepare()
        mediumImpact.prepare()
        heavyImpact.prepare()
        selectionGenerator.prepare()
        #endif
    }

    func tapLight() {
        #if canImport(UIKit)
        lightImpact.impactOccurred(intensity: 0.75)
        lightImpact.prepare()
        #endif
    }

    func tapMedium() {
        #if canImport(UIKit)
        mediumImpact.impactOccurred(intensity: 0.9)
        mediumImpact.prepare()
        #endif
    }

    func tapHeavy() {
        #if canImport(UIKit)
        heavyImpact.impactOccurred(intensity: 1.0)
        heavyImpact.prepare()
        #endif
    }

    func selection() {
        #if canImport(UIKit)
        selectionGenerator.selectionChanged()
        selectionGenerator.prepare()
        #endif
    }

    func success() {
        #if canImport(UIKit)
        notificationGenerator.notificationOccurred(.success)
        #endif
    }

    func warning() {
        #if canImport(UIKit)
        notificationGenerator.notificationOccurred(.warning)
        #endif
    }

    func error() {
        #if canImport(UIKit)
        notificationGenerator.notificationOccurred(.error)
        #endif
    }
}

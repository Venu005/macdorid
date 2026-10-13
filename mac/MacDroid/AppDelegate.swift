import AppKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    /// Set by the app at startup; opens the main window when the user launches MacDroid again.
    static var onReopen: (@MainActor () -> Void)?
    /// Set by the app at startup; decides whether to open the window once launch details are known.
    static var onLaunch: (@MainActor () -> Void)?
    private(set) static var launchedAsLoginItem = false
    /// Set by the app at startup: answers Services → "Open on Phone".
    static var servicesProvider: NSObject?

    func applicationDidFinishLaunching(_ notification: Notification) {
        if let provider = Self.servicesProvider {
            NSApp.servicesProvider = provider
            NSUpdateDynamicServices()
        }
        let event = NSAppleEventManager.shared().currentAppleEvent
        Self.launchedAsLoginItem = event?.eventID == AEEventID(kAEOpenApplication)
            && event?.paramDescriptor(forKeyword: AEKeyword(keyAEPropData))?.enumCodeValue == OSType(keyAELaunchedAsLogInItem)
        Self.onLaunch?()
    }

    /// Launching MacDroid while it's already running (e.g. its menu-bar icon is hidden by the notch) opens the window.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        Self.onReopen?()
        return false
    }

    /// Open the window on launch, except at login when a phone is already paired (stay quietly in the menu bar).
    static func shouldOpenWindowAtLaunch(paired: Bool) -> Bool {
        !launchedAsLoginItem || !paired
    }
}

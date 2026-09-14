import SwiftUI

@main
struct ERSApp: App {
    @StateObject private var store = ERSStore()
    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(store).preferredColorScheme(.dark)
        }
    }
}

import Combine
import Foundation
import Network

@MainActor
final class NetworkMonitor: ObservableObject {
    @Published private(set) var isConnected = false
    @Published private(set) var allowsLargeTransfers = false

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.sunkwon.AnyPaste.network")

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let isConnected = path.status == .satisfied
            let allowsLargeTransfers = isConnected &&
                (path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet))
            Task { @MainActor in
                self.isConnected = isConnected
                self.allowsLargeTransfers = allowsLargeTransfers
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}

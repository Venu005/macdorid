import Logging
import os

/// Sends MacDroidCore's swift-log output to the unified log (subsystem `dev.macdroid.mac`), so
/// `log stream --predicate 'subsystem == "dev.macdroid.mac"'` shows what the transfer engine and server are doing.
struct OSLogHandler: LogHandler {
    var metadata: Logging.Logger.Metadata = [:]
    var logLevel: Logging.Logger.Level = .info
    private let log: os.Logger

    init(label: String) {
        log = os.Logger(subsystem: "dev.macdroid.mac", category: label)
    }

    subscript(metadataKey key: String) -> Logging.Logger.Metadata.Value? {
        get { metadata[key] }
        set { metadata[key] = newValue }
    }

    func log(event: LogEvent) {
        let text = event.error.map { "\(event.message) (\($0))" } ?? event.message.description
        switch event.level {
        case .trace, .debug: log.debug("\(text, privacy: .public)")
        case .info, .notice: log.info("\(text, privacy: .public)")
        case .warning: log.warning("\(text, privacy: .public)")
        case .error, .critical: log.error("\(text, privacy: .public)")
        }
    }
}

// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "MacDroidCore",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "MacDroidCore", targets: ["MacDroidCore"]),
    ],
    dependencies: [
        .package(url: "https://github.com/hummingbird-project/hummingbird.git", from: "2.27.0"),
        .package(url: "https://github.com/hummingbird-project/hummingbird-websocket.git", from: "2.8.0"),
        .package(url: "https://github.com/hummingbird-project/swift-websocket.git", from: "1.6.0"),
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.80.0"),
        .package(url: "https://github.com/apple/swift-nio-ssl.git", from: "2.30.0"),
        .package(url: "https://github.com/apple/swift-certificates.git", from: "1.21.0"),
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"6.0.0"),
        // Hummingbird 2.27.1 imports ServiceContextModule, which swift-distributed-tracing 1.6.0 removed.
        .package(url: "https://github.com/apple/swift-distributed-tracing.git", "1.3.0"..<"1.6.0"),
    ],
    targets: [
        .target(
            name: "MacDroidCore",
            dependencies: [
                .product(name: "Hummingbird", package: "hummingbird"),
                .product(name: "HummingbirdTLS", package: "hummingbird"),
                .product(name: "HummingbirdWebSocket", package: "hummingbird-websocket"),
                .product(name: "NIOCore", package: "swift-nio"),
                .product(name: "NIOConcurrencyHelpers", package: "swift-nio"),
                .product(name: "NIOSSL", package: "swift-nio-ssl"),
                .product(name: "X509", package: "swift-certificates"),
                .product(name: "Crypto", package: "swift-crypto"),
            ]
        ),
        .testTarget(
            name: "MacDroidCoreTests",
            dependencies: [
                "MacDroidCore",
                .product(name: "HummingbirdTesting", package: "hummingbird"),
                .product(name: "WSClient", package: "swift-websocket"),
            ]
        ),
    ]
)

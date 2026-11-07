// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Slimmer",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "Slimmer",
            path: "Sources/Slimmer",
            linkerSettings: [
                .linkedFramework("Network"),
                .linkedFramework("CryptoKit"),
                .linkedFramework("UserNotifications"),
                .linkedFramework("ServiceManagement"),
                .linkedFramework("CoreImage"),
            ]
        ),
    ]
)

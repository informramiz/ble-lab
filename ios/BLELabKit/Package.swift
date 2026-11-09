// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "BLELabKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "BLELabKit", targets: ["BLELabKit"])],
    targets: [
        .target(name: "BLELabKit"),
        .testTarget(name: "BLELabKitTests", dependencies: ["BLELabKit"]),
    ]
)

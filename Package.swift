// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapUpdate",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapUpdate",
            targets: ["CapUpdatePlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0"),
        .package(url: "https://github.com/ZipArchive/ZipArchive.git", from: "2.5.0")
    ],
    targets: [
        .target(
            name: "CapUpdatePlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "ZipArchive", package: "ZipArchive")
            ],
            path: "ios/Sources/CapUpdatePlugin"),
        .testTarget(
            name: "CapUpdatePluginTests",
            dependencies: ["CapUpdatePlugin"],
            path: "ios/Tests/CapUpdatePluginTests")
    ]
)

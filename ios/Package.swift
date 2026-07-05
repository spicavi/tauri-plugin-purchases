// swift-tools-version:5.9

import PackageDescription

let package = Package(
    name: "tauri-plugin-purchases",
    platforms: [
        // Matches Tauri's default iOS deployment target — consumers build
        // this package at *their* app's target. StoreKit 2 needs iOS 15 at
        // runtime; the plugin availability-gates and reports unsupported on
        // older versions instead of raising the deployment floor.
        .iOS(.v13),
        // SwiftPM resolution consistency with the Tauri package's macOS
        // declaration; the target is only ever compiled into iOS builds.
        .macOS(.v12),
    ],
    products: [
        .library(
            name: "tauri-plugin-purchases",
            type: .static,
            targets: ["tauri-plugin-purchases"]),
    ],
    dependencies: [
        // Tauri runtime injected as a sibling local package by the Tauri CLI
        // when the consumer runs `tauri ios init` / `tauri ios dev`.
        .package(name: "Tauri", path: "../.tauri/tauri-api"),
    ],
    targets: [
        .target(
            name: "tauri-plugin-purchases",
            dependencies: [
                .byName(name: "Tauri"),
            ],
            path: "Sources"),
    ]
)

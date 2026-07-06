// swift-tools-version:5.9

import PackageDescription

let package = Package(
    name: "tauri-plugin-purchases",
    platforms: [
        // HARD floor: the consumer app's deployment target must be >= 15
        // (tauri.conf.json bundle.iOS.minimumSystemVersion: "15.0"). Swift
        // concurrency compiled below 15 goes through the back-deployment
        // runtime, which crashes at Task creation / inside
        // libswift_Concurrency when linked through tauri's swift-rs static
        // lib. Declaring v15 turns that runtime crash into a build error.
        .iOS(.v15),
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

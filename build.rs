const COMMANDS: &[&str] = &[
    "is_supported",
    "get_products",
    "purchase",
    "restore_purchases",
    "get_entitlements",
    "get_subscription_status",
    "manage_subscriptions",
    // Purchase updates (renewals, revocations, deferred approvals) are pushed
    // as `purchaseUpdated` plugin events, so the listener commands are needed.
    "register_listener",
    "remove_listener",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .ios_path("ios")
        .build();
}

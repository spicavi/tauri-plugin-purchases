//! In-app subscriptions and purchases for Tauri 2 apps.
//!
//! - **iOS**: StoreKit 2 (`Product` / `Transaction`). Requires iOS 15+ at
//!   runtime (older versions report unsupported). In-App Purchase needs no
//!   entitlements-file key — the capability is implicit for App Store apps;
//!   products must exist in App Store Connect (or a StoreKit configuration
//!   file during development).
//! - **Android**: Google Play Billing. `jws` carries the Play purchase
//!   token (the server-side validation credential); purchases are
//!   acknowledged client-side so Play never auto-refunds them.
//! - **Desktop**: every command rejects with `Unsupported`; `is_supported`
//!   reports `{ supported: false, platform: "unsupported" }`.
//!
//! Verified transactions carry a `jws` (StoreKit 2 signed transaction) so a
//! server can validate them; client-side purchase state is for UI only and
//! must never be treated as an entitlement source.

use tauri::{
    plugin::{Builder, TauriPlugin},
    Manager, Runtime,
};

pub use models::*;

#[cfg(desktop)]
mod desktop;
#[cfg(mobile)]
mod mobile;

mod commands;
mod error;
mod models;

pub use error::{Error, Result};

#[cfg(desktop)]
use desktop::Purchases;
#[cfg(mobile)]
use mobile::Purchases;

/// Extensions to [`tauri::App`], [`tauri::AppHandle`] and [`tauri::Window`] to access the purchases APIs.
pub trait PurchasesExt<R: Runtime> {
    fn purchases(&self) -> &Purchases<R>;
}

impl<R: Runtime, T: Manager<R>> crate::PurchasesExt<R> for T {
    fn purchases(&self) -> &Purchases<R> {
        self.state::<Purchases<R>>().inner()
    }
}

/// Initializes the plugin. Call this from your Tauri app's `lib.rs`:
///
/// ```ignore
/// .plugin(tauri_plugin_purchases::init())
/// ```
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("purchases")
        .invoke_handler(tauri::generate_handler![
            commands::is_supported,
            commands::get_products,
            commands::purchase,
            commands::restore_purchases,
            commands::get_entitlements,
            commands::get_subscription_status,
            commands::manage_subscriptions,
            commands::start_purchase_updates,
        ])
        .setup(|app, api| {
            #[cfg(mobile)]
            let purchases = mobile::init(app, api)?;
            #[cfg(desktop)]
            let purchases = desktop::init(app, api)?;
            app.manage(purchases);
            Ok(())
        })
        .build()
}

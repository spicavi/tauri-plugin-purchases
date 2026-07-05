use serde::de::DeserializeOwned;
use tauri::{
    plugin::{PluginApi, PluginHandle},
    AppHandle, Runtime,
};

use crate::models::*;

#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_purchases);

/// Initializes the Kotlin or Swift plugin classes registered by the host app.
pub fn init<R: Runtime, C: DeserializeOwned>(
    _app: &AppHandle<R>,
    api: PluginApi<R, C>,
) -> crate::Result<Purchases<R>> {
    #[cfg(target_os = "android")]
    let handle = api.register_android_plugin("app.tauri.purchases", "PurchasesPlugin")?;
    #[cfg(target_os = "ios")]
    let handle = api.register_ios_plugin(init_plugin_purchases)?;
    Ok(Purchases(handle))
}

/// Access to the purchases APIs on mobile.
pub struct Purchases<R: Runtime>(PluginHandle<R>);

impl<R: Runtime> Purchases<R> {
    // Method names are camelCase to match the @objc / @Command methods on
    // the Swift / Kotlin sides. The async variant is required: a purchase
    // blocks on the store's payment sheet, potentially for minutes.

    pub async fn is_supported(&self) -> crate::Result<SupportStatus> {
        self.0
            .run_mobile_plugin_async("isSupported", ())
            .await
            .map_err(Into::into)
    }

    pub async fn get_products(&self, options: GetProductsOptions) -> crate::Result<ProductList> {
        self.0
            .run_mobile_plugin_async("getProducts", options)
            .await
            .map_err(Into::into)
    }

    pub async fn purchase(&self, options: PurchaseOptions) -> crate::Result<PurchaseResult> {
        self.0
            .run_mobile_plugin_async("purchase", options)
            .await
            .map_err(Into::into)
    }

    pub async fn restore_purchases(&self) -> crate::Result<RestoredPurchases> {
        self.0
            .run_mobile_plugin_async("restorePurchases", ())
            .await
            .map_err(Into::into)
    }

    pub async fn get_entitlements(&self) -> crate::Result<RestoredPurchases> {
        self.0
            .run_mobile_plugin_async("getEntitlements", ())
            .await
            .map_err(Into::into)
    }

    pub async fn get_subscription_status(
        &self,
        options: SubscriptionStatusOptions,
    ) -> crate::Result<SubscriptionStatus> {
        self.0
            .run_mobile_plugin_async("getSubscriptionStatus", options)
            .await
            .map_err(Into::into)
    }

    pub async fn manage_subscriptions(&self) -> crate::Result<()> {
        self.0
            .run_mobile_plugin_async("manageSubscriptions", ())
            .await
            .map_err(Into::into)
    }
}

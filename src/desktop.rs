use serde::de::DeserializeOwned;
use tauri::{plugin::PluginApi, AppHandle, Runtime};

use crate::models::*;
use crate::Error;

const UNSUPPORTED: &str = "in-app purchases are only available on iOS and Android";

pub fn init<R: Runtime, C: DeserializeOwned>(
    _app: &AppHandle<R>,
    _api: PluginApi<R, C>,
) -> crate::Result<Purchases<R>> {
    Ok(Purchases(std::marker::PhantomData))
}

/// Desktop stub — every command rejects with `Unsupported` except
/// `is_supported`, which reports gracefully.
// fn() -> R keeps Purchases Send+Sync regardless of R's auto-traits.
pub struct Purchases<R: Runtime>(std::marker::PhantomData<fn() -> R>);

impl<R: Runtime> Purchases<R> {
    pub async fn is_supported(&self) -> crate::Result<SupportStatus> {
        Ok(SupportStatus {
            supported: false,
            platform: PurchasesPlatform::Unsupported,
            reason: Some(UNSUPPORTED.into()),
        })
    }

    pub async fn get_products(&self, _options: GetProductsOptions) -> crate::Result<ProductList> {
        Err(Error::Unsupported(UNSUPPORTED))
    }

    pub async fn purchase(&self, _options: PurchaseOptions) -> crate::Result<PurchaseResult> {
        Err(Error::Unsupported(UNSUPPORTED))
    }

    pub async fn restore_purchases(&self) -> crate::Result<RestoredPurchases> {
        Err(Error::Unsupported(UNSUPPORTED))
    }

    pub async fn get_entitlements(&self) -> crate::Result<RestoredPurchases> {
        Err(Error::Unsupported(UNSUPPORTED))
    }

    pub async fn get_subscription_status(
        &self,
        _options: SubscriptionStatusOptions,
    ) -> crate::Result<SubscriptionStatus> {
        Err(Error::Unsupported(UNSUPPORTED))
    }

    pub async fn manage_subscriptions(&self) -> crate::Result<()> {
        Err(Error::Unsupported(UNSUPPORTED))
    }

    /// Nothing to arm on desktop; a quiet no-op keeps the guest bindings'
    /// post-registration call harmless everywhere.
    pub async fn start_purchase_updates(&self) -> crate::Result<()> {
        Ok(())
    }
}

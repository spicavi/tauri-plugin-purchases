use tauri::{command, AppHandle, Runtime};

use crate::models::*;
use crate::PurchasesExt;
use crate::Result;

#[command]
pub(crate) async fn is_supported<R: Runtime>(app: AppHandle<R>) -> Result<SupportStatus> {
    app.purchases().is_supported().await
}

#[command]
pub(crate) async fn get_products<R: Runtime>(
    app: AppHandle<R>,
    options: GetProductsOptions,
) -> Result<ProductList> {
    app.purchases().get_products(options).await
}

#[command]
pub(crate) async fn purchase<R: Runtime>(
    app: AppHandle<R>,
    options: PurchaseOptions,
) -> Result<PurchaseResult> {
    app.purchases().purchase(options).await
}

#[command]
pub(crate) async fn restore_purchases<R: Runtime>(app: AppHandle<R>) -> Result<RestoredPurchases> {
    app.purchases().restore_purchases().await
}

#[command]
pub(crate) async fn get_entitlements<R: Runtime>(app: AppHandle<R>) -> Result<RestoredPurchases> {
    app.purchases().get_entitlements().await
}

#[command]
pub(crate) async fn get_subscription_status<R: Runtime>(
    app: AppHandle<R>,
    options: SubscriptionStatusOptions,
) -> Result<SubscriptionStatus> {
    app.purchases().get_subscription_status(options).await
}

#[command]
pub(crate) async fn manage_subscriptions<R: Runtime>(app: AppHandle<R>) -> Result<()> {
    app.purchases().manage_subscriptions().await
}

use serde::{Deserialize, Serialize};

/// Whether in-app purchases are usable on this device.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SupportStatus {
    pub supported: bool,
    pub platform: PurchasesPlatform,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PurchasesPlatform {
    Ios,
    Android,
    Unsupported,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ProductKind {
    Subscription,
    Consumable,
    NonConsumable,
    NonRenewingSubscription,
}

/// The store environment a transaction was made in. Server-side validation
/// must never grant production entitlement from a `sandbox`/`xcode` purchase.
/// `unknown` means the OS could not report it (iOS 15, and always on
/// Android) — the server must derive it from `jws` instead.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum StoreEnvironment {
    Production,
    Sandbox,
    Xcode,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum IntroOfferPaymentMode {
    FreeTrial,
    PayAsYouGo,
    PayUpFront,
}

/// An introductory offer configured on a subscription (e.g. a 7-day free
/// trial). Whether *this* user can still redeem it is `SubscriptionInfo::
/// intro_eligible` — Apple enforces one intro offer per Apple ID per group.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IntroOffer {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    pub payment_mode: IntroOfferPaymentMode,
    pub display_price: String,
    pub price: f64,
    /// ISO 8601 duration of one offer period, e.g. `P1W`.
    pub period: String,
    /// How many `period`s the offer spans.
    pub period_count: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SubscriptionInfo {
    pub group_id: String,
    /// ISO 8601 duration of one renewal period, e.g. `P1M`.
    pub period: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub intro_offer: Option<IntroOffer>,
    /// Whether the current store account may still redeem `intro_offer`.
    /// Gate any "free trial" copy on this — never show a trial the store
    /// will silently not honor.
    pub intro_eligible: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Product {
    pub id: String,
    pub kind: ProductKind,
    pub title: String,
    pub description: String,
    /// Locale-formatted price for display, e.g. `$9.99`.
    pub display_price: String,
    pub price: f64,
    /// ISO 4217 currency code; empty when the OS can't report it.
    pub currency: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subscription: Option<SubscriptionInfo>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PurchaseState {
    Purchased,
    /// Revoked by the store (refund, family-sharing revocation).
    Revoked,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OwnershipKind {
    Purchased,
    FamilyShared,
}

/// A verified store transaction. `jws` carries the server-side validation
/// credential — the client-side fields are for UI only and must never be
/// trusted as an entitlement source.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Purchase {
    pub product_id: String,
    pub transaction_id: String,
    pub original_transaction_id: String,
    /// Unix epoch milliseconds.
    pub purchased_at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub revoked_at: Option<i64>,
    pub state: PurchaseState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub will_auto_renew: Option<bool>,
    /// The UUID the purchase was attributed to via `PurchaseOptions`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub app_account_token: Option<String>,
    pub quantity: u32,
    pub ownership: OwnershipKind,
    pub environment: StoreEnvironment,
    /// The server-side validation credential: the StoreKit 2 signed
    /// transaction (JWS compact serialization) on iOS, the Google Play
    /// purchase token on Android. The field name is a StoreKit-ism kept for
    /// wire parity — treat it as "the opaque credential the server
    /// validates" on both platforms.
    pub jws: String,
    pub bundle_id: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PurchaseOutcome {
    Purchased,
    /// Awaiting external action (e.g. Ask to Buy parental approval). The
    /// eventual transaction arrives through the `purchaseUpdated` event.
    Pending,
    /// The user dismissed the payment sheet — not an error.
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PurchaseResult {
    pub outcome: PurchaseOutcome,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub purchase: Option<Purchase>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RestoredPurchases {
    pub purchases: Vec<Purchase>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProductList {
    pub products: Vec<Product>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SubscriptionState {
    Subscribed,
    Expired,
    InBillingRetry,
    InGracePeriod,
    Revoked,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SubscriptionStatus {
    pub product_id: String,
    /// True while the subscription grants service (subscribed or in grace).
    pub active: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub state: Option<SubscriptionState>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub will_auto_renew: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub environment: Option<StoreEnvironment>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GetProductsOptions {
    pub product_ids: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PurchaseOptions {
    pub product_id: String,
    /// Opaque account attribution forwarded to the store (StoreKit
    /// `appAccountToken`, Play `setObfuscatedAccountId`). Must be a UUID
    /// string on iOS; any string up to 64 characters on Android.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub app_account_token: Option<String>,
    /// iOS only — Play Billing has no client-side quantity option.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub quantity: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SubscriptionStatusOptions {
    pub product_id: String,
}

import {
	addPluginListener,
	invoke,
	type PluginListener,
} from '@tauri-apps/api/core';

// ---------------------------------------------------------------------------
// Types (mirror src/models.rs)
// ---------------------------------------------------------------------------

export type PurchasesPlatform = 'ios' | 'android' | 'unsupported';

/** Whether in-app purchases are usable on this device. */
export interface SupportStatus {
	supported: boolean;
	platform: PurchasesPlatform;
	reason?: string;
}

export type ProductKind =
	| 'subscription'
	| 'consumable'
	| 'nonConsumable'
	| 'nonRenewingSubscription';

/**
 * The store environment a transaction was made in. Server-side validation
 * must never grant production entitlement from a `sandbox`/`xcode` purchase.
 * `unknown` means the OS could not report it (iOS 15, and always on
 * Android) — the server must derive it from `jws` instead.
 */
export type StoreEnvironment = 'production' | 'sandbox' | 'xcode' | 'unknown';

export type IntroOfferPaymentMode = 'freeTrial' | 'payAsYouGo' | 'payUpFront';

/**
 * An introductory offer configured on a subscription (e.g. a 7-day free
 * trial). Whether *this* user can still redeem it is
 * {@link SubscriptionInfo.introEligible}.
 */
export interface IntroOffer {
	id?: string;
	paymentMode: IntroOfferPaymentMode;
	displayPrice: string;
	price: number;
	/** ISO 8601 duration of one offer period, e.g. `P1W`. */
	period: string;
	/** How many `period`s the offer spans. */
	periodCount: number;
}

export interface SubscriptionInfo {
	groupId: string;
	/** ISO 8601 duration of one renewal period, e.g. `P1M`. */
	period: string;
	introOffer?: IntroOffer;
	/**
	 * Whether the current store account may still redeem `introOffer`.
	 * Gate any "free trial" copy on this — the store enforces one intro
	 * offer per account and will silently not honor a second one.
	 */
	introEligible: boolean;
}

export interface Product {
	id: string;
	kind: ProductKind;
	title: string;
	description: string;
	/** Locale-formatted price for display, e.g. `$9.99`. */
	displayPrice: string;
	price: number;
	/** ISO 4217 currency code; empty when the OS can't report it. */
	currency: string;
	subscription?: SubscriptionInfo;
}

export type PurchaseState = 'purchased' | 'revoked';

export type OwnershipKind = 'purchased' | 'familyShared';

/**
 * A verified store transaction. `jws` carries the server-side validation
 * credential — the client-side fields are for UI only and must never be
 * trusted as an entitlement source.
 */
export interface Purchase {
	productId: string;
	transactionId: string;
	originalTransactionId: string;
	/** Unix epoch milliseconds. */
	purchasedAt: number;
	expiresAt?: number;
	revokedAt?: number;
	state: PurchaseState;
	willAutoRenew?: boolean;
	/** The UUID the purchase was attributed to via `purchase()` options. */
	appAccountToken?: string;
	quantity: number;
	ownership: OwnershipKind;
	environment: StoreEnvironment;
	/**
	 * The server-side validation credential: the StoreKit 2 signed
	 * transaction (JWS compact serialization) on iOS, the Google Play
	 * purchase token on Android. The field name is a StoreKit-ism kept for
	 * wire parity — treat it as "the opaque credential the server
	 * validates" on both platforms.
	 */
	jws: string;
	bundleId: string;
}

export type PurchaseOutcome =
	/** The transaction verified and completed. */
	| 'purchased'
	/**
	 * Awaiting external action (e.g. Ask to Buy parental approval). The
	 * eventual transaction arrives through {@link onPurchaseUpdated}.
	 */
	| 'pending'
	/** The user dismissed the payment sheet — not an error. */
	| 'cancelled';

export interface PurchaseResult {
	outcome: PurchaseOutcome;
	/** Present only when `outcome` is `purchased`. */
	purchase?: Purchase;
}

export type SubscriptionState =
	| 'subscribed'
	| 'expired'
	| 'inBillingRetry'
	| 'inGracePeriod'
	| 'revoked';

export interface SubscriptionStatus {
	productId: string;
	/** True while the subscription grants service (subscribed or in grace). */
	active: boolean;
	state?: SubscriptionState;
	willAutoRenew?: boolean;
	expiresAt?: number;
	environment?: StoreEnvironment;
}

export interface PurchaseRequestOptions {
	/**
	 * Opaque account attribution forwarded to the store (StoreKit
	 * `appAccountToken`, Play `setObfuscatedAccountId`). Must be a UUID
	 * string on iOS — derive one deterministically from your user id if
	 * needed; any string up to 64 characters on Android.
	 */
	appAccountToken?: string;
	/** iOS only — Play Billing has no client-side quantity option. */
	quantity?: number;
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

/** Report whether in-app purchases are usable on this device. */
export async function isSupported(): Promise<SupportStatus> {
	return await invoke('plugin:purchases|is_supported');
}

/** Load store products (with pricing, subscription and intro-offer detail). */
export async function getProducts(productIds: string[]): Promise<Product[]> {
	const res = await invoke<{ products: Product[] }>(
		'plugin:purchases|get_products',
		{ options: { productIds } },
	);
	return res.products;
}

/**
 * Start the store's payment flow for a product. Resolves for all normal
 * outcomes (including user cancellation); rejects only on real errors.
 */
export async function purchase(
	productId: string,
	options?: PurchaseRequestOptions,
): Promise<PurchaseResult> {
	return await invoke('plugin:purchases|purchase', {
		options: { productId, ...options },
	});
}

/**
 * User-initiated restore: syncs with the store, then returns the current
 * entitlements. Wire this to an explicit "Restore Purchases" button.
 */
export async function restorePurchases(): Promise<Purchase[]> {
	const res = await invoke<{ purchases: Purchase[] }>(
		'plugin:purchases|restore_purchases',
	);
	return res.purchases;
}

/**
 * The store's current entitlements (verified, unexpired transactions),
 * without forcing a store sync — cheap enough for launch-time checks.
 */
export async function getEntitlements(): Promise<Purchase[]> {
	const res = await invoke<{ purchases: Purchase[] }>(
		'plugin:purchases|get_entitlements',
	);
	return res.purchases;
}

/** Renewal state of an auto-renewing subscription. */
export async function getSubscriptionStatus(
	productId: string,
): Promise<SubscriptionStatus> {
	return await invoke('plugin:purchases|get_subscription_status', {
		options: { productId },
	});
}

/**
 * Open the store's own subscription-management surface (the StoreKit manage
 * sheet on iOS, falling back to Apple's subscriptions settings page; the
 * Play subscriptions page on Android). This is the correct — and, under the
 * stores' anti-steering rules, the only — affordance for cancelling or
 * changing a store subscription in-app.
 */
export async function manageSubscriptions(): Promise<void> {
	await invoke('plugin:purchases|manage_subscriptions');
}

/**
 * Listen for transactions that complete outside an active `purchase()` call:
 * renewals, Ask to Buy approvals, offer-code redemptions and refunds or
 * revocations on iOS; pending purchases completing and backgrounded
 * renewals on Android.
 */
export async function onPurchaseUpdated(
	handler: (purchase: Purchase) => void,
): Promise<PluginListener> {
	return await addPluginListener('purchases', 'purchaseUpdated', handler);
}

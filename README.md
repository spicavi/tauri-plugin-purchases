# Tauri Plugin Purchases

In-app subscriptions and purchases for Tauri 2 apps — **StoreKit 2 on iOS**
(Google Play Billing planned for Android; desktop reports unsupported).

- Product catalog with locale-formatted pricing, subscription periods, intro
  offers **and per-account intro-offer eligibility**
- Purchases with `appAccountToken` attribution (UUID), quantity, and
  non-throwing outcomes (`purchased` / `pending` / `cancelled`)
- True user-initiated restore (`AppStore.sync()` + current entitlements)
- Current entitlements and auto-renew subscription status (grace period,
  billing retry, revocation)
- `purchaseUpdated` events from `Transaction.updates` — renewals, Ask to Buy
  approvals, offer-code redemptions, refunds/revocations
- Every transaction handed to JS is StoreKit-verified and carries its **JWS**
  (signed transaction) plus the **store environment**
  (`production`/`sandbox`/`xcode`) so a server can validate independently and
  never grant production entitlement from a sandbox receipt

## Installation

```bash
pnpm add @spicavi/tauri-plugin-purchases
```

```toml
# src-tauri/Cargo.toml
[target.'cfg(any(target_os = "android", target_os = "ios"))'.dependencies]
tauri-plugin-purchases = "0.1"
```

```rust
// src-tauri/src/lib.rs
#[cfg(mobile)]
let builder = builder.plugin(tauri_plugin_purchases::init());
```

```json
// src-tauri/capabilities/mobile.json
{ "permissions": ["purchases:default"] }
```

iOS needs no entitlements-file key for In-App Purchase — the capability is
implicit for App Store distribution. StoreKit requires the app to be
**code-signed**, and products must exist in App Store Connect (or a StoreKit
configuration file during development).

## Usage

```ts
import {
	getProducts,
	purchase,
	restorePurchases,
	getEntitlements,
	getSubscriptionStatus,
	manageSubscriptions,
	onPurchaseUpdated,
} from '@spicavi/tauri-plugin-purchases';

// Catalog — gate free-trial copy on introEligible, never on the offer alone.
const [product] = await getProducts(['app.example.plus.monthly']);
const trialOk = product.subscription?.introEligible ?? false;

// Purchase. Cancellation is an outcome, not an exception.
const result = await purchase(product.id, {
	appAccountToken: userUuid, // must be a UUID string
});
if (result.outcome === 'purchased') {
	// Hand result.purchase.jws to your server for validation — the client
	// fields are for UI only.
}

// Explicit "Restore Purchases" button.
const restored = await restorePurchases();

// Launch-time entitlement check (no store sync).
const owned = await getEntitlements();

// Renewal state of an auto-renewing subscription.
const status = await getSubscriptionStatus(product.id);

// The store's own manage/cancel surface (StoreKit manage sheet, falling
// back to Apple's subscription settings).
await manageSubscriptions();

// Renewals, Ask to Buy approvals, refunds — anything that completes outside
// an active purchase() call.
const listener = await onPurchaseUpdated((p) => {
	// Revalidate p.jws server-side.
});
```

## Platform notes

| Command                 | iOS (15+)                    | Android | Desktop |
| ----------------------- | ---------------------------- | ------- | ------- |
| `isSupported`           | `{ supported: true }`        | `{ supported: false }` (planned) | `{ supported: false }` |
| everything else         | StoreKit 2                   | rejects | rejects |

- **iOS < 15** reports `supported: false` instead of raising the app's
  deployment target.
- **`environment`** is `unknown` on iOS 15 (no `Transaction.environment`
  before iOS 16) — derive it server-side from the JWS payload.
- Transactions are finished after they are verified and surfaced; unverified
  transactions are never handed to JS.

## License

MIT

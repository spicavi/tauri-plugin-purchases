# Tauri Plugin Purchases

In-app subscriptions and purchases for Tauri 2 apps — **StoreKit 2 on iOS,
Google Play Billing on Android** (desktop reports unsupported).

- Product catalog with locale-formatted pricing, subscription periods, intro
  offers **and per-account intro-offer eligibility**
- Purchases with `appAccountToken` attribution, quantity, and
  non-throwing outcomes (`purchased` / `pending` / `cancelled`)
- True user-initiated restore (`AppStore.sync()` + current entitlements on
  iOS; the store's current purchases view on Android)
- Current entitlements and auto-renew subscription status
- `purchaseUpdated` events for transactions that complete outside an active
  `purchase()` call — renewals, Ask to Buy approvals, offer-code
  redemptions, refunds/revocations (iOS `Transaction.updates`), pending
  purchases completing and new-token purchases like resubscribes or plan
  changes (Android reconcile; auto-renewals reuse the purchase token and
  only surface server-side);
  on Android, events that fire before the first listener registers (e.g.
  the first-connection seed replaying unacknowledged purchases) are
  queued and flushed once `onPurchaseUpdated` is registered; on iOS the
  updates stream is armed BY `onPurchaseUpdated` registering — earlier
  transactions stay in StoreKit's unfinished queue (which survives
  relaunches) and are drained on registration
- Every transaction handed to JS carries its server-side validation
  credential in `jws` — the StoreKit-verified **JWS** (signed transaction)
  on iOS, the **Play purchase token** on Android — plus the **store
  environment** (`production`/`sandbox`/`xcode`, `unknown` on Android) so a
  server can validate independently and never grant production entitlement
  from a sandbox receipt

## Installation

```bash
pnpm add @spicavi/tauri-plugin-purchases
```

```toml
# src-tauri/Cargo.toml
[target.'cfg(any(target_os = "android", target_os = "ios"))'.dependencies]
tauri-plugin-purchases = "0.2"
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

**The iOS deployment target must be >= 15**:

```json
// tauri.conf.json
{ "bundle": { "iOS": { "minimumSystemVersion": "15.0" } } }
```

iOS 15 has the same device floor as iOS 14, so this costs no devices. It is
a hard requirement, not a preference: plugin Swift is compiled at the app's
`IPHONEOS_DEPLOYMENT_TARGET`, and Swift concurrency (which StoreKit 2 is
built on) compiled below 15 goes through the back-deployment runtime — which
crashes at runtime (SIGABRT at `Task` creation / EXC_BAD_ACCESS in
`libswift_Concurrency`) when linked through Tauri's swift-rs static-lib
path. The package declares `.iOS(.v15)` so a lower target fails the build
with a clear message instead. After changing the target, run
`cargo clean -p tauri-plugin-purchases` — the env var isn't tracked, so the
Swift won't recompile otherwise.

iOS needs no entitlements-file key for In-App Purchase — the capability is
implicit for App Store distribution. StoreKit requires the app to be
**code-signed**, and products must exist in App Store Connect (or a StoreKit
configuration file during development).

Android needs no manual setup: the `com.android.vending.BILLING` permission
manifest-merges from the plugin. Products must exist in the Play Console and
the app must be known to Play (upload a build to any track, then license
testers can develop against local builds).

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
	appAccountToken: userUuid, // UUID on iOS; any string <= 64 chars on Android
});
if (result.outcome === 'purchased') {
	// Hand result.purchase.jws to your server for validation (the signed
	// transaction on iOS, the Play purchase token on Android) — the client
	// fields are for UI only.
}

// Explicit "Restore Purchases" button.
const restored = await restorePurchases();

// Launch-time entitlement check (no store sync).
const owned = await getEntitlements();

// Renewal state of an auto-renewing subscription.
const status = await getSubscriptionStatus(product.id);

// The store's own manage/cancel surface (StoreKit manage sheet on iOS,
// the Play subscriptions page on Android).
await manageSubscriptions();

// Renewals, Ask to Buy approvals, refunds — anything that completes outside
// an active purchase() call.
const listener = await onPurchaseUpdated((p) => {
	// Revalidate p.jws server-side.
});
```

## Platform notes

| Command         | iOS (15+)             | Android                                | Desktop                |
| --------------- | --------------------- | -------------------------------------- | ---------------------- |
| `isSupported`   | `{ supported: true }` | Play connection + subscriptions check  | `{ supported: false }` |
| everything else | StoreKit 2            | Google Play Billing 8                  | rejects                |

### iOS

- **Deployment targets below 15 fail at build time** (see Installation) —
  Swift-concurrency back-deployment crashes at runtime through the swift-rs
  static-lib path, so the package refuses to build rather than crash.
- **`environment`** is `unknown` on iOS 15 (no `Transaction.environment`
  before iOS 16) — derive it server-side from the JWS payload.
- Transactions are finished after they are verified and surfaced; unverified
  transactions are never handed to JS.

### Android

- **Your app's Kotlin Gradle Plugin must be ≥ 2.1.** billing-ktx 8.3.0 ships
  Kotlin 2.2 metadata (and pulls stdlib 2.2.x), which Kotlin 1.9 consumers
  cannot read — the symptom is a wall of `Unresolved reference` errors for
  ordinary stdlib symbols when compiling this plugin's module. Older Tauri
  Android templates pin `kotlin-gradle-plugin:1.9.x` in
  `src-tauri/gen/android/build.gradle.kts`; bump it to `2.2.10` (Kotlin 2.x
  reads all older plugin metadata, so other Kotlin plugins are unaffected).
- **`jws` carries the Play purchase token** — the server-side validation
  credential on Android (RevenueCat / Play Developer API). The field name is
  a StoreKit-ism kept for wire parity; treat it as "the opaque credential
  your server validates" on both platforms.
- **Acknowledgement is the `transaction.finish()` mirror.** Play auto-refunds
  purchases left unacknowledged for ~3 days, so the plugin acknowledges
  client-side on every path (purchase, first-connection seed, entitlement
  reads, the on-resume reconcile). Acks are gated on `!isAcknowledged`, so
  re-acking is harmless.
- **`expiresAt`/`revokedAt` are absent and `environment` is `unknown`** —
  neither is client-observable through Play Billing. The server derives
  expiry, revocation and sandbox-ness from the purchase token.
- **`restorePurchases()` ≡ `getEntitlements()`** — `queryPurchasesAsync` is
  already the store's current view for the signed-in Google account; there
  is no `AppStore.sync()` analogue.
- **`quantity` is ignored** — Play Billing has no client-side quantity
  option (multi-quantity is a Play Console feature).
- **`appAccountToken`** need not be a UUID on Android — any opaque string up
  to 64 characters (it rides `setObfuscatedAccountId`).
- **`introEligible` mirrors offer presence**: Play only returns offers the
  current user is eligible for, so gate trial copy on it exactly like iOS.
  For that to be store-exact, configure the offer's eligibility in the Play
  Console as "new customer acquisition".
- `getSubscriptionStatus` is an honest approximation (`active`,
  `subscribed`, `willAutoRenew` only) — renewal state, expiry, grace and
  billing-retry detail are not client-observable; the server owns truth.

## License

MIT

// Google Play Billing (billing-ktx 8.x) bridge.
//
// Mirrors the iOS StoreKit 2 implementation's wire contract exactly — every
// JSObject payload matches the serde shapes in src/models.rs:
//
//   - `jws` carries the PLAY PURCHASE TOKEN. That token is the server-side
//     validation credential on Android (RevenueCat / Play Developer API);
//     the field name is a StoreKit-ism the wire contract keeps for parity.
//   - Acknowledgement is Android's `transaction.finish()`: Play auto-refunds
//     purchases left unacknowledged for ~3 days, and server-side receipt
//     validation does not reliably acknowledge — so the plugin acknowledges
//     client-side, gated on `!isAcknowledged` (a re-ack is harmless).
//   - `expiresAt`/`revokedAt` are omitted and `environment` is `unknown`:
//     neither is client-observable through Play Billing — the server derives
//     truth from the purchase token.
//   - Play only returns subscription offers the current user is eligible
//     for, so `introEligible` = "a discounted/trial offer came back".
//
// The billing client is created lazily by the first command — no billing
// work at boot; a broken store must degrade IAP, never break launch (the
// same rationale as the lazy Transaction.updates listener on iOS) — and is
// then kept for the app's lifetime with auto-reconnection. The first
// connection runs a seed pass: purchase tokens the store already knows are
// recorded for event dedupe, and unacknowledged PURCHASED items are treated
// like iOS's unfinished transactions (purchaseUpdated event + acknowledge).

package app.tauri.purchases

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PurchasesPlugin"

// Play's obfuscated account id cap (setObfuscatedAccountId).
private const val MAX_ACCOUNT_TOKEN_LENGTH = 64

@InvokeArg
class GetProductsOptions {
    var productIds: List<String> = emptyList()
}

@InvokeArg
class PurchaseOptions {
    lateinit var productId: String
    var appAccountToken: String? = null

    /**
     * Accepted for wire compatibility, but ignored: Play Billing has no
     * client-side quantity option (multi-quantity is a Console feature the
     * user drives inside the purchase screen).
     */
    var quantity: Int? = null
}

@InvokeArg
class SubscriptionStatusOptions {
    lateinit var productId: String
}

private fun describe(result: BillingResult): String {
    val message = result.debugMessage
    return if (message.isNullOrEmpty()) {
        "code ${result.responseCode}"
    } else {
        "$message (code ${result.responseCode})"
    }
}

private class BillingException(message: String, result: BillingResult) :
    Exception("$message: ${describe(result)}")

@TauriPlugin
class PurchasesPlugin(private val activity: Activity) :
    Plugin(activity), PurchasesUpdatedListener {
    // Main-dispatcher scope: every mutation of the fields below happens on
    // the main thread, so the plugin needs no further synchronization.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var billingClient: BillingClient? = null
    private val clientMutex = Mutex()
    private var seeded = false

    /** launchBillingFlow needs the ProductDetails object, not just an id. */
    private val productCache = mutableMapOf<String, ProductDetails>()

    /** Purchase tokens already surfaced to JS — dedupe for out-of-band events. */
    private val knownTokens = mutableSetOf<String>()

    /** The single in-flight purchase() invoke; completed by onPurchasesUpdated. */
    private var pendingPurchase: Invoke? = null
    private var pendingProductId: String? = null

    /** Best-known owned subscription, for the manage-subscriptions deep link. */
    private var lastOwnedSubProductId: String? = null

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Command
    fun isSupported(invoke: Invoke) {
        scope.launch {
            // Resolve always, never reject — shared client code branches on
            // this without try/catch.
            val res = JSObject()
            res.put("platform", "android")
            try {
                val client = requireClient()
                val feature =
                    client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
                if (feature.responseCode == BillingClient.BillingResponseCode.OK) {
                    res.put("supported", true)
                } else {
                    res.put("supported", false)
                    res.put(
                        "reason",
                        "subscriptions are not supported on this device: ${describe(feature)}",
                    )
                }
            } catch (e: Exception) {
                res.put("supported", false)
                res.put("reason", e.message ?: "Google Play Billing is unavailable")
            }
            invoke.resolve(res)
        }
    }

    @Command
    fun getProducts(invoke: Invoke) {
        val args = invoke.parseArgs(GetProductsOptions::class.java)
        scope.launch {
            try {
                val details = mutableListOf<ProductDetails>()
                if (args.productIds.isNotEmpty()) {
                    val client = requireClient()
                    // Subscriptions first; ids that didn't come back are
                    // re-queried as one-time products.
                    val subs = queryProductDetails(
                        client,
                        args.productIds,
                        BillingClient.ProductType.SUBS,
                    )
                    details += subs
                    val leftover =
                        args.productIds.filter { id -> subs.none { it.productId == id } }
                    if (leftover.isNotEmpty()) {
                        details += queryProductDetails(
                            client,
                            leftover,
                            BillingClient.ProductType.INAPP,
                        )
                    }
                }
                val list = JSONArray()
                for (product in details) {
                    productCache[product.productId] = product
                    list.put(productJson(product))
                }
                val res = JSObject()
                res.put("products", list)
                invoke.resolve(res)
            } catch (e: Exception) {
                invoke.reject(e.message ?: "failed to load products")
            }
        }
    }

    @Command
    fun purchase(invoke: Invoke) {
        val args = invoke.parseArgs(PurchaseOptions::class.java)
        scope.launch {
            if (pendingPurchase != null) {
                invoke.reject("another purchase is already in progress")
                return@launch
            }
            val token = args.appAccountToken
            if (token != null && token.length > MAX_ACCOUNT_TOKEN_LENGTH) {
                // Unlike StoreKit, Play does not require a UUID — only a
                // length-capped opaque string.
                invoke.reject(
                    "appAccountToken must be at most $MAX_ACCOUNT_TOKEN_LENGTH characters",
                )
                return@launch
            }
            pendingPurchase = invoke
            pendingProductId = args.productId
            try {
                val client = requireClient()
                val details = productDetails(client, args.productId)
                if (details == null) {
                    clearPending()
                    invoke.reject("product not found: ${args.productId}")
                    return@launch
                }
                val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                if (details.productType == BillingClient.ProductType.SUBS) {
                    val offer = details.subscriptionOfferDetails?.let { chooseOffer(it) }
                    if (offer == null) {
                        clearPending()
                        invoke.reject("no purchasable offer for product: ${args.productId}")
                        return@launch
                    }
                    productParams.setOfferToken(offer.offerToken)
                }
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(productParams.build()))
                token?.let { flowParams.setObfuscatedAccountId(it) }

                val launched = client.launchBillingFlow(activity, flowParams.build())
                when (launched.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        // The payment sheet is up; onPurchasesUpdated
                        // completes this invoke.
                    }
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                        clearPending()
                        resolveAlreadyOwned(client, invoke, args.productId)
                    }
                    else -> {
                        clearPending()
                        invoke.reject("purchase failed: ${describe(launched)}")
                    }
                }
            } catch (e: Exception) {
                if (pendingPurchase === invoke) clearPending()
                invoke.reject(e.message ?: "purchase failed")
            }
        }
    }

    @Command
    fun restorePurchases(invoke: Invoke) {
        // No AppStore.sync() analogue exists: queryPurchasesAsync already IS
        // the store's current view for this Google account, so restore is
        // the same read as getEntitlements.
        resolveEntitlements(invoke)
    }

    @Command
    fun getEntitlements(invoke: Invoke) {
        resolveEntitlements(invoke)
    }

    @Command
    fun getSubscriptionStatus(invoke: Invoke) {
        val args = invoke.parseArgs(SubscriptionStatusOptions::class.java)
        scope.launch {
            try {
                val client = requireClient()
                val owned = querySubs(client).firstOrNull {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        it.products.contains(args.productId)
                }
                // Honest approximation: Play Billing exposes no renewal
                // state, expiry, grace or billing-retry detail client-side —
                // the server owns subscription truth via the purchase token.
                val res = JSObject()
                res.put("productId", args.productId)
                res.put("active", owned != null)
                if (owned != null) {
                    res.put("state", "subscribed")
                    res.put("willAutoRenew", owned.isAutoRenewing)
                }
                invoke.resolve(res)
            } catch (e: Exception) {
                invoke.reject(e.message ?: "failed to load subscription status")
            }
        }
    }

    @Command
    fun manageSubscriptions(invoke: Invoke) {
        scope.launch {
            // Best-effort: learn the owned sub so the deep link lands on its
            // management page instead of the generic subscriptions list.
            if (lastOwnedSubProductId == null) {
                try {
                    querySubs(requireClient())
                } catch (_: Exception) {
                    // The manage page must open even when billing is broken.
                }
            }
            val sku = lastOwnedSubProductId
            val url = if (sku != null) {
                "https://play.google.com/store/account/subscriptions" +
                    "?sku=${Uri.encode(sku)}&package=${Uri.encode(activity.packageName)}"
            } else {
                "https://play.google.com/store/account/subscriptions"
            }
            try {
                // Prefer the Play Store app…
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .setPackage("com.android.vending"),
                )
                invoke.resolve()
            } catch (_: ActivityNotFoundException) {
                try {
                    // …falling back to the browser.
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    invoke.resolve()
                } catch (e: Exception) {
                    invoke.reject(
                        "could not open the Play subscriptions page: ${e.message}",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Store events
    // ------------------------------------------------------------------

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        scope.launch {
            val invoke = pendingPurchase
            val productId = pendingProductId
            clearPending()
            try {
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        val batch = purchases.orEmpty()
                        if (invoke == null) {
                            // Out-of-band delivery (renewal, a pending
                            // purchase completing, a purchase made from
                            // another surface).
                            handleOutOfBand(batch)
                            return@launch
                        }
                        val purchased = batch.firstOrNull {
                            it.purchaseState == Purchase.PurchaseState.PURCHASED
                        }
                        if (purchased != null) {
                            knownTokens.add(purchased.purchaseToken)
                            purchased.products.firstOrNull()?.let { id ->
                                if (productCache[id]?.productType ==
                                    BillingClient.ProductType.SUBS
                                ) {
                                    lastOwnedSubProductId = id
                                }
                            }
                            billingClient?.let { ensureAcknowledged(it, purchased) }
                            // Anything else delivered in the same batch goes
                            // out as events.
                            handleOutOfBand(batch.filter { it !== purchased })
                            val res = JSObject()
                            res.put("outcome", "purchased")
                            res.put("purchase", purchaseJson(purchased))
                            invoke.resolve(res)
                        } else if (batch.any {
                                it.purchaseState == Purchase.PurchaseState.PENDING
                            }
                        ) {
                            // e.g. a slow-card / cash payment. PENDING tokens
                            // are deliberately NOT recorded in knownTokens:
                            // the PURCHASED flip must still surface, via the
                            // onResume reconcile or a later update.
                            val res = JSObject()
                            res.put("outcome", "pending")
                            invoke.resolve(res)
                        } else {
                            invoke.reject("the store reported success without a purchase")
                        }
                    }
                    BillingClient.BillingResponseCode.USER_CANCELED -> {
                        // Dismissing the payment sheet is an outcome, not an
                        // error.
                        if (invoke != null) {
                            val res = JSObject()
                            res.put("outcome", "cancelled")
                            invoke.resolve(res)
                        }
                    }
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                        if (invoke != null && productId != null) {
                            resolveAlreadyOwned(requireClient(), invoke, productId)
                        } else {
                            invoke?.reject("purchase failed: ${describe(result)}")
                        }
                    }
                    else -> invoke?.reject("purchase failed: ${describe(result)}")
                }
            } catch (e: Exception) {
                invoke?.reject(e.message ?: "purchase failed")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reconcile only once billing has been used this launch — never boot
        // the billing stack just because the app came to the foreground
        // (onResume also fires on cold starts).
        if (billingClient == null) return
        scope.launch {
            try {
                val client = requireClient()
                val owned = queryAllPurchases(client).filter {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                for (purchase in owned) {
                    // This is how PENDING→PURCHASED completions and renewals
                    // that happened while backgrounded reach JS.
                    if (knownTokens.add(purchase.purchaseToken)) {
                        trigger("purchaseUpdated", purchaseJson(purchase))
                    }
                    ensureAcknowledged(client, purchase)
                }
            } catch (e: Exception) {
                Log.w(TAG, "onResume purchase reconcile failed: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Billing client lifecycle
    // ------------------------------------------------------------------

    private suspend fun requireClient(): BillingClient = clientMutex.withLock {
        val existing = billingClient
        if (existing != null && existing.isReady) return existing

        val client = existing ?: BillingClient.newBuilder(activity)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build(),
            )
            .enableAutoServiceReconnection()
            .build()
            .also { billingClient = it }

        val setup = connect(client)
        // A DEVELOPER_ERROR race with the library's own auto-reconnection can
        // report failure while the client is in fact ready — trust isReady.
        if (setup.responseCode != BillingClient.BillingResponseCode.OK && !client.isReady) {
            throw BillingException("Google Play Billing is unavailable", setup)
        }

        if (!seeded) {
            try {
                seedFromStore(client)
                seeded = true
            } catch (e: Exception) {
                // Best-effort — the onResume reconcile covers anything missed.
                Log.w(TAG, "initial purchase seed failed: ${e.message}")
            }
        }
        client
    }

    private suspend fun connect(client: BillingClient): BillingResult =
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    // Auto-reconnection re-invokes this on every later
                    // reconnect; only the first call belongs here.
                    if (cont.isActive) cont.resume(result)
                }

                override fun onBillingServiceDisconnected() {
                    // enableAutoServiceReconnection retries in the background.
                }
            })
        }

    /**
     * First-connection seed: record the purchase tokens the store already
     * knows (event dedupe), and treat unacknowledged PURCHASED items like
     * iOS's unfinished transactions — surface, then acknowledge before
     * Play's ~3-day auto-refund window closes.
     */
    private suspend fun seedFromStore(client: BillingClient) {
        for (purchase in queryAllPurchases(client)) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            knownTokens.add(purchase.purchaseToken)
            if (!purchase.isAcknowledged) {
                trigger("purchaseUpdated", purchaseJson(purchase))
                ensureAcknowledged(client, purchase)
            }
        }
    }

    // ------------------------------------------------------------------
    // Purchase plumbing
    // ------------------------------------------------------------------

    private fun clearPending() {
        pendingPurchase = null
        pendingProductId = null
    }

    private fun resolveEntitlements(invoke: Invoke) {
        scope.launch {
            try {
                val client = requireClient()
                val owned = queryAllPurchases(client).filter {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                val list = JSONArray()
                for (purchase in owned) {
                    knownTokens.add(purchase.purchaseToken)
                    // Defensive re-ack: catch anything that slipped past the
                    // purchase path before Play's auto-refund fires.
                    ensureAcknowledged(client, purchase)
                    list.put(purchaseJson(purchase))
                }
                val res = JSObject()
                res.put("purchases", list)
                invoke.resolve(res)
            } catch (e: Exception) {
                invoke.reject(e.message ?: "failed to load purchases")
            }
        }
    }

    private suspend fun resolveAlreadyOwned(
        client: BillingClient,
        invoke: Invoke,
        productId: String,
    ) {
        val owned = queryAllPurchases(client).firstOrNull {
            it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                it.products.contains(productId)
        }
        if (owned == null) {
            invoke.reject(
                "the store reports this product as already owned, but the purchase " +
                    "could not be loaded — try restorePurchases",
            )
            return
        }
        knownTokens.add(owned.purchaseToken)
        ensureAcknowledged(client, owned)
        val res = JSObject()
        res.put("outcome", "purchased")
        res.put("purchase", purchaseJson(owned, productId))
        invoke.resolve(res)
    }

    private suspend fun handleOutOfBand(purchases: List<Purchase>) {
        if (purchases.isEmpty()) return
        val client = billingClient ?: return
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            if (knownTokens.add(purchase.purchaseToken)) {
                trigger("purchaseUpdated", purchaseJson(purchase))
            }
            ensureAcknowledged(client, purchase)
        }
    }

    /**
     * The Android `transaction.finish()`. Gated on `!isAcknowledged`, so
     * acking an already-acknowledged purchase is a no-op. Failures are
     * logged, never thrown — the purchase DID happen, and the onResume /
     * entitlement reconciles retry the ack.
     */
    private suspend fun ensureAcknowledged(client: BillingClient, purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED ||
            purchase.isAcknowledged
        ) {
            return
        }
        val result = suspendCancellableCoroutine<BillingResult> { cont ->
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build(),
            ) { r -> if (cont.isActive) cont.resume(r) }
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "acknowledge failed for ${purchase.products}: ${describe(result)}")
        }
    }

    // ------------------------------------------------------------------
    // Store queries
    // ------------------------------------------------------------------

    private suspend fun productDetails(
        client: BillingClient,
        productId: String,
    ): ProductDetails? {
        productCache[productId]?.let { return it }
        val found = queryProductDetails(
            client,
            listOf(productId),
            BillingClient.ProductType.SUBS,
        ).firstOrNull()
            ?: queryProductDetails(
                client,
                listOf(productId),
                BillingClient.ProductType.INAPP,
            ).firstOrNull()
        found?.let { productCache[productId] = it }
        return found
    }

    private suspend fun queryProductDetails(
        client: BillingClient,
        productIds: List<String>,
        productType: String,
    ): List<ProductDetails> = suspendCancellableCoroutine { cont ->
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(productType)
                        .build()
                },
            )
            .build()
        client.queryProductDetailsAsync(params) { result, detailsResult ->
            if (!cont.isActive) return@queryProductDetailsAsync
            when (result.responseCode) {
                // ITEM_UNAVAILABLE just means some/all ids don't exist under
                // this product type — absent ids are the caller's signal.
                BillingClient.BillingResponseCode.OK,
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
                    cont.resume(detailsResult.productDetailsList)
                else -> cont.resumeWithException(
                    BillingException("failed to load products", result),
                )
            }
        }
    }

    /** Queries SUBS and remembers an owned one for the manage deep link. */
    private suspend fun querySubs(client: BillingClient): List<Purchase> {
        val subs = queryPurchases(client, BillingClient.ProductType.SUBS)
        subs.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            ?.products?.firstOrNull()
            ?.let { lastOwnedSubProductId = it }
        return subs
    }

    private suspend fun queryAllPurchases(client: BillingClient): List<Purchase> =
        querySubs(client) + queryPurchases(client, BillingClient.ProductType.INAPP)

    private suspend fun queryPurchases(
        client: BillingClient,
        productType: String,
    ): List<Purchase> = suspendCancellableCoroutine { cont ->
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(productType).build(),
        ) { result, purchases ->
            if (!cont.isActive) return@queryPurchasesAsync
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                cont.resume(purchases)
            } else {
                cont.resumeWithException(
                    BillingException("failed to query purchases", result),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Serialization (shapes mirror src/models.rs)
    // ------------------------------------------------------------------

    /**
     * The offer the user should see and buy. Play only returns offers the
     * current user is eligible for, so the best discounted offer present is
     * the right one: free-trial offer > intro-priced offer > bare base plan.
     */
    private fun chooseOffer(
        offers: List<ProductDetails.SubscriptionOfferDetails>,
    ): ProductDetails.SubscriptionOfferDetails? {
        return offers.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        }
            ?: offers.firstOrNull { it.pricingPhases.pricingPhaseList.size > 1 }
            ?: offers.firstOrNull()
    }

    private fun productJson(details: ProductDetails): JSObject {
        val obj = JSObject()
        obj.put("id", details.productId)
        // `name` is the bare product name; `title` appends "(App Name)" —
        // use the former for parity with StoreKit's displayName.
        obj.put("title", details.name)
        obj.put("description", details.description)

        val offer = if (details.productType == BillingClient.ProductType.SUBS) {
            details.subscriptionOfferDetails?.let { chooseOffer(it) }
        } else {
            null
        }
        if (offer != null) {
            obj.put("kind", "subscription")
            val phases = offer.pricingPhases.pricingPhaseList
            // The final phase is the ongoing base price; phases before it
            // are introductory (free trial / discounted period).
            val basePhase = phases.last()
            obj.put("displayPrice", basePhase.formattedPrice)
            obj.put("price", basePhase.priceAmountMicros / 1_000_000.0)
            obj.put("currency", basePhase.priceCurrencyCode)

            val sub = JSObject()
            // Play has no subscription groups; the base plan is the closest
            // grouping identity.
            sub.put("groupId", offer.basePlanId)
            sub.put("period", basePhase.billingPeriod)
            val introPhase = if (phases.size > 1) phases.first() else null
            if (introPhase != null) {
                val intro = JSObject()
                offer.offerId?.let { intro.put("id", it) }
                intro.put(
                    "paymentMode",
                    when {
                        introPhase.priceAmountMicros == 0L -> "freeTrial"
                        introPhase.recurrenceMode ==
                            ProductDetails.RecurrenceMode.FINITE_RECURRING -> "payAsYouGo"
                        else -> "payUpFront" // NON_RECURRING
                    },
                )
                intro.put("displayPrice", introPhase.formattedPrice)
                intro.put("price", introPhase.priceAmountMicros / 1_000_000.0)
                intro.put("period", introPhase.billingPeriod)
                // billingCycleCount is 0 for NON_RECURRING — a single period.
                intro.put("periodCount", maxOf(introPhase.billingCycleCount, 1))
                sub.put("introOffer", intro)
            }
            // Offer presence IS eligibility on Play (requires the Console
            // offer eligibility "new customer acquisition").
            sub.put("introEligible", introPhase != null)
            obj.put("subscription", sub)
        } else {
            // One-time products. Play's catalog doesn't distinguish
            // consumables (consumption is an API decision, not a product
            // property) — report the conservative kind.
            obj.put("kind", "nonConsumable")
            val oneTime = details.oneTimePurchaseOfferDetails
            obj.put("displayPrice", oneTime?.formattedPrice ?: "")
            obj.put("price", (oneTime?.priceAmountMicros ?: 0L) / 1_000_000.0)
            obj.put("currency", oneTime?.priceCurrencyCode ?: "")
        }
        return obj
    }

    private fun purchaseJson(purchase: Purchase, productIdHint: String? = null): JSObject {
        val orderId = purchase.orderId?.takeIf { it.isNotEmpty() }
        val obj = JSObject()
        obj.put("productId", productIdHint ?: purchase.products.firstOrNull().orEmpty())
        obj.put("transactionId", orderId ?: purchase.purchaseToken)
        // Renewal order ids extend the original order id with a ..N suffix —
        // stripping it recovers the subscription's original identity.
        obj.put(
            "originalTransactionId",
            orderId?.substringBefore("..") ?: purchase.purchaseToken,
        )
        obj.put("purchasedAt", purchase.purchaseTime)
        // expiresAt / revokedAt omitted: not client-observable through Play
        // Billing (Purchase carries no expiry, and revoked purchases simply
        // stop coming back) — the server derives them from the token.
        obj.put("state", "purchased")
        obj.put("willAutoRenew", purchase.isAutoRenewing)
        purchase.accountIdentifiers?.obfuscatedAccountId?.takeIf { it.isNotEmpty() }?.let {
            obj.put("appAccountToken", it)
        }
        obj.put("quantity", maxOf(purchase.quantity, 1))
        obj.put("ownership", "purchased")
        // Play Billing doesn't report the store environment client-side; the
        // server resolves it from the token (e.g. RevenueCat's is_sandbox).
        obj.put("environment", "unknown")
        // THE cross-repo contract: on Android `jws` carries the Play purchase
        // token — the credential a server (RevenueCat / Play Developer API)
        // validates. The field name is a StoreKit-ism kept for wire parity.
        obj.put("jws", purchase.purchaseToken)
        obj.put("bundleId", purchase.packageName)
        return obj
    }
}

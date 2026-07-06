//
//  PurchasesPlugin.swift
//  tauri-plugin-purchases
//
//  StoreKit 2 in-app subscriptions and purchases.
//
//  No entitlements-file key is required for In-App Purchase on iOS — the
//  capability is implicit for App Store distribution. Products must exist in
//  App Store Connect (or a StoreKit configuration file during development),
//  and StoreKit requires the app to be code-signed.
//
//  Every transaction handed to JS is StoreKit-verified and carries its JWS
//  (signed transaction) so a server can validate it independently. Client
//  state is for UI only — never an entitlement source.
//

import Foundation
import StoreKit
import Tauri
import UIKit
import WebKit

// MARK: - Command arguments

class GetProductsArgs: Decodable {
    let productIds: [String]
}

class PurchaseArgs: Decodable {
    let productId: String
    let appAccountToken: String?
    let quantity: Int?
}

class SubscriptionStatusArgs: Decodable {
    let productId: String
}

// MARK: - Payloads (mirror src/models.rs / guest-js exactly)

struct SupportStatusPayload: Encodable {
    let supported: Bool
    let platform: String
    var reason: String? = nil
}

struct IntroOfferPayload: Encodable {
    let id: String?
    let paymentMode: String
    let displayPrice: String
    let price: Double
    let period: String
    let periodCount: Int
}

struct SubscriptionInfoPayload: Encodable {
    let groupId: String
    let period: String
    let introOffer: IntroOfferPayload?
    let introEligible: Bool
}

struct ProductPayload: Encodable {
    let id: String
    let kind: String
    let title: String
    let description: String
    let displayPrice: String
    let price: Double
    let currency: String
    let subscription: SubscriptionInfoPayload?
}

struct ProductListPayload: Encodable {
    let products: [ProductPayload]
}

struct PurchasePayload: Encodable {
    let productId: String
    let transactionId: String
    let originalTransactionId: String
    let purchasedAt: Int64
    let expiresAt: Int64?
    let revokedAt: Int64?
    let state: String
    let willAutoRenew: Bool?
    let appAccountToken: String?
    let quantity: Int
    let ownership: String
    let environment: String
    let jws: String
    let bundleId: String
}

struct PurchaseResultPayload: Encodable {
    let outcome: String
    let purchase: PurchasePayload?
}

struct PurchaseListPayload: Encodable {
    let purchases: [PurchasePayload]
}

struct SubscriptionStatusPayload: Encodable {
    let productId: String
    let active: Bool
    let state: String?
    let willAutoRenew: Bool?
    let expiresAt: Int64?
    let environment: String?
}

private func ms(_ date: Date) -> Int64 {
    Int64(date.timeIntervalSince1970 * 1000)
}

// MARK: - Plugin

@available(iOS 15.0, *)
class PurchasesPlugin: Plugin {
    private var updatesTask: Task<Void, Never>? = nil

    /// Deliver transactions that complete outside an active purchase() call:
    /// renewals, Ask to Buy approvals, offer-code redemptions and
    /// refunds/revocations all arrive here.
    ///
    /// Armed lazily by the first purchases command instead of in `load` —
    /// Transaction.updates has crashed inside StoreKit itself on some
    /// simulator runtimes (EXC_BAD_ACCESS on iOS 26.5), and a broken store
    /// environment must degrade IAP, never kill the app at boot. Trade-off:
    /// unfinished transactions surface on first IAP use, not at launch.
    private func ensureUpdatesListener() {
        guard updatesTask == nil else { return }
        updatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                await self?.handleUpdate(update)
            }
        }
    }

    deinit {
        updatesTask?.cancel()
    }

    private func handleUpdate(_ update: VerificationResult<Transaction>) async {
        // Unverified transactions are dropped — they never reach JS.
        guard case .verified(let transaction) = update else { return }
        let payload = await purchasePayload(for: transaction, jws: update.jwsRepresentation)
        try? trigger("purchaseUpdated", data: payload)
        await transaction.finish()
    }

    // MARK: Commands

    @objc public func isSupported(_ invoke: Invoke) {
        invoke.resolve(SupportStatusPayload(supported: true, platform: "ios"))
    }

    @objc public func getProducts(_ invoke: Invoke) throws {
        ensureUpdatesListener()
        let args = try invoke.parseArgs(GetProductsArgs.self)
        Task {
            do {
                let products = try await Product.products(for: args.productIds)
                var payloads: [ProductPayload] = []
                for product in products {
                    payloads.append(await productPayload(for: product))
                }
                invoke.resolve(ProductListPayload(products: payloads))
            } catch {
                invoke.reject("failed to load products: \(error.localizedDescription)")
            }
        }
    }

    @objc public func purchase(_ invoke: Invoke) throws {
        ensureUpdatesListener()
        let args = try invoke.parseArgs(PurchaseArgs.self)
        Task {
            do {
                guard let product = try await Product.products(for: [args.productId]).first
                else {
                    invoke.reject("product not found: \(args.productId)")
                    return
                }
                var options: Set<Product.PurchaseOption> = []
                if let token = args.appAccountToken {
                    guard let uuid = UUID(uuidString: token) else {
                        invoke.reject("appAccountToken must be a UUID string")
                        return
                    }
                    options.insert(.appAccountToken(uuid))
                }
                if let quantity = args.quantity {
                    options.insert(.quantity(quantity))
                }
                let result = try await product.purchase(options: options)
                switch result {
                case .success(let verification):
                    guard case .verified(let transaction) = verification else {
                        invoke.reject("transaction failed StoreKit verification")
                        return
                    }
                    let payload = await purchasePayload(
                        for: transaction, jws: verification.jwsRepresentation)
                    await transaction.finish()
                    invoke.resolve(PurchaseResultPayload(outcome: "purchased", purchase: payload))
                case .userCancelled:
                    // Dismissing the payment sheet is a normal outcome, not
                    // an error.
                    invoke.resolve(PurchaseResultPayload(outcome: "cancelled", purchase: nil))
                case .pending:
                    // e.g. Ask to Buy — the eventual transaction arrives via
                    // the purchaseUpdated event.
                    invoke.resolve(PurchaseResultPayload(outcome: "pending", purchase: nil))
                @unknown default:
                    invoke.reject("unknown purchase result")
                }
            } catch {
                invoke.reject("purchase failed: \(error.localizedDescription)")
            }
        }
    }

    @objc public func restorePurchases(_ invoke: Invoke) {
        ensureUpdatesListener()
        Task {
            // Explicit, user-initiated restore: sync with the App Store so a
            // fresh install / new device picks up existing transactions. A
            // thrown sync (e.g. the sign-in prompt was dismissed) still
            // falls back to whatever StoreKit has locally.
            try? await AppStore.sync()
            invoke.resolve(PurchaseListPayload(purchases: await currentPurchases()))
        }
    }

    @objc public func getEntitlements(_ invoke: Invoke) {
        ensureUpdatesListener()
        Task {
            invoke.resolve(PurchaseListPayload(purchases: await currentPurchases()))
        }
    }

    @objc public func getSubscriptionStatus(_ invoke: Invoke) throws {
        ensureUpdatesListener()
        let args = try invoke.parseArgs(SubscriptionStatusArgs.self)
        Task {
            var expiresAt: Int64? = nil
            var environment: String? = nil
            for await entitlement in Transaction.currentEntitlements {
                guard case .verified(let transaction) = entitlement,
                    transaction.productID == args.productId
                else { continue }
                expiresAt = transaction.expirationDate.map { ms($0) }
                environment = environmentString(for: transaction)
            }
            guard let product = try? await Product.products(for: [args.productId]).first,
                let subscription = product.subscription,
                let statuses = try? await subscription.status,
                let status = statuses.first
            else {
                // Not an auto-renewable subscription (or the store is
                // unreachable) — report from the entitlement alone.
                let active = expiresAt.map { $0 > ms(Date()) } ?? false
                invoke.resolve(
                    SubscriptionStatusPayload(
                        productId: args.productId, active: active, state: nil,
                        willAutoRenew: nil, expiresAt: expiresAt, environment: environment))
                return
            }
            var willAutoRenew: Bool? = nil
            if case .verified(let renewal) = status.renewalInfo {
                willAutoRenew = renewal.willAutoRenew
            }
            let active = status.state == .subscribed || status.state == .inGracePeriod
            invoke.resolve(
                SubscriptionStatusPayload(
                    productId: args.productId, active: active,
                    state: subscriptionStateString(status.state),
                    willAutoRenew: willAutoRenew, expiresAt: expiresAt,
                    environment: environment))
        }
    }

    @objc public func manageSubscriptions(_ invoke: Invoke) {
        ensureUpdatesListener()
        Task { @MainActor in
            let scene = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first { $0.activationState == .foregroundActive }
            if let scene = scene {
                do {
                    try await AppStore.showManageSubscriptions(in: scene)
                    invoke.resolve()
                    return
                } catch {
                    // fall through to the URL below
                }
            }
            // Apple's own subscription-management page — the one externally
            // allowed destination under the anti-steering rules.
            if let url = URL(string: "itms-apps://apps.apple.com/account/subscriptions") {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
            invoke.resolve()
        }
    }

    // MARK: Serialization

    private func currentPurchases() async -> [PurchasePayload] {
        var purchases: [PurchasePayload] = []
        for await entitlement in Transaction.currentEntitlements {
            guard case .verified(let transaction) = entitlement else { continue }
            purchases.append(
                await purchasePayload(for: transaction, jws: entitlement.jwsRepresentation))
        }
        return purchases
    }

    private func purchasePayload(for transaction: Transaction, jws: String) async
        -> PurchasePayload
    {
        var willAutoRenew: Bool? = nil
        if transaction.productType == .autoRenewable {
            willAutoRenew = await self.willAutoRenew(productId: transaction.productID)
        }
        return PurchasePayload(
            productId: transaction.productID,
            transactionId: String(transaction.id),
            originalTransactionId: String(transaction.originalID),
            purchasedAt: ms(transaction.purchaseDate),
            expiresAt: transaction.expirationDate.map { ms($0) },
            revokedAt: transaction.revocationDate.map { ms($0) },
            state: transaction.revocationDate == nil ? "purchased" : "revoked",
            willAutoRenew: willAutoRenew,
            appAccountToken: transaction.appAccountToken?.uuidString,
            quantity: transaction.purchasedQuantity,
            ownership: transaction.ownershipType == .familyShared ? "familyShared" : "purchased",
            environment: environmentString(for: transaction),
            jws: jws,
            bundleId: Bundle.main.bundleIdentifier ?? "")
    }

    private func willAutoRenew(productId: String) async -> Bool? {
        guard let product = try? await Product.products(for: [productId]).first,
            let subscription = product.subscription,
            let statuses = try? await subscription.status,
            let status = statuses.first,
            case .verified(let renewal) = status.renewalInfo
        else { return nil }
        return renewal.willAutoRenew
    }

    private func productPayload(for product: Product) async -> ProductPayload {
        var subscriptionInfo: SubscriptionInfoPayload? = nil
        if let subscription = product.subscription {
            var introOffer: IntroOfferPayload? = nil
            if let offer = subscription.introductoryOffer {
                introOffer = IntroOfferPayload(
                    id: offer.id,
                    paymentMode: paymentModeString(offer.paymentMode),
                    displayPrice: offer.displayPrice,
                    price: NSDecimalNumber(decimal: offer.price).doubleValue,
                    period: isoPeriod(offer.period),
                    periodCount: offer.periodCount)
            }
            subscriptionInfo = SubscriptionInfoPayload(
                groupId: subscription.subscriptionGroupID,
                period: isoPeriod(subscription.subscriptionPeriod),
                introOffer: introOffer,
                // Apple enforces one intro offer per Apple ID per group —
                // gate any "free trial" copy on this flag.
                introEligible: await subscription.isEligibleForIntroOffer)
        }
        return ProductPayload(
            id: product.id,
            kind: kindString(product.type),
            title: product.displayName,
            description: product.description,
            displayPrice: product.displayPrice,
            price: NSDecimalNumber(decimal: product.price).doubleValue,
            currency: currencyCode(for: product),
            subscription: subscriptionInfo)
    }

    private func subscriptionStateString(_ state: Product.SubscriptionInfo.RenewalState)
        -> String?
    {
        switch state {
        case .subscribed: return "subscribed"
        case .expired: return "expired"
        case .inBillingRetryPeriod: return "inBillingRetry"
        case .inGracePeriod: return "inGracePeriod"
        case .revoked: return "revoked"
        default: return nil
        }
    }

    private func kindString(_ type: Product.ProductType) -> String {
        switch type {
        case .autoRenewable: return "subscription"
        case .consumable: return "consumable"
        case .nonConsumable: return "nonConsumable"
        case .nonRenewable: return "nonRenewingSubscription"
        default: return "nonConsumable"
        }
    }

    private func paymentModeString(_ mode: Product.SubscriptionOffer.PaymentMode) -> String {
        switch mode {
        case .freeTrial: return "freeTrial"
        case .payAsYouGo: return "payAsYouGo"
        case .payUpFront: return "payUpFront"
        default: return "payUpFront"
        }
    }

    private func isoPeriod(_ period: Product.SubscriptionPeriod) -> String {
        switch period.unit {
        case .day: return "P\(period.value)D"
        case .week: return "P\(period.value)W"
        case .month: return "P\(period.value)M"
        case .year: return "P\(period.value)Y"
        @unknown default: return "P\(period.value)D"
        }
    }

    private func environmentString(for transaction: Transaction) -> String {
        if #available(iOS 16.0, *) {
            switch transaction.environment {
            case .production: return "production"
            case .sandbox: return "sandbox"
            case .xcode: return "xcode"
            default: return "unknown"
            }
        }
        // iOS 15 has no Transaction.environment; the server must derive the
        // environment from the JWS payload instead.
        return "unknown"
    }

    private func currencyCode(for product: Product) -> String {
        if #available(iOS 16.0, *) {
            return product.priceFormatStyle.locale.currency?.identifier ?? ""
        }
        return ""
    }
}

// MARK: - Pre-iOS-15 fallback

class PurchasesUnsupportedPlugin: Plugin {
    private static let reason = "in-app purchases require iOS 15 or later"

    @objc public func isSupported(_ invoke: Invoke) {
        invoke.resolve(
            SupportStatusPayload(
                supported: false, platform: "ios", reason: PurchasesUnsupportedPlugin.reason))
    }

    @objc public func getProducts(_ invoke: Invoke) {
        invoke.reject(PurchasesUnsupportedPlugin.reason)
    }

    @objc public func purchase(_ invoke: Invoke) {
        invoke.reject(PurchasesUnsupportedPlugin.reason)
    }

    @objc public func restorePurchases(_ invoke: Invoke) {
        invoke.reject(PurchasesUnsupportedPlugin.reason)
    }

    @objc public func getEntitlements(_ invoke: Invoke) {
        invoke.reject(PurchasesUnsupportedPlugin.reason)
    }

    @objc public func getSubscriptionStatus(_ invoke: Invoke) {
        invoke.reject(PurchasesUnsupportedPlugin.reason)
    }

    @objc public func manageSubscriptions(_ invoke: Invoke) {
        invoke.reject(PurchasesUnsupportedPlugin.reason)
    }
}

@_cdecl("init_plugin_purchases")
func initPlugin() -> Plugin {
    if #available(iOS 15.0, *) {
        return PurchasesPlugin()
    }
    return PurchasesUnsupportedPlugin()
}

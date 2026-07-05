// Android stub — Google Play Billing is not implemented yet. Every command
// rejects except isSupported, which reports gracefully so shared client code
// can branch without try/catch.

package app.tauri.purchases

import android.app.Activity
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

private const val NOT_IMPLEMENTED =
    "in-app purchases on Android are not implemented yet (Google Play Billing planned)"

@TauriPlugin
class PurchasesPlugin(private val activity: Activity) : Plugin(activity) {
    @Command
    fun isSupported(invoke: Invoke) {
        val res = JSObject()
        res.put("supported", false)
        res.put("platform", "android")
        res.put("reason", NOT_IMPLEMENTED)
        invoke.resolve(res)
    }

    @Command
    fun getProducts(invoke: Invoke) {
        invoke.reject(NOT_IMPLEMENTED)
    }

    @Command
    fun purchase(invoke: Invoke) {
        invoke.reject(NOT_IMPLEMENTED)
    }

    @Command
    fun restorePurchases(invoke: Invoke) {
        invoke.reject(NOT_IMPLEMENTED)
    }

    @Command
    fun getEntitlements(invoke: Invoke) {
        invoke.reject(NOT_IMPLEMENTED)
    }

    @Command
    fun getSubscriptionStatus(invoke: Invoke) {
        invoke.reject(NOT_IMPLEMENTED)
    }

    @Command
    fun manageSubscriptions(invoke: Invoke) {
        invoke.reject(NOT_IMPLEMENTED)
    }
}

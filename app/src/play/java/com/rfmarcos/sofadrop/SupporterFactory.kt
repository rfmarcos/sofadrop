package com.rfmarcos.sofadrop

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

fun createSupporter(context: Context, onChange: (Supporter.State) -> Unit): Supporter? = PlaySupporter(context, onChange)

/**
 * One-time product [PRODUCT_ID] of the Play Console, kept by the Google account: nothing is stored here, and a
 * refund simply takes the badge and the colours away. They aren't worth a server to check the purchases.
 */
private class PlaySupporter(context: Context, private val onChange: (Supporter.State) -> Unit) : Supporter, PurchasesUpdatedListener {

    private val main = Handler(Looper.getMainLooper())
    private var closed = false

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override fun refresh() = connected {
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingResponseCode.OK) show(purchases)
        }
    }

    override fun buy(activity: Activity) = connected(failed = { report(Supporter.State.UNAVAILABLE) }) {
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(ProductType.INAPP).build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        client.queryProductDetailsAsync(params) { result, found ->
            val details = found.productDetailsList.firstOrNull()
            if (result.responseCode != BillingResponseCode.OK || details == null) {
                report(Supporter.State.UNAVAILABLE)
                return@queryProductDetailsAsync
            }
            val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()
            val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item)).build()
            main.post {
                if (!closed && client.launchBillingFlow(activity, flow).responseCode != BillingResponseCode.OK) {
                    report(Supporter.State.UNAVAILABLE)
                }
            }
        }
    }

    override fun close() {
        closed = true
        client.endConnection()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> show(purchases.orEmpty())
            BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingResponseCode.USER_CANCELED -> Unit
            else -> report(Supporter.State.UNAVAILABLE)
        }
    }

    private fun connected(failed: () -> Unit = {}, ready: () -> Unit) {
        if (client.isReady) return ready()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) ready() else failed()
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun show(purchases: List<Purchase>) {
        val purchase = purchases.firstOrNull { PRODUCT_ID in it.products }
        // Google refunds what isn't acknowledged in three days; a failure here is tried again by the next refresh
        if (purchase?.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            client.acknowledgePurchase(params) { }
        }
        report(
            when (purchase?.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> Supporter.State.BOUGHT
                Purchase.PurchaseState.PENDING -> Supporter.State.PENDING
                else -> Supporter.State.NOT_BOUGHT
            }
        )
    }

    private fun report(state: Supporter.State) {
        main.post { if (!closed) onChange(state) }
    }

    private companion object {
        const val PRODUCT_ID = "supporter"
    }
}

package com.sack.pcremote.pro

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Canal Play: Pro es una compra única (producto [PRODUCT_ID] en Play Console). Google Play
 * la recuerda: se recupera al abrir la app, también en otro teléfono con la misma cuenta.
 *
 * shortcut: sin servidor, la compra solo se comprueba en el teléfono; si el pirateo llega a importar,
 * verificarla con la Google Play Developer API desde el relay.
 */
class Pro(context: Context, private val scope: CoroutineScope) : PurchasesUpdatedListener {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _price = MutableStateFlow<String?>(null)

    /** Precio con su moneda, como lo da Google Play; `null` mientras no responde. */
    val price: StateFlow<String?> = _price.asStateFlow()

    private var product: ProductDetails? = null

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Al abrir o volver a la app: un pago pendiente (en efectivo, por ejemplo) puede haberse completado. */
    fun refresh() {
        if (client.isReady) {
            load()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) load()
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    fun buy(activity: Activity) {
        val details = product ?: return refresh()
        client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()),
                )
                .build(),
        )
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingResponseCode.OK && purchases != null) {
            scope.launch { if (confirm(purchases)) _active.value = true }
        }
    }

    private fun load() {
        scope.launch {
            val owned = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build())
            // Es la lista completa de Google Play: también quita Pro si la compra se reembolsó.
            _active.value = confirm(owned.purchasesList)
            val query = QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(ProductType.INAPP).build()
            product = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(query)).build())
                .productDetailsList?.firstOrNull()
            _price.value = product?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    /**
     * Si hay una compra pagada de Pro. Google la reembolsa si no se confirma en 3 días: se confirma
     * aquí y, si falla, se reintenta en la próxima apertura.
     */
    private suspend fun confirm(purchases: List<Purchase>): Boolean {
        val owned = purchases.filter { unlocks(it.products, it.purchaseState) }
        owned.filterNot { it.isAcknowledged }.forEach {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(it.purchaseToken).build())
        }
        return owned.isNotEmpty()
    }

    companion object {
        const val PRODUCT_ID = "pro"
    }
}

/** Solo una compra ya pagada de Pro desbloquea; un pago pendiente todavía no. */
internal fun unlocks(products: List<String>, state: Int): Boolean =
    Pro.PRODUCT_ID in products && state == Purchase.PurchaseState.PURCHASED

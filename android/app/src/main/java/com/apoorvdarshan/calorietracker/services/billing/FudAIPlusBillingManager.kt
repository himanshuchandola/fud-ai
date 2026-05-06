package com.apoorvdarshan.calorietracker.services.billing

import android.app.Activity
import android.content.Context
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
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FudAIPlusPlan(
    val productId: String,
    val title: String,
    val price: String,
    val billingPeriod: String
)

data class FudAIPlusBillingState(
    val connected: Boolean = false,
    val loading: Boolean = true,
    val isSubscribed: Boolean = false,
    val plans: List<FudAIPlusPlan> = emptyList(),
    val error: String? = null
)

class FudAIPlusBillingManager(
    context: Context,
    private val prefs: PreferencesStore
) : PurchasesUpdatedListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val productDetailsById = mutableMapOf<String, ProductDetails>()

    private val _state = MutableStateFlow(FudAIPlusBillingState())
    val state: StateFlow<FudAIPlusBillingState> = _state

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    fun connect() {
        if (billingClient.isReady) {
            refresh()
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _state.value = _state.value.copy(connected = true, loading = false, error = null)
                    refresh()
                } else {
                    _state.value = _state.value.copy(
                        connected = false,
                        loading = false,
                        error = result.debugMessage.takeIf { it.isNotBlank() } ?: "Google Play Billing unavailable."
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                _state.value = _state.value.copy(connected = false)
            }
        })
    }

    fun refresh() {
        queryProducts()
        restorePurchases()
    }

    fun purchase(activity: Activity, productId: String) {
        if (!billingClient.isReady) {
            connect()
            _state.value = _state.value.copy(error = "Google Play Billing is still connecting. Try again in a moment.")
            return
        }
        val productDetails = productDetailsById[productId]
        if (productDetails == null) {
            _state.value = _state.value.copy(error = "This Plus plan is not available from Google Play yet.")
            return
        }
        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken.isNullOrBlank()) {
            _state.value = _state.value.copy(error = "This Plus subscription offer is not available.")
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.value = _state.value.copy(
                error = result.debugMessage.takeIf { it.isNotBlank() } ?: "Could not start Google Play checkout."
            )
        }
    }

    fun restorePurchases() {
        if (!billingClient.isReady) {
            connect()
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            } else {
                _state.value = _state.value.copy(error = result.debugMessage.takeIf { it.isNotBlank() })
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> handlePurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED -> _state.value = _state.value.copy(error = null)
            else -> _state.value = _state.value.copy(error = result.debugMessage.takeIf { it.isNotBlank() })
        }
    }

    private fun queryProducts() {
        if (!billingClient.isReady) return
        val productList = PRODUCT_IDS.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        billingClient.queryProductDetailsAsync(params) { result, response ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = result.debugMessage.takeIf { it.isNotBlank() } ?: "Could not load Plus plans."
                )
                return@queryProductDetailsAsync
            }
            productDetailsById.clear()
            response.productDetailsList.forEach { productDetailsById[it.productId] = it }
            val plans = response.productDetailsList
                .mapNotNull(::planFromDetails)
                .sortedBy { if (it.productId == YEARLY_ID) 0 else 1 }
            _state.value = _state.value.copy(loading = false, plans = plans, error = null)
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        val active = purchases.any { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                purchase.products.any { it in PRODUCT_IDS }
        }

        purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase ->
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(params) { }
            }

        scope.launch { prefs.setPlusEntitlementActive(active) }
        _state.value = _state.value.copy(isSubscribed = active, loading = false, error = null)
    }

    private fun planFromDetails(details: ProductDetails): FudAIPlusPlan? {
        val phase = details.subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.firstOrNull()
            ?: return null
        val title = if (details.productId == YEARLY_ID) "Yearly" else "Monthly"
        val period = when (phase.billingPeriod) {
            "P1Y" -> "per year"
            "P1M" -> "per month"
            else -> phase.billingPeriod
        }
        return FudAIPlusPlan(
            productId = details.productId,
            title = title,
            price = phase.formattedPrice,
            billingPeriod = period
        )
    }

    companion object {
        const val MONTHLY_ID = "fudai.subscription.monthly"
        const val YEARLY_ID = "fudai.subscription.yearly"
        val PRODUCT_IDS = setOf(MONTHLY_ID, YEARLY_ID)
    }
}

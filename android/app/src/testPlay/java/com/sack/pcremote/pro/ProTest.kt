package com.sack.pcremote.pro

import com.android.billingclient.api.Purchase.PurchaseState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProTest {
    @Test fun a_paid_purchase_of_pro_unlocks() = assertTrue(unlocks(listOf("pro"), PurchaseState.PURCHASED))

    @Test fun a_pending_payment_does_not_unlock_yet() = assertFalse(unlocks(listOf("pro"), PurchaseState.PENDING))

    @Test fun another_product_does_not_unlock() = assertFalse(unlocks(listOf("otro"), PurchaseState.PURCHASED))
}

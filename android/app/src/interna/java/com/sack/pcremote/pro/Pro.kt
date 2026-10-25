package com.sack.pcremote.pro

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Canal interno: el APK repartido a mano no puede cobrar por Google Play (no lleva su firma),
 * así que Pro viene activo para quien lo instala.
 */
@Suppress("UNUSED_PARAMETER")
class Pro(context: Context, scope: CoroutineScope) {
    val active: StateFlow<Boolean> = MutableStateFlow(true)
    val price: StateFlow<String?> = MutableStateFlow(null)

    fun refresh() = Unit

    fun buy(activity: Activity) = Unit
}

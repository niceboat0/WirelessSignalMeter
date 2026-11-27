package com.craxiom.networksurvey.ui.rootdiag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DiagOverlaySnapshot(
    val collecting: Boolean = false,
    val recording: Boolean = false,
    val status: String = "",
    val rat: String = "",
    val pci: Int? = null,
    val rsrp: Double? = null,
    val sinr: Double? = null,
    val imsReg: String = "unknown",
    val imsRegSource: String = "",
    val imsAvailable: Boolean? = null,
    val imsCall: String = "idle",
    val decodeDropped: Long = 0,
)

internal object DiagOverlayState {
    private val mutable = MutableStateFlow(DiagOverlaySnapshot())
    val snapshot = mutable.asStateFlow()
    fun publish(value: DiagOverlaySnapshot) { mutable.value = value }
}

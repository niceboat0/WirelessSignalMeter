package com.craxiom.networksurvey.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import top.yukonga.miuix.kmp.anim.folmeSpring

internal val PagerNavigationSpringSpec: SpringSpec<IntOffset> = folmeSpring(
    damping = 0.88f,
    response = 0.35f,
    visibilityThreshold = IntOffset(1, 1),
)

internal val PagerSpringSpec: SpringSpec<Float> = folmeSpring(
    damping = 0.88f,
    response = 0.35f,
    visibilityThreshold = 0.5f,
)

internal suspend fun PagerState.pagerAnimateToPage(target: Int) {
    if (target !in 0 until pageCount) return
    var shouldSnapToTarget = false
    scroll(MutatePriority.UserInput) {
        val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
        val distance = target - currentPage - currentPageOffsetFraction
        val scrollPixels = distance * pageSize
        if (abs(scrollPixels) <= 0.5f) return@scroll

        var consumedScroll = 0f
        var skipScroll = false
        Animatable(0f).animateTo(
            targetValue = scrollPixels,
            animationSpec = PagerSpringSpec,
        ) {
            if (skipScroll) return@animateTo

            val delta = value - consumedScroll
            if (abs(delta) > 0.5f) {
                val consumed = scrollBy(delta)
                consumedScroll += consumed
                if (abs(delta - consumed) > 0.1f) {
                    shouldSnapToTarget = true
                    skipScroll = true
                }
            } else {
                consumedScroll = value
            }

            if (abs(velocity) < 0.1f && abs(scrollPixels - consumedScroll) < 1.0f) {
                skipScroll = true
            }
        }

        val remaining = scrollPixels - consumedScroll
        if (abs(remaining) > 0.5f) {
            scrollBy(remaining)
        }
    }

    if (shouldSnapToTarget || currentPage != target) {
        scrollToPage(target)
    }
}

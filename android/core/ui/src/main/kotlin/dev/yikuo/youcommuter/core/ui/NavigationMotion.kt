package dev.yikuo.youcommuter.core.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/** A short shared-axis transition for entering a page and returning to its parent. */
fun <T> AnimatedContentTransitionScope<T>.pageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { direction * it / 8 } +
        fadeIn(tween(180))) togetherWith
        (slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -direction * it / 8 } +
            fadeOut(tween(120))) using null
}

package qdvc.dahdoo.android.app.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

private const val DEPTH_MS = 280
private const val LATERAL_IN_MS = 280
private const val LATERAL_OUT_MS = 220

/*
 * The app's two motion vocabularies (spec A8/B7): a horizontal slide for moving
 * along a hierarchy (list -> detail, Settings pages), and a non-directional swap
 * between bottom-bar tabs.
 *
 * Both snap the size. AnimatedContent's default SizeTransform animates height
 * during the slide, so screens of different heights drift in diagonally.
 *
 * Both are built with ContentTransform's constructor because `using` is a member
 * of AnimatedContentTransitionScope (it does not resolve in a plain function)
 * and `sizeTransform` has an internal setter (Cursed Knowledge: android-ui).
 */

fun hierarchySlide(deeper: Boolean): ContentTransform {
    val enter = if (deeper) {
        slideInHorizontally(tween(DEPTH_MS)) { w -> w } + fadeIn(tween(DEPTH_MS))
    } else {
        slideInHorizontally(tween(DEPTH_MS)) { w -> -w / 4 } + fadeIn(tween(DEPTH_MS))
    }
    val exit = if (deeper) {
        slideOutHorizontally(tween(DEPTH_MS)) { w -> -w / 4 } + fadeOut(tween(DEPTH_MS))
    } else {
        slideOutHorizontally(tween(DEPTH_MS)) { w -> w } + fadeOut(tween(DEPTH_MS))
    }
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        sizeTransform = SizeTransform(clip = false) { _, _ -> snap() },
    )
}

fun lateralSwap(): ContentTransform = ContentTransform(
    targetContentEnter = scaleIn(initialScale = 0.92f, animationSpec = tween(LATERAL_IN_MS)) +
        fadeIn(tween(LATERAL_IN_MS)),
    initialContentExit = slideOutHorizontally(tween(LATERAL_OUT_MS)) { w -> -w / 6 } +
        fadeOut(tween(LATERAL_OUT_MS)),
    sizeTransform = SizeTransform(clip = false) { _, _ -> snap() },
)

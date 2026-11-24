package qdvc.dahdoo.android.app.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The app's tactile vocabulary (spec A9/B14). Everything goes through
 * [View.performHapticFeedback], never `Vibrator`: it honours the system's
 * touch-feedback setting, needs no `VIBRATE` permission, and does nothing on a
 * device without a vibrator. `FLAG_IGNORE_GLOBAL_SETTING` is deliberately not
 * passed.
 *
 * | Constants                      | Sensation                                |
 * | ------------------------------ | ---------------------------------------- |
 * | `CONTEXT_CLICK`, `CLOCK_TICK`  | faint tick                               |
 * | `VIRTUAL_KEY`, `KEYBOARD_TAP`  | normal click -- a standard button press  |
 * | `LONG_PRESS`                   | heavy click                              |
 *
 * Taps take `VIRTUAL_KEY`, not `CONTEXT_CLICK`, which is the platform's
 * faintest effect and makes an app feel broken. Starting and stopping a
 * recording take `confirm`: the user should feel it land without looking.
 * Step, pick-up and drop are unused (no drag gestures yet) but kept so the
 * vocabulary stays enumerable.
 */
class Haptics(private val view: View) {
    fun tap() = perform(HapticFeedbackConstants.VIRTUAL_KEY)
    fun step() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun pickUp() = perform(api30(HapticFeedbackConstants.GESTURE_START, HapticFeedbackConstants.LONG_PRESS))
    fun drop() = perform(api30(HapticFeedbackConstants.GESTURE_END, HapticFeedbackConstants.CONTEXT_CLICK))
    fun confirm() = perform(api30(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.LONG_PRESS))
    fun reject() = perform(api30(HapticFeedbackConstants.REJECT, HapticFeedbackConstants.LONG_PRESS))

    /** GESTURE_START/END, CONFIRM and REJECT are API 30+; minSdk is 26. */
    private fun api30(api30: Int, fallback: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) api30 else fallback

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

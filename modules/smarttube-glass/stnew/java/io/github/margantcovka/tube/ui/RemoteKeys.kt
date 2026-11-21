package io.github.margantcovka.tube.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * OK на пульте: короткое нажатие — [onClick], удержание — [onLongClick] (система помечает повтор
 * клавиши как long press). Свой обработчик вместо clickable, чтобы удержание надёжно работало
 * с D-pad и не срабатывал ещё и клик при отпускании.
 */
fun Modifier.remoteClick(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    onKeyEvent { event ->
        if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) return@onKeyEvent false
        val native = event.nativeKeyEvent
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (native.repeatCount == 0) {
                    pressed = true
                    longFired = false
                } else if (pressed && !longFired && (native.isLongPress || native.repeatCount >= LONG_PRESS_REPEATS)) {
                    longFired = true
                    onLongClick?.invoke() ?: onClick()
                }
                true
            }
            KeyEventType.KeyUp -> {
                if (pressed && !longFired) onClick()
                pressed = false
                true
            }
            else -> false
        }
    }
}

/** Запас, если прошивка не ставит флаг long press: повторы идут примерно раз в 50 мс после ~500 мс. */
private const val LONG_PRESS_REPEATS = 8

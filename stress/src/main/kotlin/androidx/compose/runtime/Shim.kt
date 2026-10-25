package androidx.compose.runtime

import kotlin.reflect.KProperty

/**
 * Stand-in for the three Compose state functions the controllers use, so they run on a plain JVM
 * (the real runtime needs Google's Maven repository). Every write is reported to [StateWrites].
 */
interface MutableState<T> {
    var value: T
}

object StateWrites {
    @Volatile
    var observer: ((Any) -> Unit)? = null
}

private class Box<T>(@Volatile private var v: T) : MutableState<T> {
    override var value: T
        get() = v
        set(x) {
            StateWrites.observer?.invoke(this)
            v = x
        }
}

fun <T> mutableStateOf(value: T): MutableState<T> = Box(value)

operator fun <T> MutableState<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value

operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) {
    this.value = value
}

package androidx.compose.runtime

import kotlin.reflect.KProperty

/**
 * Stand-in for the Compose state functions the controllers use, so they run on a plain JVM
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

/** How a state compares a new value with the old one (only carried here: every write counts). */
interface SnapshotMutationPolicy<T>

private object Referential : SnapshotMutationPolicy<Any?>

@Suppress("UNCHECKED_CAST")
fun <T> referentialEqualityPolicy(): SnapshotMutationPolicy<T> = Referential as SnapshotMutationPolicy<T>

fun <T> mutableStateOf(value: T): MutableState<T> = Box(value)

@Suppress("UNUSED_PARAMETER")
fun <T> mutableStateOf(value: T, policy: SnapshotMutationPolicy<T>): MutableState<T> = Box(value)

operator fun <T> MutableState<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value

operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) {
    this.value = value
}

/** A list whose changes are reported to [StateWrites] (the controllers' page stack). */
class SnapshotStateList<T> private constructor(private val list: MutableList<T>) : MutableList<T> by list {
    constructor() : this(java.util.Collections.synchronizedList(ArrayList<T>()))

    private fun <R> write(block: () -> R): R {
        StateWrites.observer?.invoke(this)
        return block()
    }

    override fun add(element: T): Boolean = write { list.add(element) }
    override fun add(index: Int, element: T) = write { list.add(index, element) }
    override fun addAll(elements: Collection<T>): Boolean = write { list.addAll(elements) }
    override fun addAll(index: Int, elements: Collection<T>): Boolean = write { list.addAll(index, elements) }
    override fun remove(element: T): Boolean = write { list.remove(element) }
    override fun removeAt(index: Int): T = write { list.removeAt(index) }
    override fun removeAll(elements: Collection<T>): Boolean = write { list.removeAll(elements) }
    override fun retainAll(elements: Collection<T>): Boolean = write { list.retainAll(elements) }
    override fun set(index: Int, element: T): T = write { list.set(index, element) }
    override fun clear() = write { list.clear() }
}

fun <T> mutableStateListOf(vararg elements: T): SnapshotStateList<T> = SnapshotStateList<T>().also { it.addAll(elements) }

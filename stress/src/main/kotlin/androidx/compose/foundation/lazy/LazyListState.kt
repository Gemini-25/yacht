package androidx.compose.foundation.lazy

/** Stand-in for the scroll state the controllers keep for each chip row (never read on a plain JVM). */
class LazyListState(val firstVisibleItemIndex: Int = 0, val firstVisibleItemScrollOffset: Int = 0)

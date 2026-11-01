package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yacht.engine.league.ClubBook
import yacht.engine.league.LeagueWorld
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 이적 센터 on request (`lg.marketPages`, UX 6): the national clubs' kept moves and the last
 * window's numbers (what 이적·드래프트 shows; [WorldPages] loaded them before). Read under the lock
 * off the main thread, never in the 300 ms snapshot.
 */
class MarketPages internal constructor(private val host: LeagueHost) {
    private var loaded by mutableStateOf<MarketView?>(null)
    private val loads = PageLoads(host) { loaded = null }

    /** The view loaded last; null before, in a legacy season (its club league's market is in the snapshot) or once the league is replaced. */
    val view: MarketView? get() = loads.current(loaded)

    /** Loads the national clubs' moves into [view]. */
    fun load() = loads.load("market", ::marketOf) { loaded = it }

    private fun marketOf(w: LeagueWorld): MarketView? {
        val b = w.clubs?.takeIf { it.format == ClubBook.AFFILIATION } ?: return null
        return MarketView(w.plan?.number ?: 0, b.history.asReversed(), b.digest)
    }
}

/**
 * How the page loaders ([MarketPages], [StoryPages], [EventPages], [EncPages]) read the league, as
 * [WorldPages] does: under the lock off the main thread, the result handed over on the main
 * thread, the newest request of each kind winning. The first result read from another league
 * than before (a reset, a restore) first calls [clear], also when there is nothing to show, and
 * [current] hides a view of a league that is no longer the host's.
 */
internal class PageLoads(private val host: LeagueHost, private val clear: () -> Unit) {
    /** The league the views were read from. */
    @Volatile
    private var source: LeagueWorld? = null

    /** The newest request per kind: an older one finishing later is dropped. */
    private val latest = ConcurrentHashMap<String, Int>()
    private val requests = AtomicInteger()

    /** [view] when it was read from the league the host has now, else null. */
    fun <T> current(view: T?): T? = view?.takeIf { source === host.world() }

    /**
     * [read] under the lock off the main thread, then [done] on the main thread with its result
     * (nothing when it gives null, or when a newer request of [kind] came meanwhile). An error
     * reaches the app's handler (the crash log); the page keeps waiting.
     */
    fun <T : Any> load(kind: String, read: (LeagueWorld) -> T?, done: (T) -> Unit) {
        val n = requests.incrementAndGet()
        latest[kind] = n
        host.scope.launch(Dispatchers.Default) {
            val (w, v) = synchronized(host.lock) {
                val w = host.world() ?: return@launch
                w to read(w)
            }
            host.scope.launch {
                if (latest[kind] != n || w !== host.world()) return@launch
                latest.remove(kind, n)
                if (w !== source) {
                    source = w
                    clear()
                }
                v?.let(done)
            }
        }
    }
}

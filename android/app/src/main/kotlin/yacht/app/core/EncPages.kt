package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import yacht.engine.league.EncPage
import yacht.engine.league.LeagueWorld

/**
 * The encyclopedia's pages and its search on request (`lg.encPages`, UX 10), read under the lock
 * off the main thread ([PageLoads]), never in the 300 ms snapshot; pages are kept by their page's
 * load key (at most [WorldPages.KEEP]). The readers are P22's (over the engine's encyclopedia):
 * until then they give nothing, and the pages wait.
 */
class EncPages internal constructor(private val host: LeagueHost) {
    private var pages by mutableStateOf<Map<String, EncPage>>(emptyMap(), referentialEqualityPolicy())
    private var found by mutableStateOf<EncSearchView?>(null)
    private val loads = PageLoads(host) {
        pages = emptyMap()
        found = null
    }

    fun page(p: Page.Encyclopedia): EncPage? = loads.current(pages[p.loadKey])

    /** The results of the last search (an empty query: 둘러보기); null before, or once the league is replaced. */
    val results: EncSearchView? get() = loads.current(found)

    /** Loads the page of [ref] (an encyclopedia reference, "p:g001") into [page]. */
    fun loadPage(ref: String) {
        val key = Page.Encyclopedia(ref).loadKey
        loads.load(key, { w -> encPageOf(w, ref) }) { pages = WorldPages.keep(pages, key, it) }
    }

    /** Searches for [query] into [results] (the newest search wins). */
    fun search(query: String) = loads.load("search", { w -> searchOf(w, query) }) { found = it }

    /** An encyclopedia page (UX 10.1): P22's reader. */
    private fun encPageOf(w: LeagueWorld, ref: String): EncPage? = null

    /** The hits of a query, or 둘러보기 (UX 10.2): P22's reader. */
    private fun searchOf(w: LeagueWorld, query: String): EncSearchView? = null
}

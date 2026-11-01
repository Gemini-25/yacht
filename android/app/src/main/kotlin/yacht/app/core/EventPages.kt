package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import yacht.engine.league.ArcKind
import yacht.engine.league.LeagueWorld

/**
 * 세계 정세, one world event and a World Cup's story on request (`lg.eventPages`, UX 9), read under
 * the lock off the main thread ([PageLoads]), never in the 300 ms snapshot; one arc and one World
 * Cup are kept per page key (at most [WorldPages.KEEP] each). The readers are P18's: until then
 * they give nothing, and the pages wait.
 */
class EventPages internal constructor(private val host: LeagueHost) {
    private var eventsView by mutableStateOf<EventsView?>(null)
    private var arcs by mutableStateOf<Map<String, ArcView>>(emptyMap(), referentialEqualityPolicy())
    private var cups by mutableStateOf<Map<String, WcStoryView>>(emptyMap(), referentialEqualityPolicy())
    private val loads = PageLoads(host) {
        eventsView = null
        arcs = emptyMap()
        cups = emptyMap()
    }

    /** 세계 정세 as loaded last; null before, or once the league is replaced. */
    val events: EventsView? get() = loads.current(eventsView)

    fun arc(page: Page.Event): ArcView? = loads.current(arcs[page.loadKey])
    fun wc(page: Page.WcStory): WcStoryView? = loads.current(cups[page.loadKey])

    /**
     * The id of the arc an article of [kind] published in [season] belongs to (the article's
     * "KIND@season", UX 11), from the last [events]; null when it is not known.
     */
    fun arcId(kind: ArcKind, season: Int): Int? = events?.ids?.get("${kind.name}@$season")

    /** Loads the arcs going on and ended into [events]. */
    fun loadEvents() = loads.load("events", ::eventsOf) { eventsView = it }

    /** Loads arc [id] into [arc]. */
    fun loadEvent(id: Int) = loads.load(Page.Event(id).loadKey, { w -> eventOf(w, id) }) { arcs = WorldPages.keep(arcs, it.key, it) }

    /** Loads the World Cup of [season] (its host vote and draw ceremony) into [wc]. */
    fun loadWc(season: Int) = loads.load(Page.WcStory(season).loadKey, { w -> wcOf(w, season) }) { cups = WorldPages.keep(cups, it.key, it) }

    /** 세계 정세 (UX 9.1): P18's reader. */
    private fun eventsOf(w: LeagueWorld): EventsView? = null

    /** One arc (UX 9.2): P18's reader. */
    private fun eventOf(w: LeagueWorld, id: Int): ArcView? = null

    /** A World Cup's story (UX 9.5): P18's reader. */
    private fun wcOf(w: LeagueWorld, season: Int): WcStoryView? = null
}

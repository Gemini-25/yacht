package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import yacht.engine.league.LeagueWorld

/**
 * 기자단 and 시대와 메타 on request (`lg.storyPages`, UX 7-8), read under the lock off the main thread
 * ([PageLoads]), never in the 300 ms snapshot. The readers are P12's: until then they give nothing,
 * and the pages wait.
 */
class StoryPages internal constructor(private val host: LeagueHost) {
    private var pressView by mutableStateOf<PressView?>(null)
    private var metaView by mutableStateOf<MetaView?>(null)
    private val loads = PageLoads(host) {
        pressView = null
        metaView = null
    }

    /** 기자단 as loaded last ([PressView.league] says whose predictions it holds); null before, or once the league is replaced. */
    val press: PressView? get() = loads.current(pressView)

    /** 시대와 메타's 시대·스타일 as loaded last. */
    val meta: MetaView? get() = loads.current(metaView)

    /** Loads 기자단 with [league]'s predictions and top 5 into [press]. */
    fun loadPress(league: String) = loads.load("press", { w -> pressOf(w, league) }) { pressView = it }

    /** Loads the eras, the style shares and the meta into [meta]. */
    fun loadMeta() = loads.load("meta", ::metaOf) { metaView = it }

    /** 기자단 of [league] (UX 7): P12's reader. */
    private fun pressOf(w: LeagueWorld, league: String): PressView? = null

    /** 시대·스타일 (UX 8.1): P12's reader. */
    private fun metaOf(w: LeagueWorld): MetaView? = null
}

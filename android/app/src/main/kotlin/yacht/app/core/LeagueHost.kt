package yacht.app.core

import kotlinx.coroutines.CoroutineScope
import yacht.engine.OppModel
import yacht.engine.Tables
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.Fixture
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.MatchPreview
import yacht.engine.league.WinEstimate
import java.io.File

/**
 * What the league's helpers ([MatchCenter], [LeagueAnalytics], [WorldPages], [HomeFeed]) reach
 * the controller by. The world and its parts are read under [lock], on [scope]'s background
 * dispatchers; results go back to the main thread through [scope].
 */
internal interface LeagueHost {
    val scope: CoroutineScope
    val lock: Any

    /** The league's folder (its saved files). */
    val folder: File

    /** The league's state; null until the saved league is read. */
    fun world(): LeagueWorld?
    fun tables(): Tables?
    fun extra(): LeagueTables?
    fun estimate(): WinEstimate?

    /** A roster AI, or a legend rebuilt from its genes. */
    fun ai(key: String): LeagueAi?

    /** A player as it was ([spec]: its gene snapshot, as a highlight keeps it). */
    fun aiFrom(key: String, spec: CustomAiSpec): LeagueAi?

    /** A side that plays by its opponent or by time (상대 맞춤, the clone, duel, minimax): its matches cannot be played again. */
    fun adaptive(key: String): Boolean
    fun name(key: String): String

    /** The name with its flag ("🇧🇷 루카스 시우바"). */
    fun shown(key: String): String

    /** [key]'s club badge and league label; null until the national leagues and clubs fill them. */
    fun badge(key: String): ClubBadge?
    fun leagueLabel(key: String): String?
    fun favourites(): Set<String>

    /** The screen's latest snapshot. */
    fun view(): LeagueView?
    fun running(): Boolean

    /** Plays the week in play and stops, without changing the saved run mode. */
    fun startOneWeek()

    /** Saves the league soon (off the main thread). */
    fun requestSave()

    /** [key]'s record as a 상대 맞춤 AI reads it (under the lock), or null. */
    fun oppModel(key: String): OppModel?

    /** League members this season. */
    fun members(): Int

    /** League [id]'s 0-based place in this season's league ranking (by strength before one is published); 0 in a legacy season. Under the lock. */
    fun leagueRank(id: String): Int = 0

    /** The screen's snapshot soon (a result the screen shows was recorded). */
    fun publish() {}

    /** The preview of [a] against [b] ([fixture]: where; odds of their league when kept), or null (under the lock). */
    fun preview(a: String, b: String, fixture: Fixture?): MatchPreview? = null

    /** The watch screen's pace (ui.txt), and a new one to save. */
    fun watchPace(): WatchPace = WatchPace.NORMAL
    fun saveWatchPace(p: WatchPace) {}
}

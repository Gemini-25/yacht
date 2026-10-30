package yacht.app.core

/** The watch screen's own pace (saved in ui.txt); the game screen's [AiPace] is not touched. */
enum class WatchPace(val ms: Long, val label: String) { SLOW(1800L, "느리게"), NORMAL(1100L, "보통"), FAST(500L, "빠르게") }

/**
 * Watching real matches: reports, replays, the match log book and the broadcast channel
 * (`lg.center`). For now only the friendly: [watchFriendly] plays it the way the league screen's
 * 관전 does today ([friendly]: the controller's `startWatch`).
 */
class MatchCenter internal constructor(
    private val host: LeagueHost,
    private val friendly: (a: String, b: String) -> Unit,
) {
    /** A rated friendly of [a] against [b], shown throw by throw on the watch screen. */
    fun watchFriendly(a: String, b: String) = friendly(a, b)

    /** Binary files saved with the league's text files, by name (none yet; later the match log book). */
    internal fun files(): Map<String, ByteArray> = emptyMap()

    /** Forgets what belongs to a league being replaced (a reset, a seed, a restore). */
    internal fun clear() {}

    companion object {
        /** The match log book's file (league/matchlogs.bin); deleted with the league. */
        const val LOGS = "matchlogs.bin"
    }
}

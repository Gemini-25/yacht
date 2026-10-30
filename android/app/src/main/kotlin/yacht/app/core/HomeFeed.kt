package yacht.app.core

/**
 * The league's home dashboard (`lg.home`): built on request and refreshed on events (a new
 * week, a followed player's result, a favourite changed), never in the screen's snapshot. Empty
 * for now.
 */
class HomeFeed internal constructor(private val host: LeagueHost)

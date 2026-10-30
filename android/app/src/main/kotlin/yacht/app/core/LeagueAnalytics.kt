package yacht.app.core

/**
 * The league's analytics on request (`lg.analytics`): player pages, leaderboards, season
 * reviews, match lists and aggregates, each loader keeping its last results by key. Empty for now.
 */
class LeagueAnalytics internal constructor(private val host: LeagueHost)

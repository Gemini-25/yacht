package yacht.app.core

/**
 * The world's pages on request (`lg.worldPages`): competitions, clubs, leagues, the league
 * ranking, the federation and the transfer market, each loader keeping its last results by key.
 * Empty for now.
 */
class WorldPages internal constructor(private val host: LeagueHost)

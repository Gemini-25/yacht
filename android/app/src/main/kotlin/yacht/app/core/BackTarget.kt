package yacht.app.core

/** What the system back button does. */
sealed interface BackAction {
    /** The app goes to the background (it is not closed: a league may keep going). */
    data object ToBackground : BackAction

    /** Taken care of inside the screen (a sheet, a page or a tab of the league). */
    data object Handled : BackAction

    /** To another screen. */
    data class Go(val screen: Screen) : BackAction
}

/**
 * Back from [screen]: inside the league the sheet, then the top page, then the 홈 tab go first
 * ([LeagueNav.back]); a game started from the league goes back to it ([rankedFrom]); watching
 * stops ([stopWatch]) and lands on the league with its pages kept.
 */
fun backTarget(screen: Screen, nav: LeagueNav, rankedFrom: Screen?, stopWatch: () -> Unit): BackAction = when (screen) {
    Screen.HOME -> BackAction.ToBackground
    Screen.LEAGUE -> if (nav.back()) BackAction.Handled else BackAction.Go(Screen.HOME)
    Screen.WATCH -> {
        stopWatch()
        BackAction.Go(Screen.LEAGUE)
    }
    Screen.BUILDER, Screen.MAKER -> BackAction.Go(Screen.LEAGUE)
    Screen.REVIEW -> BackAction.Go(Screen.HISTORY)
    Screen.GAME -> BackAction.Go(rankedFrom ?: Screen.HOME)
    else -> BackAction.Go(Screen.HOME)
}

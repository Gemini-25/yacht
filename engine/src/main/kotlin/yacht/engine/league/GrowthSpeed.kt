package yacht.engine.league

/**
 * Growth speed (design ENG 1b.11): the one club-world nudge to how players develop. At a close each
 * player's nudge is `m = m_coach + m_t + m_a`, from its club's coach (development skill 1..5, `0.03 ×
 * (dev − 3)`), the club's training level (`0.02 × (training − 3)`) and, for an own academy graduate's
 * first [GRADUATE_SEASONS] seasons, the academy level (`0.02 × (academy − 3)`). It is made **zero-sum
 * per play nation** (members abroad count with their club's nation): with `g` a player's expected
 * growth points this season (`Σ max(0, cap − x) × growthRate(age)` over the abilities still before
 * their peak), `m̄ = Σ g m / Σ g` and `speed = 1 + clamp(m − m̄, −[CLAMP], +[CLAMP])`. So before the
 * clamp `Σ g (speed − 1) = 0`: each nation's expected growth points a season are what they were, the
 * ceilings and the decline never move, and a well-run club's players only reach their ceilings a
 * little sooner. [Profiles.grow] multiplies only its growth term by the speed; 1.0 is today's growth.
 *
 * Read at the close from the club book as it was then (its off-season runs at the open), so a close
 * replayed after a kill reads the same. A book without a club world gives every player 1.0; the
 * caller passes `{ 1.0 }` to [Evolution.run] while [LeagueConfig.clubWorld] is off (the club world
 * is frozen then).
 */
object GrowthSpeed {
    /** The nudge per level above or below 3 of the coach's development skill, the training and the academy. */
    const val COACH = 0.03
    const val TRAINING = 0.02
    const val ACADEMY = 0.02

    /** The level that nudges nothing. */
    const val NEUTRAL = 3

    /** Seasons of an own graduate (its first season included) that its academy's level nudges. */
    const val GRADUATE_SEASONS = 3

    /** The most a speed moves from 1. */
    const val CLAMP = 0.12

    /** A player's nudge from its coach's development skill [dev], the [training] level and its academy's level [academy] (each null: none). */
    fun nudge(dev: Int?, training: Int?, academy: Int?): Double =
        (dev?.let { COACH * (it - NEUTRAL) } ?: 0.0) + (training?.let { TRAINING * (it - NEUTRAL) } ?: 0.0) + (academy?.let { ACADEMY * (it - NEUTRAL) } ?: 0.0)

    /**
     * Player [key]'s expected growth points in the season after one at [age] ([Profiles.grow]'s
     * deterministic growth term): `max(0, cap − x) × growthRate(age)` over its abilities still before
     * their peak; 0 for a player past every peak.
     */
    fun growth(identity: Identity, key: String, age: Int): Double {
        val off = Profiles.peakOffset(key)
        val xs = identity.abilities.toList()
        var g = 0.0
        for (i in 0 until 4) {
            if (age >= Profiles.PEAK[i] + off) continue
            g += (Profiles.ceiling(identity.potential, key, i) - xs[i]).coerceAtLeast(0) * Profiles.growthRate(age)
        }
        return g
    }

    /** One player's inputs: its play [nation], [nudge] and expected [growth] points. */
    data class Entry(val key: String, val nation: String, val nudge: Double, val growth: Double)

    /**
     * Each player's growth speed (1.0 for a key it has none of), with what the calibration reads:
     * [clamped] speeds the clamp bound, and per nation the growth-weighted mean nudge [means] and the
     * sum of growth points [growth].
     */
    class Speeds internal constructor(
        private val speeds: Map<String, Double>,
        val clamped: Int,
        val means: Map<String, Double>,
        val growth: Map<String, Double>,
    ) : (String) -> Double {
        override fun invoke(key: String): Double = speeds[key] ?: 1.0

        /** Every player given a speed, by key. */
        val keys: Set<String> get() = speeds.keys

        companion object {
            /** No club world: everyone grows as before. */
            val NONE = Speeds(emptyMap(), 0, emptyMap(), emptyMap())
        }
    }

    /**
     * The speeds of [entries] (keys distinct): per nation `1 + clamp(m − m̄, ±CLAMP)` with `m̄` the
     * growth-weighted mean nudge (the plain mean in a nation where nobody grows). The sums run in key
     * order, so the result is the same however the entries come.
     */
    fun of(entries: Collection<Entry>): Speeds {
        val byNation = java.util.TreeMap<String, MutableList<Entry>>()
        for (e in entries.sortedBy { it.key }) byNation.getOrPut(e.nation) { ArrayList() } += e
        val out = HashMap<String, Double>()
        val means = LinkedHashMap<String, Double>()
        val growth = LinkedHashMap<String, Double>()
        var clamped = 0
        for ((nation, es) in byNation) {
            var g = 0.0
            var gm = 0.0
            for (e in es) {
                val w = maxOf(0.0, e.growth)
                g += w
                gm += w * e.nudge
            }
            val mean = if (g > 0) gm / g else es.sumOf { it.nudge } / es.size
            means[nation] = mean
            growth[nation] = g
            for (e in es) {
                val d = e.nudge - mean
                if (d > CLAMP || d < -CLAMP) clamped++
                out[e.key] = 1 + d.coerceIn(-CLAMP, CLAMP)
            }
        }
        return Speeds(out, clamped, means, growth)
    }

    /**
     * The speeds of season [season]'s close: every player of [players] that played it and ages
     * (not one kept in its prime), with its club's coach, training and academy in [book]'s club
     * world as they are at the close. A player without a club nudges nothing and counts with its own
     * nation. [Speeds.NONE] for a book without a club world (or a club league's book).
     */
    fun of(players: PlayerBook, season: Int, book: ClubBook): Speeds {
        val w = book.world ?: return Speeds.NONE
        if (book.format != ClubBook.AFFILIATION) return Speeds.NONE
        val entries = ArrayList<Entry>()
        for (p in players.alive) {
            if (p.keep || p.born > season) continue
            val id = p.identity ?: continue
            val club = book.clubOf(p.key)
            val st = club?.let { w.clubs[it] }
            val nation = club?.let { book.nationOf(it) } ?: id.nation
            val terms = book.terms(p.key)
            val graduate = terms?.academy != null && terms.academy == club && season - terms.gradSeason in 0 until GRADUATE_SEASONS
            val nudge = nudge(club?.let { book.coachOf(it)?.dev }, st?.training, if (graduate) st?.academy else null)
            entries += Entry(p.key, nation, nudge, growth(id, p.key, id.age(season, p.born)))
        }
        return of(entries)
    }
}

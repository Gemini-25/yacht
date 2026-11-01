package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random
import java.util.TreeMap
import java.util.TreeSet

/** A coach's philosophy (지도 철학, design ENG 2.1): who its club signs; [family] the style family it favours (none for 유망주 육성형 and 스타 영입형). [code] is saved. */
enum class Philosophy(val code: Char, val label: String, val desc: String, val family: StyleFamily?) {
    ATTACK('A', "공격형", "요트와 큰 족보를 노리는 과감한 선수를 좋아해요.", StyleFamily.YACHT),
    BONUS('B', "보너스형", "위쪽 칸 보너스를 꼬박꼬박 챙기는 선수를 모아요.", StyleFamily.BONUS),
    YOUTH('Y', "유망주 육성형", "어린 선수를 키워 쓰는 걸 좋아해요. 선수들이 조금 더 빨리 성장해요.", null),
    STABLE('S', "안정형", "흔들리지 않는 침착한 선수와 실수 없는 운영을 좋아해요.", StyleFamily.CAUTION),
    COMBO('C', "족보형", "포카드·풀하우스·스트레이트에 강한 선수를 찾아요.", StyleFamily.COMBO),
    PRAGMATIC('P', "실리형", "기대 점수를 끝까지 계산하는 정석파를 몸값 대비 효율로 골라요.", StyleFamily.EV),
    STAR('X', "스타 영입형", "이름값 있는 최고의 선수를 데려오는 걸 좋아해요. 돈이 많이 들어요.", null),
    ;

    /** The style families of the players it fits (the hiring score's fit, ENG 2.4): 공격형 takes 과감파 too; none for 유망주 육성형 and 스타 영입형. */
    val families: Set<StyleFamily>
        get() = if (this == ATTACK) setOf(StyleFamily.YACHT, StyleFamily.BOLD) else setOfNotNull(family)

    /** Only a 재벌 or 국부 펀드 owner hires it (스타 영입형). */
    val needsRichOwner: Boolean get() = this == STAR

    companion object {
        fun of(code: Char): Philosophy? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A coach's spell at [club] from season [from] to [to] (0 = now): its [best] club-table place,
 * [titles] club-table titles, and how it ended ([end]: S sacked, P poached, L left at the end of
 * its contract, R retired, '-' going on).
 */
data class Tenure(val club: String, val from: Int, val to: Int, val best: Int, val titles: Int, val end: Char) {
    /** Its length in seasons, a spell going on counted through season [now]. */
    fun seasons(now: Int): Int = maxOf(0, (if (to == 0) now else to) - from + 1)
}

/**
 * A coach (감독, design ENG 2.1): id "k<n>" (never reused), its identity (age = season − [born]),
 * philosophy, development skill [dev] 1..5 and [reputation] 0..100; its club (null: free) since
 * [since] on a contract to [until] at [wage] (all 0 while free); the player it was ([player], an
 * ex-player coach); seasons in charge, club-table titles, members' league titles, sackings, its
 * newest 5 [tenures] (newest first; older ones are summed into the counts) and its 올해의 감독
 * [awards]. Appended by the coach market (P5, each read with its default from an older line):
 * [cups] (members' 챔피언스 리그 and continental titles and the club's 클럽 대항전 cups while in
 * charge), [spells] in its career, its [longest] spell in seasons, the last off-season the market
 * ran with it ([done], the step's guard) and its [moves] in that off-season (the step's news is
 * rebuilt from them).
 */
data class Coach(
    val id: String,
    val name: String, val nation: String, val gender: Gender, val born: Int,
    val philosophy: Philosophy, val dev: Int, val reputation: Int,
    val club: String?, val since: Int, val until: Int, val wage: Int,
    val player: String?,
    val seasons: Int, val clubTitles: Int, val leagueTitles: Int, val sacked: Int,
    val tenures: List<Tenure>,
    val awards: Int,
    val cups: Int = 0,
    val spells: Int = tenures.size,
    val longest: Int = 0,
    val done: Int = 0,
    val moves: List<CoachMove> = emptyList(),
) {
    fun age(season: Int): Int = season - born

    /** The spell going on at its club, null while free. */
    val current: Tenure? get() = club?.let { c -> tenures.firstOrNull { it.club == c && it.to == 0 } }

    /** Its longest spell through season [now], the one going on included. */
    fun longestBy(now: Int): Int = maxOf(longest, tenures.maxOfOrNull { it.seasons(now) } ?: 0)
}

/** A retired coach kept for its honours (≤ 100): [summary] the space-free counts ("CT:3,L0:5,COY:1,clubs:4"). */
data class RetiredCoach(
    val id: String, val name: String, val nation: String, val gender: Gender, val born: Int, val retired: Int,
    val philosophy: Philosophy, val reputation: Int, val player: String?, val summary: String,
) {
    /**
     * One count of its [summary] by key ([CoachMarket.SUMMARY_KEYS]: CT club-table titles, L0 members'
     * league titles, IC international titles, COY 올해의 감독, clubs (spells), seasons, sacked,
     * longest); 0 when it has none (the market writes only the counts that are not 0).
     */
    fun count(key: String): Int =
        summary.split(',').firstOrNull { it.substringBefore(':') == key }?.substringAfter(':', "")?.toIntOrNull() ?: 0
}

/**
 * What the board expects of a club through a closed week (design ENG 2.2), as the screens show it:
 * the [expected] club-table place from the season's drawn ratings, the owner's [target] (clamped to
 * 1..[clubs]), the [place] now, `gap = (target − place) / clubs` (positive = better than asked), the
 * coach's [pressure] and sacking [threshold], and the pressure the next checkpoint would leave at
 * this gap ([projected]; 0 for the leader after the winter checkpoint, as a club-table title at the
 * close resets it). Recomputed on request, never stored (P5's `CoachMarket.outlook`).
 */
data class BoardOutlook(
    val expected: Int, val target: Int, val place: Int, val clubs: Int,
    val gap: Double, val pressure: Double, val threshold: Double, val projected: Double,
)

/**
 * A coach's move in an off-season (design ENG 2.3-2.4), kept on its `k` line until the next one:
 * [kind] S 경질 (sacked by [club] at the close: its club-table [place], the board's [target]), L
 * 계약 만료 (left [club]), P 영입 (poached by [club] from [from] for [fee], the compensation), H 선임
 * (hired by [club]; [from] the club it led last, if any), C 선수에서 감독으로 ([from] the player it
 * was), R 은퇴 (retired; rebuilt from the `kr` line).
 */
data class CoachMove(val kind: Char, val coach: String, val club: String?, val from: String?, val place: Int = 0, val target: Int = 0, val fee: Int = 0)

/**
 * The coach market's off-season of [season] (step 4, [CoachMarket.offseason]), rebuilt from the
 * book: every coach's [moves] of that off-season and the coaches who retired after it and are kept.
 */
data class CoachReport(val season: Int, val moves: List<CoachMove>, val retired: List<RetiredCoach>) {
    fun of(kind: Char): List<CoachMove> = moves.filter { it.kind == kind }

    /**
     * The news (design ENG 11.2; templates are News.kt's): `coach_sacked` [club, coach] (place,
     * target, far: 1 when the place is 2 or more below the target) for the 2 biggest clubs (by fans),
     * `coach_poached` [club, coach, from] (fee) for 1, `coach_hired` [club, coach, from?] (philosophy
     * code) for 2, `coach_retired` [coach] (seasons, titles) for the kept retiree with the most titles,
     * `player_to_coach` [player, coach] (its style family's code, or "-" for a player whose genes the
     * roster no longer keeps, i.e. any but a legend; philosophy code) for the best reputation, and one
     * `coach_digest` [≤ 6 clubs] (sacked, hired, poached, clubs with a new coach) when there were more
     * moves than told. Clubs rank by fans in [book], then id.
     */
    fun events(book: ClubBook, family: (String) -> StyleFamily? = { null }): List<LeagueEvent> {
        val w = book.world ?: return emptyList()
        val out = ArrayList<LeagueEvent>()
        fun event(kind: String, keys: List<String>, vararg args: String) {
            out += LeagueEvent(kind, season, keys, args.toList(), Comp.CLUB)
        }
        fun fans(club: String?) = club?.let { w.clubs[it]?.fans } ?: 0
        val order = compareByDescending<CoachMove> { fans(it.club) }.thenComparing({ it.club.orEmpty() }, ClubBook.ID_ORDER)
            .thenComparing({ it.coach }, CoachBook.ID_ORDER)
        fun coach(id: String) = w.coaches.coach(id)
        val sacked = of('S').sortedWith(order)
        val poached = of('P').sortedWith(order)
        val hired = of('H').sortedWith(order)
        for (m in sacked.take(SACKED)) {
            event("coach_sacked", listOf(m.club ?: "-", m.coach), m.place.toString(), m.target.toString(), LineParts.bit(m.place - m.target >= FAR))
        }
        for (m in poached.take(POACHED)) event("coach_poached", listOf(m.club ?: "-", m.coach, m.from ?: "-"), m.fee.toString())
        for (m in hired.take(HIRED)) {
            event("coach_hired", listOfNotNull(m.club ?: "-", m.coach, m.from), coach(m.coach)?.philosophy?.code?.toString() ?: "-")
        }
        retired.sortedWith(
            compareByDescending<RetiredCoach> { it.count("CT") + it.count("L0") + it.count("IC") }.thenByDescending { it.count("seasons") }
                .thenComparing({ it.id }, CoachBook.ID_ORDER),
        ).firstOrNull()?.let { r ->
            event("coach_retired", listOf(r.id), r.count("seasons").toString(), (r.count("CT") + r.count("L0") + r.count("IC")).toString())
        }
        of('C').mapNotNull { m -> coach(m.coach)?.let { m to it } }
            .sortedWith(compareByDescending<Pair<CoachMove, Coach>> { it.second.reputation }.thenComparing({ it.second.id }, CoachBook.ID_ORDER))
            .firstOrNull()?.let { (m, c) ->
                event("player_to_coach", listOf(m.from ?: "-", c.id), m.from?.let(family)?.code?.toString() ?: "-", c.philosophy.code.toString())
            }
        if (sacked.size > SACKED || poached.size > POACHED || hired.size > HIRED) {
            val clubs = (sacked + poached + hired).sortedWith(order).mapNotNull { it.club }.distinct()
            event(
                "coach_digest", clubs.take(DIGEST_CLUBS),
                sacked.size.toString(), (hired.size + poached.size).toString(), poached.size.toString(), clubs.size.toString(),
            )
        }
        return out
    }

    companion object {
        /** Singles told a season of each kind (the rest go into the digest). */
        const val SACKED = 2
        const val POACHED = 1
        const val HIRED = 2

        /** Places below the target from which a sacking reads "한참". */
        const val FAR = 2

        const val DIGEST_CLUBS = 6
    }
}

/**
 * A player who retired at a close (design ENG 1b.8), as the coach market reads it: [age] in its last
 * season [retired], seasons played, major titles, [peak] the share of the world's players whose best
 * rating beats its own (0 = the best), seasons as a captain and its style family (null: unknown).
 */
data class Retiree(
    val key: String, val name: String, val nation: String, val gender: Gender?, val age: Int, val retired: Int,
    val seasons: Int, val majors: Int, val peak: Double, val captain: Int = 0, val family: StyleFamily? = null,
)

/**
 * What the coach market reads about the players of the league (ENG 2.4): this close's [retirees]
 * (some become coaches), the names players use ([names]: a generated coach's name is unique against
 * them and every coach's), the closing season's 올해의 감독 ([coachOfYear], a coach id from the
 * press vote's tally; its [Coach.awards] is raised in the off-season, ENG 2.5) and the members'
 * market values ([value]; null: from [ClubInput.rating] and [ClubInput.age] at the book's anchor),
 * which rank the clubs that hire.
 */
class CoachInput(
    val retirees: List<Retiree> = emptyList(),
    val names: Set<String> = emptySet(),
    val coachOfYear: String? = null,
    val value: ((String) -> Int)? = null,
) {
    companion object {
        /**
         * The players of [players] who retired at the close of [season] (legends included, with their
         * genes' style family), by key: [majors] gives each one's major titles, [captain] its seasons as
         * a captain. The peak's share is against the alive players' best ratings and theirs.
         */
        fun retirees(players: PlayerBook, season: Int, majors: (String) -> Int, captain: (String) -> Int = { 0 }): List<Retiree> {
            val legends = players.legends.associateBy { it.key }
            val gone = (players.retired + players.legends.map { it.record }).filter { it.retired == season }.distinctBy { it.key }
            val peaks = (players.alive.map { it.peak } + gone.map { it.bestRating }).filter { it.isFinite() }.sorted()
            return gone.map { r -> retiree(r, legends[r.key], peaks, majors(r.key), captain(r.key)) }.sortedBy { it.key }
        }

        /** The legends of [players] who retired in the [LEGEND_SEASONS] seasons up to [season] and are [LEGEND_AGE] or younger in [season] (the migration's ex-player coaches, ENG 8.2). */
        fun legends(players: PlayerBook, season: Int, majors: (String) -> Int): List<Retiree> {
            val peaks = (players.alive.map { it.peak } + players.legends.map { it.record.bestRating }).filter { it.isFinite() }.sorted()
            return players.legends
                .filter { it.record.retired in season - LEGEND_SEASONS..season && it.record.retiredAge > 0 && it.record.retiredAge + (season - it.record.retired) <= LEGEND_AGE }
                .map { retiree(it.record, it, peaks, majors(it.key), 0) }.sortedBy { it.key }
        }

        const val LEGEND_SEASONS = 15
        const val LEGEND_AGE = 60

        private fun retiree(r: RetiredRecord, legend: Legend?, peaks: List<Double>, majors: Int, captain: Int): Retiree {
            val better = if (r.bestRating.isFinite()) peaks.size - upper(peaks, r.bestRating) else peaks.size
            val share = if (peaks.isEmpty()) 1.0 else better.toDouble() / peaks.size
            return Retiree(r.key, r.name, r.nation, r.gender, r.retiredAge, r.retired, r.seasons, majors, share, captain, legend?.spec?.play?.let { StyleFamily.of(it) })
        }

        /** The index of the first value above [x] in the sorted [xs]. */
        private fun upper(xs: List<Double>, x: Double): Int {
            var lo = 0
            var hi = xs.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (xs[mid] <= x) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}

/**
 * The coaches of a club world (design ENG 2.6): every coach with a club or in the free pool (`k`)
 * and the retired coaches kept for their honours (`kr`). The market's logic (hiring, sackings,
 * poaching, retirements, reputation) is [CoachMarket], in this file.
 */
class CoachBook {
    /** Every active coach (employed or free), by id number. */
    internal val active = TreeMap<String, Coach>(ID_ORDER)
    internal val retiredList = ArrayList<RetiredCoach>()

    fun coach(id: String): Coach? = active[id]

    /** Club [club]'s coach, null for none. */
    fun coachOf(club: String): Coach? = active.values.firstOrNull { it.club == club }

    /** Every active coach, by id. */
    val all: List<Coach> get() = active.values.toList()

    /** The free pool: coaches without a club, by id. */
    val free: List<Coach> get() = active.values.filter { it.club == null }

    /** Retired coaches kept, oldest retirement first. */
    val retired: List<RetiredCoach> get() = retiredList.toList()

    /** The last off-season the coach market ran (its closing season; 0 before one): the step's guard. */
    val season: Int get() = active.values.maxOfOrNull { it.done } ?: 0

    /** Every kept spell at club [club] with its coach's id, newest first (then by coach id). */
    fun tenuresAt(club: String): List<Pair<String, Tenure>> =
        active.values.flatMap { c -> c.tenures.filter { it.club == club }.map { c.id to it } }
            .sortedWith(compareByDescending<Pair<String, Tenure>> { it.second.from }.thenComparing({ it.first }, ID_ORDER))

    companion object {
        /** "k1", "k2", ... by number; other ids after them by text. */
        val ID_ORDER: Comparator<String> = compareBy<String> { it.removePrefix("k").toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }

        /** Tenures a coach keeps (older ones live on in its counts). */
        const val TENURES = 5

        /** A space-free text as a token, escaping only what would break the line or the token (Field.unesc reads it back). */
        private fun bare(s: String): String = when {
            s.isEmpty() -> "-"
            s == "-" -> "%2D"
            else -> buildString { for (ch in s) if (ch == '%' || ch == ' ' || ch.code < 0x20) append('%').append(String.format(java.util.Locale.ROOT, "%02X", ch.code)) else append(ch) }
        }

        private fun tenure(t: Tenure) = "${t.club}:${t.from}:${t.to}:${t.best}:${t.titles}:${t.end}"

        private fun move(m: CoachMove) = "${m.kind}:${LineParts.opt(m.club)}:${LineParts.opt(m.from)}:${m.place}:${m.target}:${m.fee}"

        private fun moveOf(coach: String, e: String): CoachMove? {
            val p = e.split(':')
            if (p.size < 6) return null
            return CoachMove(p[0].singleOrNull() ?: return null, coach, Field.opt(p[1]), Field.opt(p[2]), p[3].toInt(), p[4].toInt(), p[5].toInt())
        }

        private val K = LineParts.of<ClubWorld>("k", { w, out ->
            for (c in w.coaches.active.values) {
                out.append("k ${c.id} ${Field.esc(c.name)} ${c.nation} ${c.gender.code} ${c.born} ${c.philosophy.code} ${c.dev} ${c.reputation} ")
                out.append("${c.club ?: "-"} ${c.since} ${c.until} ${c.wage} ${LineParts.opt(c.player)} ${c.seasons} ${c.clubTitles} ${c.leagueTitles} ${c.sacked} ${c.awards} ")
                out.append(LineParts.list(c.tenures, ';', ::tenure))
                out.append(" ${c.cups} ${c.spells} ${c.longest} ${c.done} ").append(LineParts.list(c.moves, ';', ::move)).append('\n')
            }
        }) { w, f ->
            require(f.size >= 20)
            val tenures = LineParts.items(f[19], ';').map { e ->
                val p = e.split(':')
                Tenure(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].single())
            }
            fun int(i: Int, default: Int) = f.getOrNull(i)?.toIntOrNull() ?: default
            val c = Coach(
                f[1], Field.unesc(f[2]), f[3], Gender.of(f[4].single()) ?: Gender.MALE, f[5].toInt(),
                Philosophy.of(f[6].single()) ?: return@of false, f[7].toInt(), f[8].toInt(),
                f[9].takeIf { it != "-" }, f[10].toInt(), f[11].toInt(), f[12].toInt(), Field.opt(f[13]),
                f[14].toInt(), f[15].toInt(), f[16].toInt(), f[17].toInt(), tenures, f[18].toInt(),
                int(20, 0), int(21, tenures.size), int(22, 0), int(23, 0),
                f.getOrNull(24)?.let { t -> LineParts.items(t, ';').mapNotNull { runCatching { moveOf(f[1], it) }.getOrNull() } }.orEmpty(),
            )
            w.coaches.active[c.id] = c
            true
        }

        private val KR = LineParts.of<ClubWorld>("kr", { w, out ->
            for (c in w.coaches.retiredList) {
                out.append("kr ${c.id} ${Field.esc(c.name)} ${c.nation} ${c.gender.code} ${c.born} ${c.retired} ${c.philosophy.code} ${c.reputation} ")
                out.append("${LineParts.opt(c.player)} ${bare(c.summary)}\n")
            }
        }) { w, f ->
            require(f.size >= 11)
            w.coaches.retiredList += RetiredCoach(
                f[1], Field.unesc(f[2]), f[3], Gender.of(f[4].single()) ?: Gender.MALE, f[5].toInt(), f[6].toInt(),
                Philosophy.of(f[7].single()) ?: return@of false, f[8].toInt(), Field.opt(f[9]), Field.opt(f[10]).orEmpty(),
            )
            true
        }

        /** `k kr`. */
        val PARTS: List<LinePart<ClubWorld>> = listOf(K, KR)
    }
}

/**
 * Making coaches (design ENG 2.1, 1b.8): generated ones for the free pool and the hiring clubs, and
 * retired players with a career who become coaches.
 */
object CoachMaker {
    /** A generated coach's philosophy weights (스타 영입형 is hired only by 재벌 and 국부 펀드 owners). */
    val WEIGHTS: List<Pair<Philosophy, Double>> = listOf(
        Philosophy.ATTACK to 18.0, Philosophy.BONUS to 16.0, Philosophy.YOUTH to 14.0, Philosophy.STABLE to 16.0,
        Philosophy.COMBO to 14.0, Philosophy.PRAGMATIC to 16.0, Philosophy.STAR to 6.0,
    )

    /** A generated coach's age range when it is made. */
    const val MIN_AGE = 38
    const val MAX_AGE = 58

    /** A retiring player becomes a coach with [EX_CHANCE] after ≥ [EX_SEASONS] seasons, aged ≥ [EX_AGE], with a major title, a peak in the world's top [EX_TOP] or ≥ [EX_CAPTAIN] seasons as a captain. */
    const val EX_SEASONS = 8
    const val EX_AGE = 32
    const val EX_TOP = 0.10
    const val EX_CAPTAIN = 3
    const val EX_CHANCE = 0.35

    /** A peak in the world's top 5% adds 10 to an ex-player coach's reputation. */
    const val EX_STAR = 0.05

    private const val EX_SALT = 0xC0ACL

    /** A philosophy drawn by [weights]. */
    fun pick(rng: Random, weights: List<Pair<Philosophy, Double>> = WEIGHTS): Philosophy {
        val total = weights.sumOf { maxOf(0.0, it.second) }
        if (total <= 0) return weights.firstOrNull()?.first ?: Philosophy.PRAGMATIC
        var x = rng.nextDouble() * total
        for ((p, w) in weights) {
            x -= maxOf(0.0, w)
            if (x < 0) return p
        }
        return weights.last { it.second > 0 }.first
    }

    /**
     * A generated coach [id] of [nation] for season [season] (it is [MIN_AGE]..[MAX_AGE] then):
     * gender 50/50, a name of its nation unique against [taken], its [philosophy] (drawn by
     * [WEIGHTS] when null), development skill 4-5 for 유망주 육성형 and 1-4 for the others,
     * reputation `20 + (age − 38) + U(0, 25)`; free.
     */
    fun make(id: String, nation: String, season: Int, rng: Random, taken: Set<String>, philosophy: Philosophy? = null): Coach {
        val n = Nations.of(nation) ?: Nations.KR
        val gender = if (rng.nextBoolean()) Gender.MALE else Gender.FEMALE
        val age = MIN_AGE + rng.nextInt(MAX_AGE - MIN_AGE + 1)
        val phil = philosophy ?: pick(rng)
        val dev = if (phil == Philosophy.YOUTH) 4 + rng.nextInt(2) else 1 + rng.nextInt(4)
        val reputation = (20 + (age - MIN_AGE) + rng.nextInt(26)).coerceIn(0, 100)
        val name = PlayerNames.unique(n, gender, age, rng, taken)
        return Coach(id, name, n.code, gender, season - age, phil, dev, reputation, null, 0, 0, 0, null, 0, 0, 0, 0, emptyList(), 0)
    }

    /** Whether retiree [r] has the career of a coach (ENG 1b.8), before the draw. */
    fun eligible(r: Retiree): Boolean =
        r.seasons >= EX_SEASONS && r.age >= EX_AGE && (r.majors > 0 || r.peak < EX_TOP || r.captain >= EX_CAPTAIN)

    /** Whether retiree [r] becomes a coach: [eligible] and the draw `Random(mix(seed, 0xC0AC, season, key))` < [EX_CHANCE]. */
    fun becomes(r: Retiree, seed: Long): Boolean =
        eligible(r) && Random(DiceStream.mix(seed, EX_SALT, r.retired.toLong(), r.key.hashCode().toLong())).nextDouble() < EX_CHANCE

    /** The philosophy a player's style family turns into (요트·과감 공격형, 보너스 보너스형, 신중 안정형, 족보 족보형, 정석 실리형); null for the others (drawn). */
    fun philosophyOf(family: StyleFamily?): Philosophy? = when (family) {
        StyleFamily.YACHT, StyleFamily.BOLD -> Philosophy.ATTACK
        StyleFamily.BONUS -> Philosophy.BONUS
        StyleFamily.CAUTION -> Philosophy.STABLE
        StyleFamily.COMBO -> Philosophy.COMBO
        StyleFamily.EV -> Philosophy.PRAGMATIC
        else -> null
    }

    /**
     * Retiree [r] as coach [id]: its name, nation and gender (drawn when unknown), `born` from its
     * age, the philosophy of its style family, development skill 2-5 and reputation `30 + 10 × min(3,
     * majors) + 10 × [a peak in the top 5%]`; free.
     */
    fun fromPlayer(id: String, r: Retiree, rng: Random): Coach {
        val phil = philosophyOf(r.family) ?: pick(rng)
        val gender = r.gender ?: if (rng.nextBoolean()) Gender.MALE else Gender.FEMALE
        val dev = 2 + rng.nextInt(4)
        val reputation = (30 + 10 * minOf(3, maxOf(0, r.majors)) + if (r.peak < EX_STAR) 10 else 0).coerceIn(0, 100)
        return Coach(id, r.name, Nations.of(r.nation)?.code ?: Nations.KR.code, gender, r.retired - r.age, phil, dev, reputation, null, 0, 0, 0, r.key, 0, 0, 0, 0, emptyList(), 0)
    }
}

/** A coach record of the coach book (ENG 2.5, on request): [kind], the holder (a coach id, [retired] or not) and the [value]. */
data class CoachRecord(val kind: CoachRecordKind, val coach: String, val value: Int, val retired: Boolean)

enum class CoachRecordKind(val label: String) {
    TENURE("최장 재임"), CLUB_TITLES("최다 클럽 순위 우승"), CLUBS("최다 클럽"), SACKED("최다 경질"), AWARDS("올해의 감독 최다"),
}

/**
 * The coach market (design ENG 2.2-2.5): board expectations and the pressure on coaches, sackings at
 * the winter checkpoint and the close, the off-season's job market (retirements, ex-player coaches,
 * contracts, hiring and poaching), reputation and honours, the founding of a club world's coaches,
 * and the records. Every function works on a [ClubBook] with a club world and does nothing without
 * one; every change the screens show bumps [ClubBook.version]. Randomness comes from the plan's seed
 * by coach or club number, and every loop runs in id order, so a step asked again with the same book
 * decides the same.
 */
object CoachMarket {
    // ---- the board (2.2, 2.3) -------------------------------------------------------------------

    /** An owner's ambition moves the target by this many places a point above or below 3. */
    const val AMBITION = 0.5

    /** The sacking threshold `0.8 × patience + 0.4`. */
    const val PATIENCE_SLOPE = 0.8
    const val PATIENCE_BASE = 0.4

    /** A coach at its first checkpoint is sacked only at this many times the threshold. */
    const val FIRST_CHECKPOINT = 2.0

    /** Pressure a checkpoint adds per unit of gap below the target. */
    const val MISS = 2.0

    /** Fans' mood below which a checkpoint adds [MOOD_PRESSURE]. */
    const val MOOD_LOW = 35
    const val MOOD_PRESSURE = 0.5

    /** A contract is renewed when the pressure is below this share of the threshold. */
    const val RENEW = 0.5

    // ---- the job market (2.4) -------------------------------------------------------------------

    const val RETIRE_FROM = 62
    const val RETIRE_BY = 72
    const val RETIRE_BASE = 0.15
    const val RETIRE_STEP = 0.10

    /** The free pool holds at least `max(8, ⌈0.25 × clubs⌉)` coaches. */
    const val POOL_MIN = 8

    /** A coach is poached from a club worth ≤ 0.77 × the hirer, or from a league ranked ≥ 3 places lower, and only with reputation ≥ 50. */
    const val POACH_VALUE = 0.77
    const val POACH_RANKS = 3
    const val POACH_REPUTATION = 50

    /** The most of its income a club pays its coach (재벌 and 국부 펀드 owners more). */
    const val WAGE_SHARE = 0.12
    const val RICH_WAGE_SHARE = 0.18

    /** The hiring score: reputation + 25 × fit + 10 × [same nation]. */
    const val FIT = 25.0
    const val SAME_NATION = 10.0

    /** Contract lengths: 2-3 seasons, 2 for a club in 재정 위기 or worse. */
    const val CONTRACT_MIN = 2
    const val CONTRACT_MAX = 3

    /** Retired coaches kept (fewer below [SMALL] members) and the seasons that keep one without titles. */
    const val KEEP_RETIRED = 100
    const val KEEP_RETIRED_SMALL = 40
    const val SMALL = 500
    const val KEEP_SEASONS = 10

    /** A generated coach is of the hiring club's nation this often, else of a best-ranked league's ([TOP_LEAGUES]). */
    const val OWN_NATION = 0.75
    const val TOP_LEAGUES = 8

    /** The keys of a retired coach's summary ([RetiredCoach.count]). */
    val SUMMARY_KEYS = listOf("CT", "L0", "IC", "COY", "clubs", "seasons", "sacked", "longest")

    private const val RETIRE_SALT = 0xC0A1L
    private const val MAKE_SALT = 0xC0A2L
    private const val CONTRACT_SALT = 0xC0A3L
    private const val START_SALT = 0xC0A5L

    /** The sacking threshold of an owner of [patience]. */
    fun threshold(patience: Int): Double = PATIENCE_SLOPE * patience + PATIENCE_BASE

    /** The board's target place `T = clamp(E − 0.5 × (ambition − 3), 1, C)` for the [expected] place among [clubs]. */
    fun target(expected: Int, ambition: Int, clubs: Int): Double = (expected - AMBITION * (ambition - 3)).coerceIn(1.0, maxOf(1, clubs).toDouble())

    /** A target as the screens and the news show it: a half place rounds to the better one, so "met it" reads the same as a gap ≥ 0. */
    fun shown(target: Double): Int = Math.ceil(target - 0.5).toInt()

    /** `gap = (T − place) / C`, positive when better than asked. */
    fun gap(target: Double, place: Int, clubs: Int): Double = if (clubs <= 0) 0.0 else (target - place) / clubs

    /** The pressure after a checkpoint: `max(0, p + 2 × max(0, −gap) − max(0, gap) + 0.5 × [mood < 35])`. */
    fun pressureAfter(pressure: Double, gap: Double, mood: Int): Double =
        maxOf(0.0, pressure + MISS * maxOf(0.0, -gap) - maxOf(0.0, gap) + if (mood < MOOD_LOW) MOOD_PRESSURE else 0.0)

    /** A coach's reputation after a close (ENG 2.5). */
    fun reputationAfter(reputation: Int, gap: Double, title: Boolean, league: Boolean, champions: Boolean, sacked: Boolean): Int = Math.round(
        0.95 * reputation + 0.05 * 40 + 12 * gap + (if (title) 10 else 0) + (if (league) 4 else 0) + (if (champions) 8 else 0) - (if (sacked) 8 else 0),
    ).toInt().coerceIn(0, 100)

    /** The chance a coach of [age] retires after a season: from 62 `0.15 + 0.10 × (age − 62)` (certain from 71), at 72 at the latest. */
    fun retireChance(age: Int): Double = when {
        age >= RETIRE_BY -> 1.0
        age < RETIRE_FROM -> 0.0
        else -> minOf(1.0, RETIRE_BASE + RETIRE_STEP * (age - RETIRE_FROM))
    }

    /** The free pool's floor for a world of [clubs] clubs: `max(8, ⌈clubs / 4⌉)`. */
    fun poolSize(clubs: Int): Int = maxOf(POOL_MIN, (clubs + 3) / 4)

    /** Club → its expected club-table place in league [league] of [plan] ([Expectation.clubs] from the [drawn] ratings). */
    fun expected(plan: SeasonPlan, book: ClubBook, league: String, drawn: (String) -> Double): Map<String, Int> =
        Expectation.clubs(plan, book, league, drawn).withIndex().associate { (i, p) -> p.first to i + 1 }

    /**
     * Every club's board target in [plan]'s season, as [shown] (for the fans' mood, `LifeInput.target`):
     * club → place, for each club of a league's club table.
     */
    fun targets(plan: SeasonPlan, book: ClubBook, drawn: (String) -> Double): Map<String, Int> {
        val w = book.world ?: return emptyMap()
        val out = HashMap<String, Int>()
        for (lg in plan.league.leagues.list) {
            val exp = expected(plan, book, lg.id, drawn)
            for ((club, e) in exp) {
                val ambition = (w.economy.owners[club] ?: Owner.NONE).ambition
                out.putIfAbsent(club, shown(target(e, ambition, exp.size)))
            }
        }
        return out
    }

    /** Whether club [club] sacked a coach in season [season] already (at most one a season). */
    fun sackedIn(book: ClubBook, club: String, season: Int): Boolean =
        book.world?.coaches?.active?.values?.any { c -> c.tenures.any { it.club == club && it.to == season && it.end == 'S' } } == true

    /**
     * Whether [coach] at [club] is at its first checkpoint in [season] (it then survives unless the
     * pressure reaches [FIRST_CHECKPOINT] × the threshold): at the winter checkpoint a coach hired in
     * the off-season before it; at the close one hired at that winter's checkpoint, or one hired in the
     * off-season when no winter checkpoint ran.
     */
    fun firstCheckpoint(book: ClubBook, club: String, coach: Coach, season: Int, winter: Boolean): Boolean {
        val w = book.world ?: return false
        val t = coach.current?.takeIf { it.club == club } ?: return false
        if (t.from < season) return false
        return winter || w.checkpoint < season || sackedIn(book, club, season)
    }

    /** The sacking threshold that applies to [coach] at [club] at its next checkpoint of [season]. */
    fun limit(book: ClubBook, club: String, coach: Coach, season: Int, winter: Boolean): Double {
        val owner = book.world?.economy?.owners?.get(club) ?: Owner.NONE
        return threshold(owner.patience) * if (firstCheckpoint(book, club, coach, season, winter)) FIRST_CHECKPOINT else 1.0
    }

    /** One coached club at a checkpoint: its seat, expected place and the board's target. */
    private class Seat(val club: String, val coach: String, val league: String, val place: Int, val clubs: Int, val expected: Int, val target: Double) {
        val gap: Double get() = gap(target, place, clubs)
    }

    /** The coached clubs of [book] with a seat in [tables], in id order. */
    private fun seats(book: ClubBook, plan: SeasonPlan, tables: Map<String, List<ClubStanding>>, drawn: (String) -> Double): List<Seat> {
        val w = book.world ?: return emptyList()
        val seats = ClubEconomy.seats(tables)
        val expected = HashMap<String, Map<String, Int>>()
        val out = ArrayList<Seat>()
        for ((id, st) in w.clubs) {
            val coach = st.coach?.let { w.coaches.active[it] }?.takeIf { it.club == id } ?: continue
            if (book.club(id) == null) continue
            val seat = seats[id] ?: continue
            val exp = expected.getOrPut(seat.league) { expected(plan, book, seat.league, drawn) }[id] ?: continue
            val ambition = (w.economy.owners[id] ?: Owner.NONE).ambition
            out += Seat(id, coach.id, seat.league, seat.place, seat.clubs, exp, target(exp, ambition, seat.clubs))
        }
        return out
    }

    /**
     * The board review at a checkpoint of [plan]'s season (ENG 2.3): every coached club with a seat in
     * [tables] (league → club table, best first; at the winter checkpoint the tables through its week,
     * at the close the season's) moves its coach's pressure by its gap and its fans' mood; at the close
     * a club-table title or a member's league title ([honours]) sets it to 0. A coach whose pressure
     * reaches its threshold ([limit]) is sacked, at most one a club a season. Returns the sackings, in
     * club order (P13 hires at once at the winter checkpoint, [hire]; the off-season's hiring step
     * fills the close's vacancies).
     */
    fun review(
        book: ClubBook,
        plan: SeasonPlan,
        tables: Map<String, List<ClubStanding>>,
        drawn: (String) -> Double,
        winter: Boolean,
        honours: List<Honour> = emptyList(),
    ): List<CoachMove> {
        if (book.world == null) return emptyList()
        return review(book, plan.number, seats(book, plan, tables, drawn), winter, honours)
    }

    /** [review] of the coached clubs' [seats] in season [s]. */
    private fun review(book: ClubBook, s: Int, seats: List<Seat>, winter: Boolean, honours: List<Honour>): List<CoachMove> {
        val w = book.world ?: return emptyList()
        val titled = if (winter) emptySet() else leagueTitleClubs(book, honours, s)
        val out = ArrayList<CoachMove>()
        for (seat in seats) {
            val st = w.clubs.getValue(seat.club)
            val coach = w.coaches.active.getValue(seat.coach)
            val before = st.pressure
            st.pressure = if (!winter && (seat.place == 1 || seat.club in titled)) 0.0 else pressureAfter(st.pressure, seat.gap, st.mood)
            val limit = limit(book, seat.club, coach, s, winter)
            if (st.pressure >= limit && !sackedIn(book, seat.club, s)) {
                val place = seat.place
                sack(book, seat.club, s)
                out += CoachMove('S', coach.id, seat.club, null, place, shown(seat.target))
            } else if (st.pressure != before) {
                book.touch()
            }
        }
        return out
    }

    /** Club [club]'s coach is sacked in [season]: its spell ends ('S'), it joins the free pool, the club's pressure starts again. */
    private fun sack(book: ClubBook, club: String, season: Int) = end(book, club, season, 'S')

    /** Club [club]'s coach leaves ([end]: S sacked, P poached, L left, R retired), its spell ends in [season]; the club has no coach. */
    private fun end(book: ClubBook, club: String, season: Int, end: Char): Coach? {
        val w = book.world ?: return null
        val st = w.clubs[club]
        val c = (st?.coach?.let { w.coaches.active[it] } ?: w.coaches.coachOf(club))?.takeIf { it.club == club }
        st?.coach = null
        st?.pressure = 0.0
        book.touch()
        c ?: return null
        val spell = c.current
        val tenures = c.tenures.map { if (it === spell) it.copy(to = season, end = end) else it }
        val n = c.copy(
            club = null, since = 0, until = 0, wage = 0, tenures = tenures,
            sacked = c.sacked + if (end == 'S') 1 else 0, longest = maxOf(c.longest, spell?.seasons(season) ?: 0),
        )
        w.coaches.active[c.id] = n
        return n
    }

    /** Club [club] of a dissolved or merged-away club lets its coach go to the free pool ('L' in [season]; P11's dissolutions). */
    fun release(book: ClubBook, club: String, season: Int): Coach? {
        val w = book.world ?: return null
        val c = end(book, club, season, 'L') ?: return null
        if (w.coaches.season != season) return c
        return noted(c, season, CoachMove('L', c.id, club, null)).also { w.coaches.active[it.id] = it }
    }

    /**
     * [c] with [move] among its moves of the off-season after [season] (its [Coach.done], so
     * [report] finds it): a coach the market has not run with yet (one made after the step) starts
     * that off-season's moves.
     */
    private fun noted(c: Coach, season: Int, move: CoachMove): Coach =
        if (c.done == season) c.copy(moves = c.moves + move) else c.copy(done = season, moves = listOf(move))

    // ---- hiring ------------------------------------------------------------------------------------

    /** What hiring needs about the clubs, computed once a step. */
    private class Hiring(
        val book: ClubBook,
        val season: Int,
        val input: ClubInput,
        val seed: Long,
        val leagueOf: Map<String, String>,
        val rank: (String) -> Int,
    ) {
        val w = book.world!!
        private val valuer: (String) -> Int = input.coachInput.value ?: { k -> Valuation.value(input.rating(k), w.anchor, input.age(k), null, null) }
        private val values = HashMap<String, Int>()
        private val incomes = HashMap<String, Int>()
        private val medians = HashMap<String?, Int>()
        private val fits = HashMap<Pair<String, Philosophy>, Double>()
        private val alive = input.members.toHashSet()

        fun value(club: String): Int = values.getOrPut(club) { ClubEconomy.clubValue(book, club, valuer) ?: 0 }

        fun league(club: String): String = leagueOf[club] ?: book.nationOf(club).orEmpty()

        fun omega(club: String): Double = w.omega(league(club))

        /** Its income for the wage rule: the last accounts', else the median of its nation's clubs (0: no rule). */
        fun income(club: String): Int = incomes.getOrPut(club) {
            ClubEconomy.income(book, club).takeIf { it > 0 } ?: medians.getOrPut(book.nationOf(club)) {
                val xs = book.clubsOf(book.nationOf(club).orEmpty()).map { ClubEconomy.income(book, it.id) }.filter { it > 0 }.sorted()
                if (xs.isEmpty()) 0 else xs[xs.size / 2]
            }
        }

        fun wageOf(c: Coach, club: String): Int = ClubEconomy.coachWage(c.reputation, omega(club))

        fun affordable(c: Coach, club: String): Boolean {
            val income = income(club)
            if (income <= 0) return true
            return wageOf(c, club) <= share(club) * income
        }

        fun share(club: String): Double = if (rich(club)) RICH_WAGE_SHARE else WAGE_SHARE

        fun rich(club: String): Boolean = richOwner(book, club)

        fun allowed(c: Coach, club: String): Boolean = !c.philosophy.needsRichOwner || rich(club)

        /** The share of [club]'s counted 6 (its best members by rating, next season's) that fits [p]. */
        fun fit(p: Philosophy, club: String): Double = fits.getOrPut(club to p) { fitNow(p, club) }

        private fun fitNow(p: Philosophy, club: String): Double {
            if (p == Philosophy.STAR) return 0.0
            val counted = book.members(club).filter { it in alive }
                .sortedWith(compareByDescending<String> { input.rating(it).let { r -> if (r.isNaN()) Double.NEGATIVE_INFINITY else r } }.thenBy { it })
                .take(ClubTable.COUNTED)
            if (counted.isEmpty()) return 0.0
            val n = if (p == Philosophy.YOUTH) counted.count { (input.age(it) ?: 99) <= 23 } else counted.count { input.families(it) in p.families }
            return n.toDouble() / counted.size
        }

        fun score(c: Coach, club: String): Double =
            c.reputation + FIT * fit(c.philosophy, club) + if (c.nation == book.nationOf(club)) SAME_NATION else 0.0

        fun contract(club: String): Int {
            if ((ClubEconomy.troubleLevel(book, club) ?: 0) >= 2) return CONTRACT_MIN
            return CONTRACT_MIN + Random(DiceStream.mix(seed, CONTRACT_SALT, season.toLong(), ClubEconomy.number(club).toLong())).nextInt(CONTRACT_MAX - CONTRACT_MIN + 1)
        }
    }

    /** The coaches' names and the players' (a generated coach's name is unique against both). */
    private fun taken(w: ClubWorld, input: ClubInput): MutableSet<String> = HashSet<String>().apply {
        addAll(input.coachInput.names)
        w.coaches.active.values.forEach { add(it.name) }
        w.coaches.retiredList.forEach { add(it.name) }
    }

    /** The next coach id ("k<n>", never reused). */
    private fun nextId(w: ClubWorld): String = "k${w.nextCoach++}"

    /** The nation of a coach generated for [club] (its own 75%, else one of the [TOP_LEAGUES] best-ranked leagues'), or for the pool (a club's drawn first). */
    private fun nationFor(book: ClubBook, club: String?, input: ClubInput, rng: Random): String {
        val own = (club ?: book.clubs.takeIf { it.isNotEmpty() }?.let { it[rng.nextInt(it.size)].id })?.let { book.nationOf(it) }
        if (own != null && rng.nextDouble() < OWN_NATION) return own
        val top = topNations(book, input)
        return if (top.isEmpty()) own ?: Nations.KR.code else top[rng.nextInt(top.size)]
    }

    /** The nations of the [TOP_LEAGUES] best-ranked leagues (by the ranking, else by λ), national leagues only. */
    private fun topNations(book: ClubBook, input: ClubInput): List<String> {
        val leagues = input.ranking.rows.sortedBy { it.rank }.map { it.id }.ifEmpty {
            input.economy.lambda.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).map { it.key }
        }
        val nations = book.clubs.mapTo(HashSet()) { it.nation }
        return leagues.filter { Nations.of(it) != null && it in nations }.take(TOP_LEAGUES).distinct()
    }

    /** A generated coach for [club] (or the pool), added to the book free; one for a club without a rich owner is never a 스타 영입형. */
    private fun generate(book: ClubBook, club: String?, season: Int, input: ClubInput, seed: Long, taken: MutableSet<String>, philosophy: Philosophy? = null): Coach {
        val w = book.world!!
        val id = nextId(w)
        val rng = Random(DiceStream.mix(seed, MAKE_SALT, season.toLong(), id.removePrefix("k").toLong()))
        val nation = nationFor(book, club, input, rng)
        val phil = philosophy ?: if (club != null && !richOwner(book, club)) CoachMaker.pick(rng, CoachMaker.WEIGHTS.filter { !it.first.needsRichOwner }) else null
        val c = CoachMaker.make(id, nation, season, rng, taken, phil)
        taken += c.name
        w.coaches.active[c.id] = c
        book.touch()
        return c
    }

    /** Whether club [club]'s owner is a 재벌 or a 국부 펀드 (the only ones who hire a 스타 영입형). */
    private fun richOwner(book: ClubBook, club: String): Boolean =
        (book.world?.economy?.owners?.get(club) ?: Owner.NONE).type.let { it == OwnerType.TYCOON || it == OwnerType.STATE }

    /** Coach [c] takes charge of [club] from season [from] to [until] at [wage]; [move] is kept on it. */
    private fun take(book: ClubBook, c: Coach, club: String, from: Int, until: Int, wage: Int, move: CoachMove?): Coach {
        val w = book.world!!
        val all = listOf(Tenure(club, from, 0, 0, 0, '-')) + c.tenures
        val kept = all.take(CoachBook.TENURES)
        val dropped = all.drop(CoachBook.TENURES)
        val n = c.copy(
            club = club, since = from, until = until, wage = wage, tenures = kept, spells = c.spells + 1,
            longest = maxOf(c.longest, dropped.maxOfOrNull { it.seasons(from) } ?: 0), moves = c.moves + listOfNotNull(move),
        )
        w.coaches.active[c.id] = n
        w.state(club).apply {
            coach = c.id
            pressure = 0.0
        }
        book.touch()
        return n
    }

    /** The club [c] led last (its newest ended spell's), null for none. */
    private fun lastClub(c: Coach): String? = c.tenures.firstOrNull { it.to != 0 }?.club

    /**
     * Club [club] hires at the winter checkpoint of [season] (ENG 2.3): the free coach with the best
     * reputation whose wage it can pay (`wage ≤ 0.12 × income`, 0.18 with a 재벌 or 국부 펀드 owner),
     * else the cheapest free one, else a generated one; no poaching mid-season. The spell starts in
     * [season] and the contract covers 2-3 seasons with it. Null without a club world state or when
     * the club has a coach.
     */
    fun hire(book: ClubBook, club: String, season: Int, input: ClubInput, seed: Long = book.seed): Coach? {
        val w = book.world ?: return null
        val st = w.clubs[club] ?: return null
        if (st.coach?.let { w.coaches.active[it] }?.club == club || book.club(club) == null) return null
        val h = Hiring(book, season, input, seed, emptyMap(), { 0 })
        val free = w.coaches.active.values.filter { it.club == null && h.allowed(it, club) && !justLeft(it, club, season) }
        val c = free.filter { h.affordable(it, club) }
            .maxWithOrNull(compareBy<Coach> { it.reputation }.thenComparing({ it.id }, CoachBook.ID_ORDER.reversed()))
            ?: cheapest(free)
            ?: generate(book, club, season, input, seed, taken(w, input))
        return take(book, c, club, season, season + h.contract(club) - 1, h.wageOf(c, club), null)
    }

    /** Whether [c]'s spell at [club] ended in [season] (a club does not hire back the coach it just let go). */
    private fun justLeft(c: Coach, club: String, season: Int): Boolean = c.tenures.any { it.club == club && it.to >= season }

    private fun cheapest(cs: List<Coach>): Coach? = cs.minWithOrNull(compareBy<Coach> { it.reputation }.thenComparing({ it.id }, CoachBook.ID_ORDER))

    // ---- the off-season (step 4) --------------------------------------------------------------------

    /**
     * The coach market of the off-season after [closing] (ENG 1b.10 step 4, 2.3-2.5), once a season:
     * the close's board review ([review] against the club [tables] of the close, the [ClubInput.drawn]
     * ratings and the season's honours) and every coach's season in charge (seasons, club-table titles
     * and places, members' league and international titles, reputation, whose −8 for a sacking also
     * reaches a coach sacked at the winter checkpoint; the 올해의 감독's award);
     * retirements (62-72); expiring contracts, renewed when the pressure is below half the threshold;
     * this close's retirees who become coaches and generated coaches until the free pool holds
     * [poolSize]; then every club without a coach hires, by club value (high first), one hire a club:
     * the best score (`reputation + 25 × fit + 10 × [same nation]`) among the free coaches and the
     * employed ones it may poach (reputation ≥ 50, from a club worth ≤ 0.77 × its value or a league
     * ranked 3 or more places lower), whose wage it can pay (≤ 0.12 × income, 0.18 for 재벌 and 국부
     * 펀드 owners) and, for a poached one, whose compensation (its wage × seasons left, paid to the old
     * club) its transfer budget covers; the club a coach was poached from hires next in turn. A coach
     * hired in this off-season is not poached again. The free pool is topped up again at the end.
     *
     * Nothing without a club world, for a club league's book, with [LeagueConfig.clubWorld] off or
     * the test knob [ClubInput.coaches] off. Asked again for the same season it changes nothing and
     * returns the report rebuilt from the book ([report]).
     */
    fun offseason(book: ClubBook, closing: SeasonPlan, input: ClubInput, tables: Map<String, List<ClubStanding>>): CoachReport {
        val s = closing.number
        val w = book.world ?: return CoachReport(s, emptyList(), emptyList())
        if (book.format != ClubBook.AFFILIATION || !input.config.clubWorld || !input.coaches) return CoachReport(s, emptyList(), emptyList())
        if (w.coaches.season >= s) return report(book, s)
        val seed = closing.seed
        // The previous off-season's moves go; coaches and clubs out of step are put right.
        for (c in w.coaches.active.values.toList()) w.coaches.active[c.id] = c.copy(moves = emptyList())
        settle(book, s)
        val honours = input.honours.filter { it.season == s }
        val places = ClubEconomy.seats(tables)
        // 1. The close's review, and every coach's season in charge.
        val inCharge = w.clubs.values.mapNotNull { st -> st.coach?.let { w.coaches.active[it] }?.takeIf { it.club == st.id && book.club(st.id) != null } }
        val seated = seats(book, closing, tables, input.drawn)
        val boards = seated.associateBy { it.club }
        val sacked = review(book, s, seated, winter = false, honours = honours)
        val sackedIds = sacked.mapTo(HashSet()) { it.coach }
        for (m in sacked) moveOn(w, m)
        // Sacked this season, at the close or at the winter checkpoint (then free, or in charge elsewhere): reputation's −8.
        fun wasSacked(c: Coach) = c.tenures.any { it.to == s && it.end == 'S' }
        for (c0 in inCharge) {
            val club = c0.club!!
            val c = w.coaches.active.getValue(c0.id)
            val seat = places[club]
            val title = seat?.place == 1
            val members = book.members(club).toHashSet()
            val mine = honours.filter { h -> if (HonourKind.base(h.kind) in CLUB_CUPS) h.key == club else h.key in members }
            val league = mine.count { HonourKind.isTopTitle(it.kind) }
            val champions = mine.count { it.kind == "CL" }
            val cups = champions + mine.count { HonourKind.base(it.kind) == "CCL" || HonourKind.base(it.kind) in CLUB_CUPS }
            val tenures = c.tenures.map { t ->
                if (t.club != club || !(t.to == 0 || (t.to == s && c.id in sackedIds))) t
                else t.copy(best = seat?.place?.let { p -> if (t.best == 0) p else minOf(t.best, p) } ?: t.best, titles = t.titles + if (title) 1 else 0)
            }
            val gap = boards[club]?.gap ?: 0.0
            w.coaches.active[c.id] = c.copy(
                seasons = c.seasons + 1, clubTitles = c.clubTitles + if (title) 1 else 0, leagueTitles = c.leagueTitles + league, cups = c.cups + cups,
                tenures = tenures, reputation = reputationAfter(c.reputation, gap, title, league > 0, champions > 0, wasSacked(c)),
            )
        }
        val charged = inCharge.mapTo(HashSet()) { it.id }
        for (c in w.coaches.active.values.toList()) {
            if (c.id !in charged) w.coaches.active[c.id] = c.copy(reputation = reputationAfter(c.reputation, 0.0, false, false, false, wasSacked(c)))
        }
        input.coachInput.coachOfYear?.let { id -> w.coaches.active[id]?.let { w.coaches.active[id] = it.copy(awards = it.awards + 1) } }
        // 2. Retirements (not a coach sacked at this close: its news stays with it).
        for (c in w.coaches.active.values.toList()) {
            if (c.id in sackedIds) continue
            val r = Random(DiceStream.mix(seed, RETIRE_SALT, s.toLong(), c.id.removePrefix("k").toLongOrNull() ?: c.id.hashCode().toLong()))
            if (r.nextDouble() < retireChance(c.age(s))) retire(book, c.id, s)
        }
        // 3. Contracts ending with this season.
        val h = Hiring(book, s, input, seed, places.mapValues { it.value.league }, rankOf(input))
        for (c in w.coaches.active.values.toList()) {
            val club = c.club ?: continue
            if (c.until > s) continue
            val owner = w.economy.owners[club] ?: Owner.NONE
            val st = w.state(club)
            if (st.pressure < RENEW * threshold(owner.patience)) {
                w.coaches.active[c.id] = c.copy(until = s + h.contract(club), wage = h.wageOf(c, club))
                book.touch()
            } else {
                end(book, club, s, 'L')?.let { left -> moveOn(w, CoachMove('L', left.id, club, null)) }
            }
        }
        // 4. New coaches: this close's retirees with a career, then generated ones for the pool.
        val taken = taken(w, input)
        for (r in input.coachInput.retirees.sortedBy { it.key }) {
            if (!CoachMaker.becomes(r, seed)) continue
            if (w.coaches.active.values.any { it.player == r.key } || w.coaches.retiredList.any { it.player == r.key }) continue
            val id = nextId(w)
            val c = CoachMaker.fromPlayer(id, r, Random(DiceStream.mix(seed, MAKE_SALT, s.toLong(), id.removePrefix("k").toLong())))
            w.coaches.active[id] = c.copy(moves = listOf(CoachMove('C', id, null, r.key)))
            taken += c.name
            book.touch()
        }
        fill(book, s + 1, input, seed, taken)
        // 5. Hiring, the richest vacancy first; a poached coach's club joins the queue.
        hireAll(h, taken)
        fill(book, s + 1, input, seed, taken)
        for (c in w.coaches.active.values.toList()) w.coaches.active[c.id] = c.copy(done = s)
        book.touch()
        return report(book, s)
    }

    /** The club cups' honour kinds (keyed by the club). */
    private val CLUB_CUPS = setOf("CLUBCUP")

    /** Keeps [m] on its coach's line (the step's news). */
    private fun moveOn(w: ClubWorld, m: CoachMove) {
        w.coaches.active[m.coach]?.let { w.coaches.active[m.coach] = it.copy(moves = it.moves + m) }
    }

    /** Coaches and clubs that point at each other only one way (or at a club that is gone) are set free. */
    private fun settle(book: ClubBook, season: Int) {
        val w = book.world ?: return
        for (st in w.clubs.values) {
            val c = st.coach?.let { w.coaches.active[it] }
            if (st.coach != null && (c == null || c.club != st.id || book.club(st.id) == null)) {
                st.coach = null
                book.touch()
            }
        }
        for (c in w.coaches.active.values.toList()) {
            val club = c.club ?: continue
            if (book.club(club) == null || w.clubs[club]?.coach != c.id) {
                val spell = c.current
                w.coaches.active[c.id] = c.copy(
                    club = null, since = 0, until = 0, wage = 0,
                    tenures = c.tenures.map { if (it === spell) it.copy(to = season, end = 'L') else it },
                    longest = maxOf(c.longest, spell?.seasons(season) ?: 0),
                )
                book.touch()
            }
        }
    }

    /** Coach [id] retires after [season]: its spell ends ('R'); kept in `kr` with a title, an award or [KEEP_SEASONS] seasons. */
    private fun retire(book: ClubBook, id: String, season: Int) {
        val w = book.world ?: return
        val c0 = w.coaches.active[id] ?: return
        val c = c0.club?.let { end(book, it, season, 'R') } ?: c0
        w.coaches.active.remove(id)
        book.touch()
        val honours = c.clubTitles + c.leagueTitles + c.cups + c.awards
        if (honours == 0 && c.seasons < KEEP_SEASONS) return
        val summary = listOf(
            "CT" to c.clubTitles, "L0" to c.leagueTitles, "IC" to c.cups, "COY" to c.awards, "clubs" to c.spells,
            "seasons" to c.seasons, "sacked" to c.sacked, "longest" to c.longestBy(season),
        ).filter { it.second != 0 }.joinToString(",") { "${it.first}:${it.second}" }
        w.coaches.retiredList += RetiredCoach(c.id, c.name, c.nation, c.gender, c.born, season, c.philosophy, c.reputation, c.player, summary)
        val cap = if (book.size < SMALL) KEEP_RETIRED_SMALL else KEEP_RETIRED
        while (w.coaches.retiredList.size > cap) {
            val plain = w.coaches.retiredList.filter { r -> listOf("CT", "L0", "IC", "COY").all { r.count(it) == 0 } }
            val drop = (plain.ifEmpty { w.coaches.retiredList }).minWith(compareBy<RetiredCoach> { it.retired }.thenComparing({ it.id }, CoachBook.ID_ORDER))
            w.coaches.retiredList.remove(drop)
        }
    }

    /** Generated coaches join the free pool until it holds [poolSize] (made for season [season]). */
    private fun fill(book: ClubBook, season: Int, input: ClubInput, seed: Long, taken: MutableSet<String>) {
        val w = book.world ?: return
        val want = poolSize(book.clubs.size)
        var free = w.coaches.active.values.count { it.club == null }
        while (free < want) {
            generate(book, null, season, input, seed, taken)
            free++
        }
    }

    /** League → its rank (0 the best): by the ranking, else by λ; [Int.MAX_VALUE] when unknown. */
    private fun rankOf(input: ClubInput): (String) -> Int {
        if (input.ranking.rows.isNotEmpty()) return { input.ranking.rank(it) }
        val order = input.economy.lambda.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .withIndex().associate { (i, e) -> e.key to i }
        return { order[it] ?: Int.MAX_VALUE }
    }

    /** The hiring step: every club without a coach, by value (high first), hires once. */
    private fun hireAll(h: Hiring, taken: MutableSet<String>) {
        val book = h.book
        val w = h.w
        val s = h.season
        val queue = TreeSet<String>(compareByDescending<String> { h.value(it) }.thenComparing(ClubBook.ID_ORDER))
        for (c in book.clubs) {
            val st = w.clubs[c.id] ?: continue
            if (st.coach == null) queue += c.id
        }
        val hired = HashSet<String>()
        val done = HashSet<String>()
        while (queue.isNotEmpty()) {
            val club = queue.pollFirst()!!
            if (!done.add(club)) continue
            val tb by lazy { ClubEconomy.transferBudget(book, club, h.input.config.ffp) }
            val rank = h.rank(h.league(club))
            val candidates = ArrayList<Pair<Coach, Int>>()
            for (c in w.coaches.active.values) {
                if (c.id in hired || !h.allowed(c, club) || !h.affordable(c, club)) continue
                val from = c.club
                if (from == null) {
                    if (!justLeft(c, club, s)) candidates += c to 0
                    continue
                }
                if (from == club || c.reputation < POACH_REPUTATION || c.until <= s) continue
                val fromRank = h.rank(h.league(from))
                val smaller = h.value(from) <= POACH_VALUE * h.value(club)
                val lower = rank != Int.MAX_VALUE && fromRank != Int.MAX_VALUE && fromRank - rank >= POACH_RANKS
                if (!smaller && !lower) continue
                val fee = c.wage * (c.until - s)
                if (fee > tb) continue
                candidates += c to fee
            }
            val best = candidates.maxWithOrNull(
                compareBy<Pair<Coach, Int>> { h.score(it.first, club) }.thenBy { it.first.reputation }
                    .thenComparing({ it.first.id }, CoachBook.ID_ORDER.reversed()),
            )
            val until = s + h.contract(club)
            if (best == null) {
                val free = w.coaches.active.values.filter { it.club == null && it.id !in hired && h.allowed(it, club) && !justLeft(it, club, s) }
                val c = cheapest(free) ?: generate(book, club, s + 1, h.input, h.seed, taken)
                hired += c.id
                take(book, c, club, s + 1, until, h.wageOf(c, club), CoachMove('H', c.id, club, lastClub(c)))
                continue
            }
            val (c, fee) = best
            hired += c.id
            val from = c.club
            if (from == null) {
                take(book, c, club, s + 1, until, h.wageOf(c, club), CoachMove('H', c.id, club, lastClub(c)))
            } else {
                val left = end(book, from, s, 'P') ?: continue
                ClubEconomy.charge(book, club, MoneyItem.COACH, fee, s)
                ClubEconomy.charge(book, from, MoneyItem.COACH, -fee, s)
                take(book, left, club, s + 1, until, h.wageOf(left, club), CoachMove('P', c.id, club, from, fee = fee))
                if (from !in done) queue += from
            }
        }
    }

    /**
     * The coach market's off-season of [season] as the book holds it: the moves kept on the coaches
     * whose last off-season it was and the coaches kept in `kr` who retired after it.
     */
    fun report(book: ClubBook, season: Int): CoachReport {
        val w = book.world ?: return CoachReport(season, emptyList(), emptyList())
        val moves = w.coaches.active.values.filter { it.done == season }.flatMap { it.moves }
        return CoachReport(season, moves, w.coaches.retiredList.filter { it.retired == season })
    }

    // ---- outlooks (2.2, for the screens) --------------------------------------------------------------

    /**
     * Club [club]'s board outlook through week [through] of [plan]'s season (ENG 2.2): its expected
     * place from the [drawn] ratings, the target, its place in the club table through the week (with
     * the season's winter moves, deductions and [honours]), the gap, its coach's pressure, the
     * threshold of its next checkpoint and the pressure that checkpoint would leave at this gap (the
     * close's: 0 in first place, the title's reset). Null without a club world, a coach or a seat (a
     * club league's season included).
     */
    fun outlook(plan: SeasonPlan, book: ClubBook, club: String, through: Int, drawn: (String) -> Double, honours: List<Honour> = emptyList()): BoardOutlook? =
        outlooks(plan, book, through, drawn, listOf(club), honours)[club]

    /**
     * [outlook] of every coached club of [clubs] (null: every one), each league's club table and
     * expectations computed once. Club → outlook, by league then table order.
     */
    fun outlooks(
        plan: SeasonPlan,
        book: ClubBook,
        through: Int,
        drawn: (String) -> Double,
        clubs: Collection<String>? = null,
        honours: List<Honour> = emptyList(),
    ): Map<String, BoardOutlook> {
        val w = book.world ?: return emptyMap()
        val season = plan.league
        if (book.format != ClubBook.AFFILIATION || !season.leagues.national) return emptyMap()
        val want = clubs?.toHashSet()
        val leagues = if (want == null) season.leagues.list.map { it.id } else {
            val ids = want.mapNotNull { c -> book.members(c).firstNotNullOfOrNull { season.leagueOf(it)?.id } }.toHashSet()
            season.leagues.list.map { it.id }.filter { it in ids }
        }
        val s = plan.number
        val splits = book.winterMoves(s)
        val deductions = ClubEconomy.deductions(book)
        val winter = w.checkpoint < s
        val out = LinkedHashMap<String, BoardOutlook>()
        for (lg in leagues) {
            val table = ClubTable.of(season, book, lg, honours, through, splits, deductions)
            if (table.none { want == null || it.club in want }) continue
            val exp = expected(plan, book, lg, drawn)
            for ((i, row) in table.withIndex()) {
                if (want != null && row.club !in want) continue
                if (out.containsKey(row.club)) continue
                val st = w.clubs[row.club] ?: continue
                val coach = st.coach?.let { w.coaches.active[it] }?.takeIf { it.club == row.club } ?: continue
                val e = exp[row.club] ?: continue
                val owner = w.economy.owners[row.club] ?: Owner.NONE
                val t = target(e, owner.ambition, table.size)
                val g = gap(t, i + 1, table.size)
                val projected = if (!winter && i == 0) 0.0 else LineParts.fixed(pressureAfter(st.pressure, g, st.mood), 10)
                out[row.club] = BoardOutlook(e, shown(t), i + 1, table.size, g, st.pressure, limit(book, row.club, coach, s, winter), projected)
            }
        }
        return out
    }

    // ---- founding a club world's coaches (8.2) -----------------------------------------------------------

    /**
     * The coaches of a club world being migrated or founded (ENG 8.2; P11's `ClubWorld.migrate` and
     * `found`): every club of [book] without a coach gets one, starting in [season] (the season in
     * play) on a staggered contract (to [season] + 0..2 by its id) at `(0.3 + 0.04 × reputation) × ω`
     * of its league ([leagueOf], the club's nation by default): first the ex-player coaches of
     * [legends] (best first, each to the most valuable club of its nation still without one that may
     * hire it; one without a club joins the pool), then a generated coach (of the club's nation three
     * times in four) whose philosophy is weighted by how many of the club's members fit it (스타
     * 영입형 only for a 재벌 or 국부 펀드 owner); then the free pool up to [poolSize]. Clubs that have a
     * coach keep it, so a second call changes nothing. Returns the coaches made.
     */
    fun start(
        book: ClubBook,
        season: Int,
        input: ClubInput,
        legends: List<Retiree> = emptyList(),
        seed: Long = book.seed,
        leagueOf: (String) -> String = { book.nationOf(it).orEmpty() },
    ): Int {
        val w = book.world ?: return 0
        if (book.format != ClubBook.AFFILIATION) return 0
        val before = w.coaches.active.size
        val h = Hiring(book, season - 1, input, seed, book.clubs.associate { it.id to leagueOf(it.id) }, { 0 })
        val taken = taken(w, input)
        val open = book.clubs.filter { c -> w.clubs[c.id]?.let { st -> st.coach?.let { w.coaches.active[it] }?.club != c.id } == true }
            .sortedWith(compareByDescending<Club> { h.value(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER)).map { it.id }.toMutableList()
        fun contract(club: String) = season + Math.floorMod(DiceStream.mix(seed, CONTRACT_SALT, ClubEconomy.number(club).toLong()), 3L).toInt()
        for (r in legends.sortedWith(compareByDescending<Retiree> { it.majors }.thenBy { it.peak }.thenBy { it.key })) {
            if (w.coaches.active.values.any { it.player == r.key } || w.coaches.retiredList.any { it.player == r.key }) continue
            val id = nextId(w)
            val c = CoachMaker.fromPlayer(id, r, Random(DiceStream.mix(seed, START_SALT, id.removePrefix("k").toLong())))
            w.coaches.active[id] = c
            taken += c.name
            val club = open.firstOrNull { book.nationOf(it) == c.nation && h.allowed(c, it) } ?: continue
            open.remove(club)
            take(book, c, club, season, contract(club), h.wageOf(c, club), null)
        }
        for (club in open) {
            val weights = CoachMaker.WEIGHTS.filter { !it.first.needsRichOwner || h.rich(club) }.map { (p, x) -> p to x * (1 + 3 * h.fit(p, club)) }
            val rng = Random(DiceStream.mix(seed, START_SALT, ClubEconomy.number(club).toLong(), 1L))
            val c = generate(book, club, season, input, seed, taken, CoachMaker.pick(rng, weights))
            take(book, c, club, season, contract(club), h.wageOf(c, club), null)
        }
        fill(book, season, input, seed, taken)
        return w.coaches.active.size - before
    }

    /**
     * A club founded in the off-season after season [season] − 1 (P11's step 10, ENG 8.2) hires for
     * [season]: the best free coach by the hiring score whose wage it can pay, else the cheapest free
     * one, else a generated one. Null when it has a coach or no club world state.
     */
    fun newClub(book: ClubBook, club: String, season: Int, input: ClubInput, seed: Long = book.seed): Coach? {
        val w = book.world ?: return null
        val st = w.clubs[club] ?: return null
        if (st.coach?.let { w.coaches.active[it] }?.club == club || book.club(club) == null) return null
        val h = Hiring(book, season - 1, input, seed, emptyMap(), { 0 })
        val free = w.coaches.active.values.filter { it.club == null && h.allowed(it, club) && !justLeft(it, club, season - 1) }
        val c = free.filter { h.affordable(it, club) }
            .maxWithOrNull(compareBy<Coach> { h.score(it, club) }.thenBy { it.reputation }.thenComparing({ it.id }, CoachBook.ID_ORDER.reversed()))
            ?: cheapest(free)
            ?: generate(book, club, season, input, seed, taken(w, input))
        // After the off-season's coach market: the hire is one of its moves (a generated coach's too).
        val hiring = if (w.coaches.season == season - 1) noted(c, season - 1, CoachMove('H', c.id, club, lastClub(c))) else c
        return take(book, hiring, club, season, season - 1 + h.contract(club), h.wageOf(c, club), null)
    }

    // ---- records (2.5) ------------------------------------------------------------------------------

    /**
     * The coach records through [season] (ENG 2.5): 최장 재임 (the longest spell), 최다 클럽 순위 우승,
     * 최다 클럽 (spells), 최다 경질, 올해의 감독 최다, each held by the active or retired coach with the
     * most (ties: the lower id); a kind nobody has any of is left out.
     */
    fun records(book: ClubBook, season: Int): List<CoachRecord> {
        val w = book.world ?: return emptyList()
        val out = ArrayList<CoachRecord>()
        for (kind in CoachRecordKind.entries) {
            val active = w.coaches.active.values.map { c ->
                CoachRecord(kind, c.id, when (kind) {
                    CoachRecordKind.TENURE -> c.longestBy(season)
                    CoachRecordKind.CLUB_TITLES -> c.clubTitles
                    CoachRecordKind.CLUBS -> c.spells
                    CoachRecordKind.SACKED -> c.sacked
                    CoachRecordKind.AWARDS -> c.awards
                }, false)
            }
            val retired = w.coaches.retiredList.map { r ->
                CoachRecord(kind, r.id, r.count(when (kind) {
                    CoachRecordKind.TENURE -> "longest"
                    CoachRecordKind.CLUB_TITLES -> "CT"
                    CoachRecordKind.CLUBS -> "clubs"
                    CoachRecordKind.SACKED -> "sacked"
                    CoachRecordKind.AWARDS -> "COY"
                }), true)
            }
            (active + retired).filter { it.value > 0 }
                .maxWithOrNull(compareBy<CoachRecord> { it.value }.thenComparing({ it.coach }, CoachBook.ID_ORDER.reversed()))
                ?.let { out += it }
        }
        return out
    }

    /** The clubs whose members won a league title (L0.*) in [season]. */
    private fun leagueTitleClubs(book: ClubBook, honours: List<Honour>, season: Int): Set<String> =
        honours.filter { it.season == season && HonourKind.isTopTitle(it.kind) }.mapNotNullTo(HashSet()) { book.clubOf(it.key) }
}

package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.ChampionsLeague
import yacht.engine.league.Competition
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.Family
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.MatchFormat
import yacht.engine.league.Season
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonStart
import yacht.engine.league.TieRule
import yacht.engine.league.ViewableCompetition
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The champions league's groups and bracket ([yacht.engine.league.ChampionsBracket]): the 16-player
 * bracket the champions league plays is the one it played before the bracket was shared with the
 * 대륙 챔피언스 (same fixtures, ids, deals, labels, stages, weeks, news, honours, views and `cl 1`
 * text, also after a save read back midway); the hashes were taken from the code before that.
 */
class ChampionsBracketTest {
    private fun key(i: Int) = "ai%02d".format(i)
    private fun index(k: String) = k.removePrefix("ai").toInt()
    private fun rating(k: String) = 1700.0 - 20 * index(k)
    private val families = Family.entries.filter { it != Family.MINE }
    private val print = CompetitionPrint(::rating)

    private fun start(n: Int, config: LeagueConfig): SeasonStart {
        val keys = (1..n).map(::key)
        return SeasonStart(1, 4242L, config, keys, ::rating, Season.split(keys, 2), emptyList(), emptyList(), { k -> families[(index(k) - 1) % families.size] }, { "서울" })
    }

    private val single = LeagueConfig(matchFormat = MatchFormat.MIRRORED, seriesKnockouts = false, doubleElimination = false)

    @Test
    fun theChampionsLeaguePlaysAsBeforeTheBracketWasShared() {
        val runs = listOf(
            "single" to single,
            "short" to single.copy(longKnockouts = false, matchFormat = MatchFormat.GAMES),
            "default" to LeagueConfig(),
            "short series" to LeagueConfig(longKnockouts = false),
            "double legs" to LeagueConfig(seriesKnockouts = false),
        )
        val got = runs.map { (name, config) ->
            val cl = assertNotNull(ChampionsLeague.create(start(20, config)), name)
            name to print.sha(print.fingerprint(cl, setOf(20, 49, 52)))
        }
        for ((name, hash) in got) println("$name $hash")
        assertEquals(GOLDEN, got.toMap())
    }

    companion object {
        private val GOLDEN = mapOf(
            "single" to "2a6bef3a0f406622cce191ea44045bcbe8616097f5ea27de857a866aec7f22e1",
            "short" to "72941a3e9ff7bbd06166391ce2b18ab079091ac947708507896b84f993cce0a8",
            "default" to "a66c26d2aed8332e8430d7c33c34f18d1fafc0ba93447637dd4fadd9509572d1",
            "short series" to "e7c27feb573b07b40078296b66e333f782dc8268979d265ae0d871446537a3da",
            "double legs" to "77e4f4b2f72986845cf61182f471882aa7e67ba51744033469d48bb66f294603",
        )
    }
}

/**
 * A competition's whole run as text, to pin it by a hash: [play] gives every fixture seeded games
 * (upsets, draws and sweeps come out of its deal), the stronger side by [rating] a little ahead.
 */
internal class CompetitionPrint(private val rating: (String) -> Double) {
    fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun play(f: Fixture): JobResult {
        val rng = Random(f.seed)
        fun games(a: String, b: String, n: Int): List<GameScore> = List(n) {
            val edge = ((rating(a) - rating(b)) / 10).toInt()
            val x = 150 + rng.nextInt(90) + edge
            val y = if (rng.nextInt(10) == 0) x else 150 + rng.nextInt(90)
            GameScore(x, y)
        }
        return when (f.kind) {
            JobKind.MATCH -> games(f.a, f.b, 2).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g, MatchFormat.GAMES), 0, MatchFormat.GAMES) }
            JobKind.TIE -> games(f.a, f.b, 2 * f.legs).let { g ->
                JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0)
            }
            JobKind.TEAM -> f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, games(x, y, 2), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
        }
    }

    fun text(c: Competition) = StringBuilder().also { c.encode(it) }.toString()

    /** A view as text, field by field (only the fields the views had before the bracket was shared). */
    fun viewText(v: CompetitionView): String = buildString {
        fun bracket(b: CompetitionView.Bracket) {
            append("bracket ${b.title}\n")
            for (r in b.rounds) {
                append(" round ${r.label} ${r.side} ${r.stage}\n")
                for (t in r.ties) append("  ${t.a} ${t.b} ${t.scoreA} ${t.scoreB} ${t.winner} ${t.played} ${t.legs} ${t.firstTo} ${t.fromWinners} ${t.bye}\n")
            }
        }
        fun groups(g: CompetitionView.Groups) {
            append("groups ${g.title}\n")
            for (t in g.groups) {
                append(" group ${t.label} ${t.bonus}\n")
                for (r in t.rows) append("  ${r.key} ${r.played} ${r.wins} ${r.draws} ${r.losses} ${r.points} ${r.diff} ${r.sweeps}\n")
            }
            g.knockout?.let(::bracket)
            for ((k, s) in g.squads) append(" squad $k ${s.joinToString(",")}\n")
        }
        when (v) {
            is CompetitionView.Bracket -> bracket(v)
            is CompetitionView.Groups -> groups(v)
            is CompetitionView.Sections -> {
                append("sections ${v.title}\n")
                v.parts.forEach(::groups)
            }
        }
    }

    /**
     * Everything a competition shows the season: its stages and their weeks in a 38- and a
     * 51-week season, every fixture it hands out (played as it opens, read back from its save
     * after each count of [reloadAt] fixtures), the news, the counts, the honours, the view and
     * the save.
     */
    fun fingerprint(first: Competition, reloadAt: Set<Int>, season: Int = 1, seed: Long = 4242L): String = buildString {
        var c = first
        val cals = listOf(38, 51).map { r -> SeasonCalendar.build(r, listOf(c), emptyList()) }
        for (s in c.stages()) append("stage ${s.label} ${s.opensAt} ${s.target}\n")
        for (cal in cals) append("weeks ${c.stages().indices.map { cal.week(c.comp, it) }}\n")
        for (e in c.opening()) append("open ${e.encode()}\n")
        append("counts ${c.stageCounts().map { "${it.played}/${it.total}" }} ${c.fixturesTotal} ${c.fixturesLeft}\n")
        var handed = 0
        var p = 0.0
        while (!c.finished) {
            val f = c.next(p, emptySet())
            if (f == null) {
                check(p <= 1.0) { "stuck" }
                p += 0.01
                continue
            }
            val s = c.stageOf(f)
            append("f ${f.comp.code} ${f.id} ${f.a} ${f.b} ${f.seed} ${f.kind} ${f.legs} ${f.firstTo} ${f.label} ${f.rated} ${f.lineupA} ${f.lineupB} $s ${cals.map { it.week(c.comp, s) }}\n")
            for (e in c.record(play(f))) append("e ${e.encode()}\n")
            if (++handed in reloadAt) {
                val saved = text(c)
                append(saved)
                val back = Competitions.codecs.getValue(c.comp).decode(saved.lines().filter { it.isNotEmpty() }, season, seed)
                assertEquals(saved, text(back), "round trip at $handed")
                c = back
            }
            append("counts ${c.stageCounts().map { "${it.played}/${it.total}" }} ${c.fixturesLeft}\n")
        }
        for (h in c.honours()) append("h ${h.encode()}\n")
        append(viewText((c as ViewableCompetition).view()))
        append(text(c))
    }
}

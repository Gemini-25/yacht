package yacht.engine.league

/** A transfer story's stage (design ENG 3.1); [code] is saved. */
enum class StoryStage(val code: Char, val label: String) {
    RUMOUR('R', "소문"),
    TALKS('T', "협상 중"),
    DONE('D', "성사"),
    COLLAPSED('C', "무산"),
    HIJACKED('H', "가로채기"),
    ;

    companion object {
        fun of(code: Char): StoryStage? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A transfer story (이적설, design ENG 3.1): [buyer] wants member [key] of [seller] for [fee] (the
 * asked fee when the rumour started in week [rumourWeek]); talks from [talksWeek] (0: none). How
 * it ended: [reason] '-' (none yet), 'P' 가격, 'S' 잔류, 'H' 가로채기 (by club [by]), 'M' 감독 교체,
 * 'F' 재정, 'R' 규정; [winter] when it belongs to the winter window. [id] is unique among the
 * stories kept.
 */
data class MarketStory(
    val id: Int, val season: Int, val stage: StoryStage, val key: String, val buyer: String, val seller: String,
    val fee: Int, val rumourWeek: Int, val talksWeek: Int,
    val reason: Char, val winter: Boolean, val by: String?,
)

/**
 * A notable transfer (`tx`, design ENG 3.2) after [season]: member [key] from club [from] to [to]
 * (null: none, a free agent or abroad) for [fee], move type [type] (a [ClubMoveType] code), with
 * [flags]: W world record, L league record, C club record, V rival, K release clause, D deadline
 * day, F forced sale ("" for none).
 */
data class NotableMove(val season: Int, val type: Char, val key: String, val from: String?, val to: String?, val fee: Int, val flags: String)

/**
 * The club world's market part: the stories (`ms`: the current season's open ones and the last
 * off-season's outcomes) and the notable transfers (`tx`, newest seasons). The market's logic
 * (talks, rounds, caps, the guard, clauses, loans, deadline day, records) is P7's, the weekly tick
 * P13's.
 */
class MarketBook {
    internal val stories = ArrayList<MarketStory>()
    internal val notable = ArrayList<NotableMove>()

    /** The stories of [season] (any stage), by id. */
    fun stories(season: Int): List<MarketStory> = stories.filter { it.season == season }.sortedBy { it.id }

    /** The notable transfers kept, oldest first. */
    val moves: List<NotableMove> get() = notable.toList()

    companion object {
        private val MS = LineParts.of<ClubWorld>("ms", { w, out ->
            for (s in w.market.stories) {
                out.append("ms ${s.id} ${s.season} ${s.stage.code} ${Field.esc(s.key)} ${s.buyer} ${s.seller} ${s.fee} ${s.rumourWeek} ${s.talksWeek} ")
                out.append("${s.reason} ${LineParts.bit(s.winter)} ${s.by ?: "-"}\n")
            }
        }) { w, f ->
            require(f.size >= 13)
            val stage = StoryStage.of(f[3].single()) ?: return@of false
            w.market.stories += MarketStory(
                f[1].toInt(), f[2].toInt(), stage, Field.unesc(f[4]), f[5], f[6], f[7].toInt(), f[8].toInt(), f[9].toInt(),
                f[10].single(), f[11] == "1", f[12].takeIf { it != "-" },
            )
            true
        }

        private val TX = LineParts.of<ClubWorld>("tx", { w, out ->
            for (m in w.market.notable) out.append("tx ${m.season} ${m.type} ${Field.esc(m.key)} ${m.from ?: "-"} ${m.to ?: "-"} ${m.fee} ${m.flags.ifEmpty { "-" }}\n")
        }) { w, f ->
            require(f.size >= 8)
            w.market.notable += NotableMove(f[1].toInt(), f[2].single(), Field.unesc(f[3]), f[4].takeIf { it != "-" }, f[5].takeIf { it != "-" }, f[6].toInt(), if (f[7] == "-") "" else f[7])
            true
        }

        /** `ms tx`. */
        val PARTS: List<LinePart<ClubWorld>> = listOf(MS, TX)
    }
}

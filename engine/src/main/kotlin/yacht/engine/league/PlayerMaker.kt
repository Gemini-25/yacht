package yacht.engine.league

import java.util.Random

/**
 * A player as the player describes it (선수 만들기): who it is, how good it is and how it plays.
 * [age] is its age now; [play] its style (a recipe at intensity k, a second style, an overlay,
 * and the tastes and situational habits a drawn or edited player brings along); [keep] keeps it in
 * its prime forever (no aging, growth, decline or retirement).
 */
data class PlayerDraft(
    val name: String,
    val gender: Gender,
    val nation: String,
    val hometown: String,
    val age: Int,
    val abilities: Abilities,
    val potential: Int,
    /** Its mistakes come by feel (a near miss is likelier than a blunder), else as slips. */
    val feel: Boolean,
    /** The temper a 멘탈 under [Profiles.CALM] brings. */
    val temper: Temper,
    val play: PlayGene,
    val keep: Boolean = false,
) {
    /** A copy with the style's recipe [primary] (the overlay's moods kept for a chameleon). */
    fun withPrimary(primary: String): PlayerDraft =
        copy(play = play.copy(primary = primary, k = if (PlayStyles.recipe(primary)?.scales == true) play.k else 1.0, secondary = play.secondary?.takeIf { it != primary }))

    fun withOverlay(overlay: String?): PlayerDraft = copy(play = play.copy(overlay = overlay, moods = if (overlay == "chameleon") play.moods.ifEmpty { PlayerMaker.MOODS } else emptyList()))
}

/** Making and remaking players from drafts. */
object PlayerMaker {
    const val MIN_AGE = 16
    const val MAX_AGE = 45
    const val NAME_LENGTH = 20
    const val MIN_K = 0.7
    const val MAX_K = 1.7

    /** A chameleon's styles of the day when it was made without any. */
    val MOODS = listOf("hunter", "purist", "straight")

    /** An empty draft: a 20-year-old max-expectation player of middling abilities. */
    fun blank(nation: String = Nations.KR.code): PlayerDraft = PlayerDraft(
        "", Gender.MALE, nation, Nations.of(nation)?.cities?.names?.firstOrNull() ?: "", 20,
        Abilities(60, 60, 60, 60), 75, true, Temper.TILT, PlayGene("ev", v = PlayGene.V2),
    )

    /** A drawn player (🎲 무작위) as a draft, named apart from [taken]. */
    fun random(rng: Random, taken: Set<String>): PlayerDraft {
        // A generated player of an index no league reaches: drawn exactly as the league draws its own.
        val r = PlayerGenerator.recruit(RANDOM_BASE + rng.nextInt(RANDOM_RANGE), 1, rng.nextBoolean(), taken)
        return of(r, 1)
    }

    /** [record] as a draft for editing ([season] gives its age). */
    fun of(record: PlayerRecord, season: Int): PlayerDraft {
        val id = record.identity ?: Profiles.legacy(record, record.born)
        return PlayerDraft(
            record.name, id.gender, id.nation, record.hometown, id.age(season, record.born), id.abilities, id.potential, id.feel, id.temperKind,
            record.spec.play ?: PlayGene("ev", v = PlayGene.V2), record.keep,
        )
    }

    /** Why [draft] cannot be saved (Korean, 해요체), or null when it can; [taken] are the names in use (its own excluded). */
    fun problem(draft: PlayerDraft, taken: Set<String>): String? {
        val name = draft.name.trim()
        return when {
            name.isEmpty() -> "이름을 적어 주세요."
            name.length > NAME_LENGTH -> "이름은 ${NAME_LENGTH}자까지예요."
            name in taken -> "같은 이름의 선수가 이미 있어요."
            Nations.of(draft.nation) == null -> "국적을 골라 주세요."
            PlayStyles.recipe(draft.play.primary) == null -> "주 유형을 골라 주세요."
            else -> null
        }
    }

    /**
     * The record of [draft] under [key], debuting in [season] (or, remade from [old], keeping its
     * debut, parents, peak and best abilities): its identity with the age now, the style with its
     * abilities applied ([Profiles.apply]).
     */
    fun build(draft: PlayerDraft, key: String, season: Int, old: PlayerRecord?): PlayerRecord {
        val born = old?.born ?: season
        val age = draft.age.coerceIn(MIN_AGE, MAX_AGE)
        val abilities = Abilities.of(draft.abilities.toList().map { it.coerceIn(1, 99) })
        val nation = Nations.of(draft.nation)?.code ?: Nations.KR.code
        val id = Identity(
            nation, draft.gender, age - (season - born), abilities, draft.potential.coerceIn(1, 99),
            draft.temper.takeIf { it != Temper.NONE } ?: Temper.TILT, draft.feel,
            old?.identity?.peakAbl, old?.identity?.lastAbl,
        )
        val g = draft.play
        val recipe = PlayStyles.recipe(g.primary) ?: PlayStyles.recipe("ev")!!
        val gene = g.copy(
            primary = recipe.key,
            k = if (recipe.scales) PlayerGenerator.round(g.k.coerceIn(MIN_K, MAX_K), 2) else 1.0,
            secondary = g.secondary?.takeIf { it != recipe.key && PlayStyles.recipe(it) != null },
            overlay = g.overlay?.takeIf { PlayStyles.overlay(it) != null },
            moods = if (g.overlay == "chameleon") g.moods.ifEmpty { MOODS } else emptyList(),
            v = PlayGene.V2,
        )
        val name = draft.name.trim().take(NAME_LENGTH)
        val spec = Profiles.apply((old?.spec ?: CustomAiSpec(key, name)).copy(id = key, name = name, play = gene), id)
        val origin = old?.origin ?: Origin.CREATED
        return PlayerRecord(
            key, name, draft.hometown.trim().ifEmpty { Nations.of(nation)?.cities?.names?.firstOrNull() ?: "" }, origin, born,
            old?.parentA, old?.parentB, spec, old?.peak ?: Double.NaN, id, draft.keep,
        )
    }

    /** Indices for drawn drafts: far past any league's own players. */
    private const val RANDOM_BASE = 5_000_000
    private const val RANDOM_RANGE = 1_000_000
}

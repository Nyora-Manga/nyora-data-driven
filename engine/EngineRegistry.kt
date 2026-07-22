package app.nyora.data.engine

/**
 * EngineRegistry — the single, code-loading-free factory that maps a data-driven source's
 * `engine` id (a plain String) to the bundled [SourceEngine] implementation that renders it.
 *
 * A source is DATA, not code: a repo-supplied [SourceDef] carries an `engine` id string
 * (e.g. "madara", "asurascans", "mangadex", ...) and the registry looks that id up here to
 * construct the matching engine. There is NO reflection, NO downloaded code and NO JavaScript:
 * every id resolves to a compiled, bundled factory below.
 *
 * WHY STRING KEYS (not the [EngineId] enum): the shared [EngineId] enum in SourceEngine.kt models
 * only the first two engines (MADARA, MANGAREADER) and is owned by the contract — this agent must
 * not modify it (self-contained new files only). Every later engine family/bespoke engine therefore
 * ships its factory keyed by a String engine id (its `ENGINE_KEY` / `engineKey`), and several of
 * those factories are plain objects/classes that intentionally do NOT implement [EngineFactory]
 * (whose `engineId: EngineId` cannot name them yet) or implement it with an `engineId` that throws
 * until the enum is extended. To stay uniform and to avoid ever touching those throwing `engineId`
 * getters, this registry stores a construction lambda per id rather than an [EngineFactory] value.
 *
 * The two enum-modeled engines are keyed by [EngineId.key] so `def.engine.key` continues to resolve
 * them; all others are keyed by their published string id. When the shared [EngineId] enum (and the
 * SourceDef.schema.json `engine` enum) are later extended, no change is required here beyond an
 * optional switch of the two enum members to their `EngineId.key`, which already matches.
 */
object EngineRegistry {

    /** Constructs a [SourceEngine] for one [SourceDef] + [EngineContext] (mirrors [EngineFactory.create]). */
    fun interface Creator {
        fun create(def: SourceDef, context: EngineContext): SourceEngine
    }

    /**
     * engine id (String) -> engine constructor. Ordered for readability: the two enum-modeled
     * generic engines first, then the generic engine families, then the bespoke single-site engines.
     * Keys are the exact `engine` strings a repo-supplied SourceDef uses.
     */
    private val creators: Map<String, Creator> = linkedMapOf(
        // --- enum-modeled generic engines (keyed by EngineId.key) ---
        EngineId.MADARA.key       to Creator(MadaraEngineFactory::create),                 // "madara"
        EngineId.MANGAREADER.key  to Creator { def, ctx -> MangaReaderEngineFactory().create(def, ctx) }, // "mangareader"

        // --- generic engine families (String-keyed) ---
        "animebootstrap" to Creator(AnimebootstrapEngineFactory::create),
        "asurascans"     to Creator(AsuraScansEngineFactory::create),
        "batoto"         to Creator { def, ctx -> BatotoEngineFactory().create(def, ctx) },
        "cupfox"         to Creator(CupfoxEngineFactory::create),
        "fmreader"       to Creator(FmreaderEngineFactory::create),
        "foolslide"      to Creator(FoolslideEngineFactory::create),
        "fuzzydoodle"    to Creator(FuzzydoodleEngineFactory::create),
        "galleryadults"  to Creator(GalleryadultsEngineFactory::create),
        "gattsu"         to Creator(GattsuEngineFactory::create),
        "heancms"        to Creator(HeancmsEngineFactory::create),
        "heancmsalt"     to Creator(HeancmsaltEngineFactory::create),
        "hotcomics"      to Creator(HotcomicsEngineFactory::create),
        "iken"           to Creator(IkenEngineFactory::create),
        "keyoapp"        to Creator(KeyoappEngineFactory::create),
        "liliana"        to Creator(LilianaEngineFactory::create),
        "madtheme"       to Creator(MadthemeEngineFactory::create),
        "manga18"        to Creator(Manga18EngineFactory::create),
        "mangabox"       to Creator(MangaboxEngineFactory::create),
        "mmrcms"         to Creator(MmrcmsEngineFactory::create),
        "natsu"          to Creator(NatsuEngineFactory::create),
        "pizzareader"    to Creator(PizzareaderEngineFactory::create),
        "scan"           to Creator(ScanEngineFactory::create),
        "signedrest"     to Creator(SignedRestEngineFactory::create),
        "sinmh"          to Creator(SinmhEngineFactory::create),
        "wpcomics"       to Creator(WpcomicsEngineFactory::create),
        "zeistmanga"     to Creator(ZeistmangaEngineFactory::create),
        "zmanga"         to Creator(ZmangaEngineFactory::create),

        // --- bespoke single-site engines (String-keyed) ---
        "guya"           to Creator { def, ctx -> GuyaEngineFactory().create(def, ctx) },
        "mangadex"       to Creator { def, ctx -> MangaDexEngineFactory().create(def, ctx) },
        "mangadventure"  to Creator { def, ctx -> MangadventureEngineFactory().create(def, ctx) },
        "mangago"        to Creator { def, ctx -> MangagoEngineFactory().create(def, ctx) },
        "onemanga"       to Creator(OnemangaEngineFactory::create),

        // --- ported from custom kotatsu parsers (real-user sources missing from the catalogue) ---
        "atsumoe"        to Creator(AtsuMoeEngineFactory::create),
        "baozimh"        to Creator(BaozimhEngineFactory::create),
        "demonicscans"   to Creator(DemonicScansEngineFactory::create),
        "mangaball"      to Creator(MangaBallEngineFactory::create),
        "mangakawaii"    to Creator(MangaKawaiiEngineFactory::create),
        "mangapill"      to Creator(MangaPillEngineFactory::create),
        "webtoons"       to Creator(WebtoonsEngineFactory::create),
        "weebcentral"    to Creator(WeebCentralEngineFactory::create),
    )

    /** Every engine id this build can render, in declaration order. */
    val engineIds: Set<String> get() = creators.keys

    /** Whether an engine id is bundled in this build. */
    fun supports(engineId: String): Boolean = engineId in creators

    /**
     * Resolve an engine id to its [Creator], or null if this build has no such engine (e.g. a repo
     * references an engine newer than the installed app).
     */
    fun find(engineId: String): Creator? = creators[engineId]

    /**
     * Construct the engine for [engineId], binding it to [def] + [context].
     * @throws IllegalArgumentException if no bundled engine matches [engineId].
     */
    fun create(engineId: String, def: SourceDef, context: EngineContext): SourceEngine =
        (creators[engineId] ?: throw IllegalArgumentException(
            "Unknown engine id \"$engineId\"; bundled engines: ${creators.keys.joinToString()}",
        )).create(def, context)

    /**
     * Convenience: construct the engine for a [SourceDef] whose enum-modeled [SourceDef.engine] is
     * one of the two contract engines (madara/mangareader). String-keyed engines must be created via
     * [create] with their explicit id, since the shared [EngineId] enum cannot name them.
     */
    fun create(def: SourceDef, context: EngineContext): SourceEngine =
        create(def.engine.key, def, context)
}

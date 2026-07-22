package app.nyora.core.model

import app.nyora.data.engine.ContentType

/**
 * Nyora canonical domain model — the data-driven target the bundled engines parse into.
 *
 * These types mirror kotatsu's `Manga` / `MangaChapter` / `MangaPage` / `MangaTag` 1:1, adapted to
 * Nyora's canonical form: String ids (the relative href), epoch-millis dates, and a `source` String
 * (the [SourceDef.id]) carried on every record. The field shapes here were reverse-engineered from
 * the ~33 engines' construction call-sites so the whole engine set compiles unchanged.
 *
 * COLLECTION TYPES: kotatsu uses `Set`; this port's engines assign a mix of `List` and
 * `LinkedHashSet` to `tags` / `authors` / `altTitles`, so those fields are typed as the common
 * supertype [Collection] to accept both without touching engine logic.
 */
data class Manga(
    /** Stable String id — the relative href, optionally namespaced by source id. */
    val id: String,
    /** Primary display title. */
    val title: String,
    /** Alternative/secondary titles (kotatsu `altTitles: Set<String>`). */
    val altTitles: Collection<String> = emptyList(),
    /** Href RELATIVE to the source domain; resolved to absolute at load time. */
    val url: String,
    /** Fully-qualified public URL for sharing / opening in a browser. */
    val publicUrl: String = "",
    /** 0f..1f rating, or -1f when unknown (RATING_UNKNOWN). */
    val rating: Float = RATING_UNKNOWN,
    /** True when the source/entry is adult (kotatsu `isNsfw`). */
    val isNsfw: Boolean = false,
    /** Fine-grained content rating; null when the engine only knows [isNsfw]. */
    val contentRating: ContentRating? = null,
    /** Small cover image url (may be relative or absolute). */
    val coverUrl: String? = null,
    /** Genre/tag set. */
    val tags: Collection<MangaTag> = emptyList(),
    /** Publication state, or null when unknown. */
    val state: MangaState? = null,
    /** Author / artist credits. */
    val authors: Collection<String> = emptyList(),
    /** Large / high-res cover url, when the source exposes one. */
    val largeCoverUrl: String? = null,
    /** Description / synopsis HTML or text. */
    val description: String? = null,
    /** Ordered chapter list (ascending reading order); null on browse stubs until getDetails. */
    val chapters: List<MangaChapter>? = null,
    /** The owning source id ([SourceDef.id]). */
    val source: String = "",
) {
    companion object {
        const val RATING_UNKNOWN: Float = -1f
    }
}

/**
 * One chapter of a [Manga].
 * @property number 1-based sequential number in ascending reading order (kotatsu `number: Float`).
 * @property volume volume number, 0 when unknown.
 * @property uploadDate epoch MILLIS (never an ISO string).
 */
data class MangaChapter(
    val id: String,
    val title: String? = null,
    val number: Float = 0f,
    val volume: Int = 0,
    val url: String,
    val scanlator: String? = null,
    val uploadDate: Long = 0L,
    val branch: String? = null,
    val source: String = "",
)

/**
 * One image page of a [MangaChapter]. [url] leads; [id] defaults to [url] because some engines
 * construct a page from just its url (kotatsu derives the id from the url too).
 */
data class MangaPage(
    val url: String,
    val id: String = url,
    val preview: String? = null,
    val source: String = "",
    // Optional per-page request headers merged into the image download (on top of the source
    // Referer). Used by engines whose images need a per-page value at fetch time — e.g. MangaPlus
    // carries its XOR decryption key here for an app-side image interceptor.
    val headers: Map<String, String> = emptyMap(),
)

/** A genre / category tag. */
data class MangaTag(
    val title: String,
    val key: String,
    val source: String = "",
)

/** Sort orders a source may expose (superset across all engines). */
enum class SortOrder {
    UPDATED,
    UPDATED_ASC,
    POPULARITY,
    POPULARITY_ASC,
    NEWEST,
    NEWEST_ASC,
    ALPHABETICAL,
    ALPHABETICAL_DESC,
    RATING,
    RATING_ASC,
    RELEVANCE,
    ADDED,
    ADDED_ASC,
}

/** Publication state of a series. */
enum class MangaState {
    ONGOING,
    FINISHED,
    ABANDONED,
    PAUSED,
    UPCOMING,
}

/** Coarse content rating (kotatsu ContentRating). */
enum class ContentRating {
    SAFE,
    SUGGESTIVE,
    ADULT,
}

/**
 * Browse/search constraints (kotatsu `MangaListFilter`). Collections are [Set] (kotatsu semantics);
 * [EMPTY] is the no-filter value. `query` carries free-text search when funnelled through
 * [SourceEngine.search].
 */
data class MangaListFilter(
    val query: String? = null,
    val tags: Set<MangaTag> = emptySet(),
    val tagsExclude: Set<MangaTag> = emptySet(),
    val states: Set<MangaState> = emptySet(),
    val types: Set<ContentType> = emptySet(),
    val contentRating: Set<ContentRating> = emptySet(),
    val year: Int = 0,
    val author: String? = null,
    val locale: java.util.Locale? = null,
) {
    companion object {
        val EMPTY = MangaListFilter()
    }
}

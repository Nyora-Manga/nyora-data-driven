package app.nyora.data.engine

import org.jsoup.nodes.Element

/**
 * Shared Jsoup helpers the engines expect but Jsoup does not provide out of the box.
 *
 * kotatsu ships an `Element.selectLast(css)` util (the last element matching a CSS query). Several
 * engines (Madara, Keyoapp, Fuzzydoodle, ...) call it; it is provided once here for the whole
 * `app.nyora.data.engine` package so no per-engine copy is needed.
 */
internal fun Element.selectLast(cssQuery: String): Element? = select(cssQuery).lastOrNull()

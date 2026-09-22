package app.nyora.data.engine

/**
 * Named-capturing-group access that works on every client.
 *
 * `MatchGroupCollection["name"]` compiles to `java.util.regex.Matcher#group(String)` /
 * `#start(String)`, which Android only gained in API 26 — on API 23-25 it throws
 * `NoSuchMethodError` at runtime. Resolving the group's ordinal position from the pattern and
 * reading it by index uses only API-1 regex methods, so the same engine sources run on the oldest
 * supported Android release and on the JVM.
 */
internal fun MatchResult.namedGroup(pattern: String, name: String): String? {
	val index = namedGroupIndex(pattern, name) ?: return null
	return groups[index]?.value
}

/**
 * The 1-based capturing-group index of `(?<[name]>…)` in [pattern], or null when it declares no
 * such group. Escapes and character classes are skipped so a `\(` or a `[(]` is not counted, and
 * only `(?<name>` captures — every other `(?…` form (`(?:`, `(?=`, `(?!`, `(?<=`, `(?<!`, `(?>`,
 * inline flags) does not.
 */
internal fun namedGroupIndex(pattern: String, name: String): Int? {
	var group = 0
	var inCharClass = false
	var i = 0
	while (i < pattern.length) {
		when (pattern[i]) {
			'\\' -> i++ // the next character is literal, whatever it is
			'[' -> if (!inCharClass) inCharClass = true
			']' -> inCharClass = false
			'(' -> if (!inCharClass) {
				if (pattern.getOrNull(i + 1) != '?') {
					group++ // plain capturing group
				} else {
					val declared = groupNameAt(pattern, i + 2)
					if (declared != null) {
						group++
						if (declared == name) return group
					}
				}
			}
			else -> Unit
		}
		i++
	}
	return null
}

/** Reads the `<name>` of a named group starting at [start], or null for any other `(?…` form. */
private fun groupNameAt(pattern: String, start: Int): String? {
	if (pattern.getOrNull(start) != '<') return null
	val end = pattern.indexOf('>', start + 1)
	if (end < 0) return null
	val name = pattern.substring(start + 1, end)
	// java.util.regex accepts [a-zA-Z][a-zA-Z0-9]* only; this also rejects `(?<=` and `(?<!`.
	if (name.isEmpty() || !name[0].isLetter() || !name.all { it.isLetterOrDigit() }) return null
	return name
}

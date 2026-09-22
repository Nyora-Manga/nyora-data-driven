package app.nyora.data.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineRegexExtTest {

    @Test
    fun resolvesTheKeyoappCdnHostWithoutNamedGroupLookup() {
        val pattern = """realUrl\s*=\s*`[^`]+//(?<host>[^/]+)"""
        val match = Regex(pattern).find("var realUrl = `https://cdn.example.org/uploads`;")
        assertEquals("cdn.example.org", match?.namedGroup(pattern, "host"))
    }

    @Test
    fun countsOnlyCapturingGroupsBeforeTheNamedOne() {
        assertEquals(1, namedGroupIndex("""(?:x)(?<a>y)""", "a"))
        assertEquals(2, namedGroupIndex("""(x)(?<a>y)""", "a"))
        assertEquals(4, namedGroupIndex("""(?<a>x)(?:y)(?<b>z)(w)(?<c>v)""", "c"))
    }

    @Test
    fun ignoresParenthesesThatDoNotOpenAGroup() {
        assertEquals(1, namedGroupIndex("""\((?<a>y)""", "a"))
        assertEquals(1, namedGroupIndex("""[(](?<a>y)""", "a"))
        assertEquals(1, namedGroupIndex("""(?<=x)(?<a>y)""", "a"))
        assertEquals(1, namedGroupIndex("""(?!no)(?<a>x)""", "a"))
        assertEquals(1, namedGroupIndex("""(?i)(?<a>y)""", "a"))
    }

    @Test
    fun returnsNullForAnUndeclaredGroup() {
        assertNull(namedGroupIndex("""(?<a>x)""", "b"))
        val pattern = """/(?<slug>[^/]+)"""
        assertNull(Regex(pattern).find("/one")?.namedGroup(pattern, "missing"))
    }

    @Test
    fun resolvesEveryNamedGroupOfAMangaReaderApiTemplate() {
        val pattern = """/manga/(?<slug>[^/]+)/chapter-(?<num>\d+)"""
        val match = Regex(pattern).find("/manga/blue-lock/chapter-214")
        assertEquals("blue-lock", match?.namedGroup(pattern, "slug"))
        assertEquals("214", match?.namedGroup(pattern, "num"))
    }
}

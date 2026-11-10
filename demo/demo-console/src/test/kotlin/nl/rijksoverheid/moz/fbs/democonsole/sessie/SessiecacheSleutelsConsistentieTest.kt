package nl.rijksoverheid.moz.fbs.democonsole.sessie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * De console leest en wijzigt de sessie-administratie van de sessiecache zonder van die bibliotheek
 * af te hangen: een demo-module hoort niet aan een stelsel-library te hangen. De compiler ziet de
 * koppeling dus niet. Deze test leest de broncode van de bibliotheek en faalt zodra een aanname van
 * [SessiecacheSleutels] of [RedisSessieOpslag] daar niet meer staat.
 *
 * Faalscenario zonder deze test: de bibliotheek hernoemt `:sessies` of legt het verloop anders vast.
 * De console vindt dan geen enkele sessie meer en toont overal "0" — en de knoppen doen niets — terwijl
 * er wel sessies lopen. Een versiebump (`v4` naar `v5`) hoort deze test níet rood te maken: de console
 * zoekt over alle versies.
 */
class SessiecacheSleutelsConsistentieTest {

    private val berichtenCache = lees("berichten/BerichtenCache.kt")

    private val register = lees("sessie/SessieRegister.kt")

    private val scripts = lees("sessie/SessieScripts.kt")

    @Test
    fun `de sleutels van een ontvanger beginnen met het versieprefix en het kenmerk`() {
        val prefix = Regex("""const val SLEUTEL_PREFIX = "([^"]+)"""").find(berichtenCache)?.groupValues?.get(1)

        assertTrue(prefix != null, "SLEUTEL_PREFIX niet gevonden in BerichtenCache.kt; klopt de vorm nog?")
        assertTrue(
            Regex("""berichtensessiecache:[^:]+:""").matches(prefix!!),
            "SLEUTEL_PREFIX '$prefix' heeft niet de vorm die SessiecacheSleutels herkent",
        )
        assertTrue(
            "fun cacheKey(ontvanger: Identificatienummer): String = SLEUTEL_PREFIX + kenmerk(ontvanger)" in berichtenCache,
            "de cache-key is niet meer prefix plus kenmerk",
        )
    }

    @Test
    fun `het kenmerk is de sha256 in hex over de canonieke ontvanger`() {
        val kenmerk = Regex("""fun kenmerk\(ontvanger: Identificatienummer\): String \{(.*?)\n {8}}""", RegexOption.DOT_MATCHES_ALL)
            .find(berichtenCache)
            ?.groupValues
            ?.get(1)

        assertTrue(kenmerk != null, "kenmerk() niet gevonden in BerichtenCache.kt; klopt de vorm nog?")
        assertTrue("ontvanger.toCanonicalString()" in kenmerk!!, "het kenmerk hasht niet meer de canonieke ontvanger")
        assertTrue("toByteArray(Charsets.UTF_8)" in kenmerk, "het kenmerk hasht niet meer de UTF-8-bytes")
        assertTrue("HEX.formatHex(digest)" in kenmerk, "het kenmerk is niet meer de hex-weergave")
        assertTrue("""MessageDigest.getInstance("SHA-256")""" in berichtenCache, "het kenmerk is niet meer een SHA-256")
    }

    @Test
    fun `de sessies, hun plafonds en de verloopindex staan waar de console ze zoekt`() {
        assertTrue(""""${'$'}k:sessies",""" in register, "de sessie-ZSET heet niet meer \$k:sessies")
        assertTrue(""""${'$'}k:sessies:plafond",""" in register, "de plafonds staan niet meer onder \$k:sessies:plafond")

        val prefix = Regex("""const val SLEUTEL_PREFIX = "([^"]+)"""").find(berichtenCache)?.groupValues?.get(1)
        val index = Regex("""const val VERLOOP_INDEX = "([^"]+)"""").find(register)?.groupValues?.get(1)

        assertEquals("${prefix}verloop", index, "de verloopindex is niet meer het versieprefix plus 'verloop'")
    }

    @Test
    fun `de score van een sessie is haar verloop en een score tot en met nu is verlopen`() {
        // Zo leest de console "lopend" (score later dan nu) en zo beëindigt hij een sessie: score op nu.
        assertTrue(
            "1. `${'$'}k:sessies` — ZSET, lid `sha256(sessie-id)`, score het verloop in ms;" in scripts,
            "de score van de sessie-ZSET is niet meer het verloop in ms",
        )
        assertTrue(
            "if not score or tonumber(score) <= nu then" in scripts,
            "een sessie met een score tot en met nu telt niet meer als verlopen",
        )
        assertTrue(
            "return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)" in scripts,
            "'nu' komt niet meer op dezelfde manier uit Redis",
        )
    }

    @Test
    fun `de veegtaak vindt een ontvanger met een verlopen score in de index en ruimt hem op`() {
        // Daarop rust het beëindigen vanuit de console: de index-entry op "nu" zetten laat de veegtaak
        // de laatste sessie opruimen, mét een regel in het logboek.
        assertTrue(
            "redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', tijdInMs(), 'LIMIT', 0, tonumber(ARGV[1]))" in scripts,
            "de veegtaak kiest zijn kandidaten niet meer op een verstreken score in de index",
        )

        val veeg = Regex("""val VEEG = GEDEELD \+ "\\n\\n" \+ \"\"\"(.*?)\"\"\"""", RegexOption.DOT_MATCHES_ALL)
            .find(scripts)
            ?.groupValues
            ?.get(1)

        assertTrue(veeg != null, "het script VEEG niet gevonden in SessieScripts.kt; klopt de vorm nog?")
        assertTrue("ruimVerlopenOp(nu)" in veeg!!, "VEEG haalt de verlopen sessies niet meer weg")
        assertTrue("purge()" in veeg, "VEEG ruimt de gegevens niet meer op")
    }

    private fun lees(pad: String): String {
        val bron = File("$BIBLIOTHEEK/$pad")

        assertTrue(bron.isFile, "$pad niet gevonden op ${bron.absolutePath}")

        return bron.readText()
    }

    private companion object {

        const val BIBLIOTHEEK =
            "../../libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache"
    }
}

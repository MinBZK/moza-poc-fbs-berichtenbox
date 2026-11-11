package nl.rijksoverheid.moz.fbs.democonsole.sessie

import java.util.concurrent.ConcurrentHashMap

/**
 * De sessie-administratie in het geheugen, met dezelfde betekenis als in Redis: per sessie-ZSET de
 * leden met hun verloop, per plafond-HASH het plafond, en de verloopindex. [laatVerlopen] doet wat
 * het Lua-script in [RedisSessieOpslag] doet, zodat de tests van [SessieBeheer] de uitkomst kunnen
 * nalopen en niet alleen de aanroep. Het script zelf draait in [LaatVerlopenRedisTest].
 */
class GeheugenSessieOpslag : SessieOpslag {

    @Volatile
    var nu: Long = NU

    /** Sessie-ZSET-sleutel → (sessie-hash → verloop in ms). */
    val zsets: MutableMap<String, MutableMap<String, Long>> = ConcurrentHashMap()

    /** Plafond-sleutel → (sessie-hash → plafond in ms). */
    val plafondsPerSleutel: MutableMap<String, MutableMap<String, Long>> = ConcurrentHashMap()

    /** Verloopindex-sleutel → (kenmerk → score in ms). */
    val indexen: MutableMap<String, MutableMap<String, Long>> = ConcurrentHashMap()

    fun leeg() {
        nu = NU
        zsets.clear()
        plafondsPerSleutel.clear()
        indexen.clear()
    }

    /** Zet een sessie zoals de start van de bibliotheek dat doet, inclusief de index-entry. */
    fun zet(administratie: Sessieadministratie, hash: String, verloop: Long, plafond: Long? = verloop + UUR) {
        zsets.getOrPut(administratie.sessies) { ConcurrentHashMap() }[hash] = verloop

        if (plafond != null) plafondsPerSleutel.getOrPut(administratie.plafonds) { ConcurrentHashMap() }[hash] = plafond

        val index = indexen.getOrPut(administratie.verloopindex) { ConcurrentHashMap() }

        index[administratie.kenmerk] = maxOf(index[administratie.kenmerk] ?: Long.MIN_VALUE, verloop)
    }

    override fun nuMs(): Long = nu

    override fun sleutels(patroon: String): List<String> {
        val glob = Regex(patroon.split("*").joinToString(".*") { Regex.escape(it) })

        return zsets.keys.filter { glob.matches(it) }.sorted()
    }

    override fun leden(administratie: Sessieadministratie): List<Sessielid> =
        zsets[administratie.sessies].orEmpty().map { (hash, verloop) -> Sessielid(hash, verloop) }

    override fun plafonds(administratie: Sessieadministratie, hashes: List<String>): Map<String, Long> =
        plafondsPerSleutel[administratie.plafonds].orEmpty().filterKeys { it in hashes }

    override fun laatVerlopen(administratie: Sessieadministratie, hashes: List<String>): Int {
        val leden = zsets[administratie.sessies] ?: return 0
        val beeindigd = hashes.distinct().filter { (leden[it] ?: Long.MIN_VALUE) > nu }

        beeindigd.forEach { leden[it] = nu }

        if (beeindigd.isNotEmpty()) {
            indexen.getOrPut(administratie.verloopindex) { ConcurrentHashMap() }[administratie.kenmerk] = leden.values.max()
        }

        return beeindigd.size
    }

    companion object {

        const val NU = 1_800_000_000_000L

        const val MINUUT = 60_000L

        const val UUR = 60 * MINUUT
    }
}

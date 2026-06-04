package nl.rijksoverheid.moz.fbs.common.profiel

import nl.rijksoverheid.moz.fbs.common.identificatie.Oin

/**
 * Helpers rond `PartijResponse`-interpretatie. Bron van waarheid voor de
 * mapping tussen het externe Profiel-service-contract (string-voorkeurType,
 * string-waarde, OIN-identificatieType) en de domein-vraag "is deze partij
 * opted-in voor de berichtenbox van afzender X?".
 *
 * Wordt gebruikt door zowel:
 *  - `berichtensessiecache` (verzamel alle opted-in afzender-OINs van een ontvanger),
 *  - `berichtenmagazijn` (controleer toestemming voor één specifieke afzender).
 *
 * Eén plek voor de constanten — uitbreiding hier = contract-wijziging met het
 * Profiel-team, niet een config-knop. Anders zou een drift tussen de twee
 * services ongezien een security-gap kunnen worden (magazijn weigert, cache
 * staat toe of vice versa).
 */
object ProfielVoorkeuren {

    /** VoorkeurType in het externe Profiel-contract dat berichtenbox-opt-in modelleert. */
    const val VOORKEUR_ONTVANG_BERICHTEN_TYPE: String = "OntvangViaBerichtenbox"

    /** IdentificatieType in `ScopeResponse.partij` waarop de afzender-OIN bekend is. */
    private const val OIN_IDENTIFICATIE_TYPE: String = "OIN"

    /**
     * Profiel-service modelleert booleane voorkeuren als string. We accepteren
     * "true"/"ja" case-insensitive; alles anders (null, "false", "nee", lege
     * string, "yes", etc.) telt als opt-out.
     */
    private val INGESCHAKELDE_WAARDEN: Set<String> = setOf("true", "ja")

    /**
     * True als deze voorkeur een berichtenbox-opt-in is met geldige opt-in-waarde.
     */
    fun isOntvangstvoorkeurIngeschakeld(voorkeur: VoorkeurResponse): Boolean =
        voorkeur.voorkeurType == VOORKEUR_ONTVANG_BERICHTEN_TYPE &&
            voorkeur.waarde?.lowercase() in INGESCHAKELDE_WAARDEN

    /**
     * Sequentie van afzender-OIN-strings waar de partij opted-in voor is. Filtert:
     *  - alleen `OntvangViaBerichtenbox`-voorkeuren met opt-in-waarde,
     *  - scopes met OIN-`partij` (scopes met alleen `dienst` of niet-OIN-type worden genegeerd).
     *
     * Returnt rauwe strings (geen `Oin`-instances): de caller bepaalt zelf of
     * een ongeldige upstream-OIN hard moet falen, defensief overslaan, of
     * gemaskeerd loggen — die policy verschilt per call-site.
     */
    fun optedInAfzenderOinStrings(partij: PartijResponse): Sequence<String> =
        partij.voorkeuren.asSequence()
            .filter(::isOntvangstvoorkeurIngeschakeld)
            .flatMap { it.scopes.asSequence() }
            .mapNotNull { scope ->
                val partijInScope = scope.partij ?: return@mapNotNull null

                if (partijInScope.identificatieType != OIN_IDENTIFICATIE_TYPE) return@mapNotNull null

                partijInScope.identificatieNummer
            }

    /**
     * True als de partij opted-in is voor berichtenbox-ontvangst van [afzender].
     * Vergelijking op `.waarde`: het externe contract gebruikt OIN-strings, niet
     * `Oin`-instances. Aangeroepen vanaf het aanlever-pad in berichtenmagazijn.
     */
    fun isOptedInVoorAfzender(partij: PartijResponse, afzender: Oin): Boolean =
        optedInAfzenderOinStrings(partij).any { it == afzender.waarde }
}

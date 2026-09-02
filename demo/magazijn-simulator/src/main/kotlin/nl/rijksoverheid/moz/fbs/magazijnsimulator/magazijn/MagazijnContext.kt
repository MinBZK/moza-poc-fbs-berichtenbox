package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import jakarta.enterprise.context.RequestScoped

/**
 * Draagt het magazijn dat bij deze request hoort, gezet door [MagazijnPadFilter] vóór het matchen
 * van de resource. Alles wat daarna draait leest hier welk magazijn bedoeld is; de `dbId` erin is de
 * discriminator waarop elke query filtert.
 *
 * Bewust geen default: een request zonder magazijn hoort het filter nooit te passeren, en een
 * "eerste magazijn"-terugval zou een verkeerd geconfigureerd register stil laten slagen in plaats
 * van luidruchtig laten falen.
 *
 * Het magazijn wordt één keer gekozen, door het pad-filter. Vandaar [kies] in plaats van een
 * publieke setter: overschrijven halverwege een request zou betekenen dat het antwoord uit een
 * ánder magazijn komt dan waar de autorisatie op is gedaan.
 */
@RequestScoped
class MagazijnContext {

    private var gekozen: GesimuleerdMagazijn? = null

    /**
     * Het gekozen magazijn, of `null` als er geen is. Voor code die op élk pad draait en het
     * beheerpad — dat geen magazijn kiest — moet kunnen overslaan.
     */
    val magazijnOfNiets: GesimuleerdMagazijn? get() = gekozen

    val magazijn: GesimuleerdMagazijn
        get() = checkNotNull(gekozen) {
            "Geen magazijn in de request-context; MagazijnPadFilter hoort dit vóór het matchen te zetten"
        }

    /** Kiest het magazijn voor deze request. Eén keer per request; een tweede keuze is een fout. */
    fun kies(magazijn: GesimuleerdMagazijn) {
        check(gekozen == null) { "Het magazijn van deze request is al gekozen" }

        gekozen = magazijn
    }
}

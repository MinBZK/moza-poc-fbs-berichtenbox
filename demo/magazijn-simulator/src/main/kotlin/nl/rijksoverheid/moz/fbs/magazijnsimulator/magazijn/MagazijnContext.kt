package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import jakarta.enterprise.context.RequestScoped

/**
 * Draagt het magazijn dat bij deze request hoort, gezet door [MagazijnPadFilter] vóór het matchen
 * van de resource. Alles wat daarna draait leest hier welk magazijn bedoeld is — vanaf stap 2 is
 * dat ook de discriminator waarop elke query filtert.
 *
 * Bewust geen default: een request zonder magazijn hoort het filter nooit te passeren, en een
 * "eerste magazijn"-terugval zou een verkeerd geconfigureerd register stil laten slagen in plaats
 * van luidruchtig laten falen.
 */
@RequestScoped
class MagazijnContext {

    private var gekozen: GesimuleerdMagazijn? = null

    var magazijn: GesimuleerdMagazijn
        get() = checkNotNull(gekozen) {
            "Geen magazijn in de request-context; MagazijnPadFilter hoort dit vóór het matchen te zetten"
        }
        set(waarde) {
            gekozen = waarde
        }
}

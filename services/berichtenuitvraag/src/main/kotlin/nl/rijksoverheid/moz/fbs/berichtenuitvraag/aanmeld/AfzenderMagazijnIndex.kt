package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister

/**
 * Leidt uit een afzender-OIN het bron-magazijn-id af, zodat een aanmeld-
 * geschreven cache-entry hetzelfde `magazijnId` krijgt als het read-aggregatie-pad
 * en PATCH/DELETE/bijlage-routering blijft werken.
 *
 * In het 1:1-model OIN↔magazijn (zie [Magazijnregister]) is het magazijn-id de
 * afzender-OIN zélf; het register-lookup bevestigt enkel dat die organisatie een
 * ingeschreven magazijn heeft. Het register is de enige bron van de OIN→magazijn-
 * koppeling; deze klasse houdt er geen eigen kopie van.
 */
@ApplicationScoped
class AfzenderMagazijnIndex(private val register: Magazijnregister) {

    /**
     * Bron-magazijn voor [afzender], of `null` als die OIN geen ingeschreven magazijn heeft
     * (onbekende bron / config-drift). De weergavenaam komt mee omdat een aanmeld-geschreven
     * cache-entry hem net zo hard nodig heeft als het `magazijnId`: de berichtenlijst toont hem.
     */
    fun magazijnVoor(afzender: Oin): BronMagazijn? = register.voorOin(afzender)
        ?.let { BronMagazijn(oin = it.oin, naam = it.naam) }

    /**
     * Het magazijn waaruit een aangemeld bericht komt, versmald tot wat het aanmeld-pad nodig
     * heeft: `url` en `grantHash` van de inschrijving horen daar niet te lekken. De `Oin` blijft
     * getypeerd — hem hier tot `String` wassen zou de validatie weggooien die het register al
     * gedaan heeft, en twee `String`-velden naast elkaar zijn verwisselbaar.
     */
    data class BronMagazijn(val oin: Oin, val naam: String)
}

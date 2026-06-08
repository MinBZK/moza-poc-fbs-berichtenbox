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
 * ingeschreven magazijn heeft. De koppeling leeft daarmee op één plek — geen
 * eigen reverse-index meer die uit de config opnieuw moet worden opgebouwd.
 */
@ApplicationScoped
class AfzenderMagazijnIndex(private val register: Magazijnregister) {

    /**
     * Magazijn-id voor [afzender], of `null` als die OIN geen ingeschreven
     * magazijn heeft (onbekende bron / config-drift).
     */
    fun magazijnVoor(afzender: Oin): String? = register.voorOin(afzender)?.oin?.waarde
}

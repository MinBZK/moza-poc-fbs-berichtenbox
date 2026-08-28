package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad

/**
 * Het bedieningspaneel van de simulator: demo's vullen, terugzetten en tijdens het verhaal
 * bijsturen.
 *
 * Wie een demonstratie geeft, moet die kunnen voorbereiden en tussendoor kunnen bijsturen. Zonder
 * deze drie handelingen is elke demo handwerk en is een tweede ronde niet hetzelfde als de eerste —
 * en dan is hij niet te oefenen en niet te vertrouwen.
 *
 * **Buiten de simulatie.** Een magazijn dat op storing staat weigert al zijn gewone verkeer, maar
 * hier komt het gedrag niet aan te pas: anders zou een kapot gezet magazijn niet meer te repareren
 * of te vullen zijn.
 *
 * **Buiten de gedeelde spec.** De gegenereerde interfaces blijven zo precies wat het echte magazijn
 * ook aanbiedt.
 *
 * **De set magazijnen komt hier niét vandaan.** Die leest de simulator bij het starten uit dezelfde
 * configuratie die het register van de uitvraag vult. Zou hij via een beheer-aanroep binnenkomen,
 * dan was er een opstartvolgorde — tot die aanroep geeft elk pad 404 terwijl het register geldig
 * oogt — en een tweede waarheid die kan driften.
 */
@Path("/${MagazijnPad.BEHEER_SEGMENT}")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
class BeheerResource(private val service: BeheerService) {

    /** Wat deze simulator voorstelt en hoe elk magazijn zich op dit moment gedraagt. */
    @GET
    @Path("/magazijnen")
    fun magazijnen(): List<MagazijnOverzicht> = service.overzicht()

    /**
     * Stelt het gedrag van één magazijn bij, met onmiddellijke ingang.
     *
     * Dit is de knop waarmee je tijdens een demo een organisatie kapot maakt om te laten zien wat de
     * gebruiker dan merkt. De storingsgevallen zijn pas iets waard als je ze op het juiste moment
     * kunt aanzetten in plaats van te hopen dat ze toevallig langskomen.
     */
    @PUT
    @Path("/magazijnen/{oin}/gedrag")
    @Consumes(MediaType.APPLICATION_JSON)
    fun zetGedrag(@PathParam("oin") oin: String, verzoek: GedragVerzoek): MagazijnOverzicht =
        service.zetGedrag(oin, verzoek) ?: throw NotFoundException("Geen gesimuleerd magazijn met OIN $oin")

    /**
     * Zet in één handeling berichten klaar in alle gesimuleerde magazijnen.
     *
     * Los aanleveren via de gewone API zou minuten kosten; dit is één transactie per magazijn. Wat
     * er klaargezet wordt is volledig afgeleid, dus dezelfde aanroep geeft dezelfde uitgangssituatie
     * — een demo die je oefent is dezelfde demo als je hem geeft.
     */
    @POST
    @Path("/seed")
    @Consumes(MediaType.APPLICATION_JSON)
    fun seed(verzoek: SeedVerzoek): SeedUitkomst = service.seed(verzoek)

    /**
     * Zet alles terug naar de begintoestand: berichten weg én het gedrag terug naar de vastgelegde
     * verdeling.
     *
     * Dat laatste hoort erbij. Een magazijn dat tijdens de vorige demo op storing is gezet, zou er
     * anders de volgende keer nog zo bij staan, en dan is "terug naar de begintoestand" een halve
     * waarheid.
     */
    @POST
    @Path("/legen")
    fun legen(): LeegUitkomst = service.legen()
}

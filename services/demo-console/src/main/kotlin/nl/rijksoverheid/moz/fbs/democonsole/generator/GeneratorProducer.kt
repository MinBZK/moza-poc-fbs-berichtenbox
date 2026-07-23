package nl.rijksoverheid.moz.fbs.democonsole.generator

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Levert de gedeelde generator-configuratie als CDI-bean. De organisaties (één per magazijn)
 * en de persona-opt-ins staan hier centraal en moeten sporen met de profielservice-stubs
 * onder `wiremock/demo-profiel/` — anders faalt aanleveren met 403.
 */
class GeneratorProducer {

    @Produces
    @ApplicationScoped
    fun generator(): DemoBerichtGenerator =
        DemoBerichtGenerator(
            personas = listOf(
                Persona("J. Pietersen", "BSN", "999993653", listOf(RVO, BELASTINGDIENST)),
                Persona("Bakkerij De Vroege Vogel", "BSN", "123456782", listOf(RVO)),
                Persona("Garage Van Dijk B.V.", "KVK", "12345678", listOf(BELASTINGDIENST)),
            ),
            organisaties = mapOf(
                RVO to Organisatie(RVO, "RVO", RVO_SJABLONEN),
                BELASTINGDIENST to Organisatie(BELASTINGDIENST, "Belastingdienst", BELASTINGDIENST_SJABLONEN),
            ),
            klok = Clock.systemUTC(),
        )

    private companion object {

        const val RVO = "00000001003214345000"
        const val BELASTINGDIENST = "00000001823288444000"

        val RVO_SJABLONEN = listOf(
            Sjabloon(
                "Subsidieaanvraag ontvangen",
                "We hebben uw aanvraag voor de SDE++-subsidie ontvangen. U hoort binnen acht weken of " +
                    "de subsidie wordt toegekend. U hoeft nu niets te doen.",
            ),
            Sjabloon(
                "Beschikking SDE++",
                "Uw aanvraag voor de SDE++-subsidie is toegekend. De beschikking met de " +
                    "uitbetalingsvoorwaarden vindt u in de bijlage.",
            ),
            Sjabloon(
                "Controle mestboekhouding",
                "In het kader van de gecombineerde opgave voeren wij een controle uit op uw " +
                    "mestboekhouding. Houd uw administratie van de afgelopen twee jaar beschikbaar.",
            ),
            Sjabloon(
                "Eenmalige verlenging eHerkenning",
                "Uw eHerkenningsmiddel verloopt over vier weken. Verleng het tijdig om toegang tot " +
                    "Mijn RVO te behouden.",
            ),
            Sjabloon(
                "Gecombineerde opgave verwerkt",
                "Uw gecombineerde opgave is verwerkt. Controleer in Mijn RVO of alle percelen correct " +
                    "zijn geregistreerd.",
            ),
            Sjabloon(
                "Uitnodiging webinar verduurzaming",
                "Op 12 september organiseren wij een webinar over het verduurzamen van uw bedrijf. " +
                    "Aanmelden kan kosteloos via Mijn RVO.",
            ),
        )

        val BELASTINGDIENST_SJABLONEN = listOf(
            Sjabloon(
                "Voorlopige aanslag 2026",
                "Uw voorlopige aanslag inkomstenbelasting 2026 staat klaar. Het te betalen bedrag en de " +
                    "termijnen vindt u in de bijlage.",
            ),
            Sjabloon(
                "Definitieve aanslag 2025",
                "De definitieve aanslag over 2025 is vastgesteld. Er is een verschil met uw voorlopige " +
                    "aanslag; de toelichting vindt u in de bijlage.",
            ),
            Sjabloon(
                "Herinnering aangifte omzetbelasting",
                "U heeft de aangifte omzetbelasting over het tweede kwartaal nog niet ingediend. Dien " +
                    "deze uiterlijk 31 juli in om een boete te voorkomen.",
            ),
            Sjabloon(
                "Teruggaaf inkomstenbelasting",
                "U ontvangt een teruggaaf inkomstenbelasting. Het bedrag wordt binnen zes weken op uw " +
                    "bekende rekeningnummer bijgeschreven.",
            ),
            Sjabloon(
                "Betalingsherinnering",
                "Wij hebben uw betaling voor de openstaande aanslag nog niet ontvangen. Betaal het bedrag " +
                    "vóór de vervaldatum om invorderingskosten te voorkomen.",
            ),
            Sjabloon(
                "Wijziging rekeningnummer bevestigd",
                "Het rekeningnummer waarop u teruggaven ontvangt is gewijzigd. Heeft u dit niet zelf " +
                    "gedaan? Neem dan direct contact met ons op.",
            ),
        )
    }
}

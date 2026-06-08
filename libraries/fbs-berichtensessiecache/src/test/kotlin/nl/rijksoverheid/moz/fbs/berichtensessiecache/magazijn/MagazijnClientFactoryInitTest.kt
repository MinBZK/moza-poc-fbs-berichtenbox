package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.mockk.mockk
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockedDependenciesProfile
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * De config-validatie (OIN-keys, URL/TLS, leeg register) leeft — met eigen tests —
 * in fbs-magazijnregister; hier pinnen we alleen het factory-gedrag bovenop het
 * register: client-map met `magazijnId == oin.waarde`, namen-lookup en de
 * 1:1-afzender-lookup.
 */
@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class MagazijnClientFactoryInitTest {

    private val oinA = "00000001003214345000"
    private val oinB = "00000001823288444000"
    private val onbekendeOin = "99999999999999999999"

    // Stub-register i.p.v. mockk: MockK kan de Oin-value-class niet synthesiseren
    // tijdens matcher-recording (`any<Oin>()` triggert de validerende constructor
    // met dummy-waarden → DomainValidationException). Subclass overschrijft
    // createClient zodat geen Quarkus-CDI-context nodig is voor de REST-client-builder.
    private fun factory(vararg inschrijvingen: Magazijninschrijving): MagazijnClientFactory =
        object : MagazijnClientFactory(
            register = stubRegister(*inschrijvingen),
            connectTimeoutMs = 2000L,
            readTimeoutMs = 12000L,
        ) {
            override fun createClient(inschrijving: Magazijninschrijving): MagazijnClient = mockk()
        }.also { it.init() }

    private fun stubRegister(vararg inschrijvingen: Magazijninschrijving): Magazijnregister =
        object : Magazijnregister {
            override fun alle(): Collection<Magazijninschrijving> = inschrijvingen.toList()
            override fun voorOin(oin: Oin): Magazijninschrijving? = inschrijvingen.firstOrNull { it.oin == oin }
        }

    private fun inschrijving(oin: String, naam: String? = null): Magazijninschrijving =
        Magazijninschrijving(oin = Oin(oin), url = URI.create("http://localhost:8081"), naam = naam)

    @Test
    fun `init bouwt per inschrijving een client met de OIN-waarde als magazijnId`() {
        val factory = factory(inschrijving(oinA), inschrijving(oinB))

        assertEquals(setOf(oinA, oinB), factory.getAllClients().keys)
    }

    @Test
    fun `getNaam levert de register-naam per magazijnId`() {
        val factory = factory(inschrijving(oinA, naam = "Belastingdienst"), inschrijving(oinB))

        assertEquals("Belastingdienst", factory.getNaam(oinA))
        assertNull(factory.getNaam(oinB))
    }

    @Test
    fun `magazijnenVoorAfzender levert singleton-set voor een ingeschreven OIN`() {
        val factory = factory(inschrijving(oinA))

        assertEquals(setOf(oinA), factory.magazijnenVoorAfzender(Oin(oinA)))
    }

    @Test
    fun `magazijnenVoorAfzender levert lege set voor een onbekende OIN (drift)`() {
        val factory = factory(inschrijving(oinA))

        assertEquals(emptySet<String>(), factory.magazijnenVoorAfzender(Oin(onbekendeOin)))
    }
}

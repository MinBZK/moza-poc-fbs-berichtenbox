package nl.rijksoverheid.moz.fbs.appmanager

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path

/** Alleen in de tests: een pad dat een onverwachte fout gooit, om het vangnet van [Foutvertaling] te toetsen. */
@Path("/test/onverwacht")
class OnverwachtTestResource {

    @GET
    fun gooi(): String = throw IllegalStateException("onverwacht")
}

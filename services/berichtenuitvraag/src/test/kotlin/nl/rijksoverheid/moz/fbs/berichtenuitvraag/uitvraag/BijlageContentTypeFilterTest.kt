package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.NewCookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit-tests voor [BijlageContentTypeFilter] zonder MockK: die verwart
 * `getProperty(String)` met een Kotlin-property, waardoor de stub nooit aanslaat.
 * Een minimale handgeschreven stub omzeilt dat. Verifieert fail-closed-gedrag (`application/octet-stream` + download) bij een
 * onparsebaar MIME-type, en welke typen wél inline mogen.
 */
class BijlageContentTypeFilterTest {

    private val filter = BijlageContentTypeFilter()

    @Test
    fun `parsebaar MIME-type wordt 1-op-1 doorgegeven`() {
        val req = FakeRequestCtx("application/pdf")
        val resp = FakeResponseCtx()

        filter.filter(req, resp)

        assertEquals("application/pdf", resp.headers.getFirst("Content-Type"))
        assertEquals("inline", resp.headers.getFirst("Content-Disposition"))
    }

    @Test
    fun `onparsebaar MIME-type valt terug op octet-stream + attachment`() {
        val req = FakeRequestCtx("not-a-mime-type")
        val resp = FakeResponseCtx()

        filter.filter(req, resp)

        assertEquals("application/octet-stream", resp.headers.getFirst("Content-Type"))
        assertEquals("attachment", resp.headers.getFirst("Content-Disposition"))
    }

    @Test
    fun `header-splitting payload wordt afgevangen via fallback`() {
        val req = FakeRequestCtx("text/plain\r\nX-Evil: 1")
        val resp = FakeResponseCtx()

        filter.filter(req, resp)

        assertEquals("application/octet-stream", resp.headers.getFirst("Content-Type"))
        assertEquals("attachment", resp.headers.getFirst("Content-Disposition"))
    }

    @Test
    fun `een veilig te tonen type mag inline, met bestandsnaam`() {
        val req = FakeRequestCtx("application/pdf", "aanslag 2026.pdf")
        val resp = FakeResponseCtx()

        filter.filter(req, resp)

        assertEquals(
            "inline; filename=\"aanslag_2026.pdf\"; filename*=UTF-8''aanslag%202026.pdf",
            resp.headers.getFirst("Content-Disposition"),
        )
    }

    @Test
    fun `een in de browser uitvoerbaar type blijft een download`() {
        val req = FakeRequestCtx("image/svg+xml", "tekening.svg")
        val resp = FakeResponseCtx()

        filter.filter(req, resp)

        assertEquals("image/svg+xml", resp.headers.getFirst("Content-Type"))
        assertEquals(
            "attachment; filename=\"tekening.svg\"; filename*=UTF-8''tekening.svg",
            resp.headers.getFirst("Content-Disposition"),
        )
    }

    @Test
    fun `een niet-Latijnse naam beschadigt de header niet`() {
        val req = FakeRequestCtx("application/pdf", "Λογαριασμός.pdf")
        val resp = FakeResponseCtx()

        filter.filter(req, resp)

        assertEquals(
            "inline; filename=\"___________.pdf\"; " +
                "filename*=UTF-8''%CE%9B%CE%BF%CE%B3%CE%B1%CF%81%CE%B9%CE%B1%CF%83%CE%BC%CF%8C%CF%82.pdf",
            resp.headers.getFirst("Content-Disposition"),
        )
    }

    @Test
    fun `een onparsebaar MIME-type blijft een download, ook met naam`() {
        val req = FakeRequestCtx("not-a-mime-type", "nota.pdf")
        val resp = FakeResponseCtx()

        filter.filter(req, resp)

        assertEquals("application/octet-stream", resp.headers.getFirst("Content-Type"))
        assertEquals(
            "attachment; filename=\"nota.pdf\"; filename*=UTF-8''nota.pdf",
            resp.headers.getFirst("Content-Disposition"),
        )
    }

    @Test
    fun `zonder property doet filter niets`() {
        val req = FakeRequestCtx(null)
        val resp = FakeResponseCtx()

        filter.filter(req, resp)

        assertEquals(null, resp.headers.getFirst("Content-Type"))
        assertEquals(null, resp.headers.getFirst("Content-Disposition"))
    }

    private class FakeRequestCtx(private val mime: String?, private val naam: String? = null) : StubRequestCtx() {
        override fun getProperty(name: String?): Any? = when (name) {
            BIJLAGE_MIME_TYPE_PROPERTY -> mime
            BIJLAGE_NAAM_PROPERTY -> naam
            else -> null
        }
    }

    private class FakeResponseCtx : StubResponseCtx() {
        private val hdrs: MultivaluedMap<String, Any> = MultivaluedHashMap()
        override fun getHeaders(): MultivaluedMap<String, Any> = hdrs
    }

    /** Minimal abstract base: alleen de methodes die de filter gebruikt staan in subklasses. */
    private abstract class StubRequestCtx : ContainerRequestContext {
        override fun getProperty(name: String?): Any? = null
        override fun getPropertyNames(): MutableCollection<String> = mutableListOf()
        override fun setProperty(name: String?, `object`: Any?) {}
        override fun removeProperty(name: String?) {}
        override fun getUriInfo(): jakarta.ws.rs.core.UriInfo = throw UnsupportedOperationException()
        override fun setRequestUri(requestUri: java.net.URI?) {}
        override fun setRequestUri(baseUri: java.net.URI?, requestUri: java.net.URI?) {}
        override fun getRequest(): jakarta.ws.rs.core.Request = throw UnsupportedOperationException()
        override fun getMethod(): String = "GET"
        override fun setMethod(method: String?) {}
        override fun getHeaders(): MultivaluedMap<String, String> = throw UnsupportedOperationException()
        override fun getHeaderString(name: String?): String? = null
        override fun getDate(): java.util.Date? = null
        override fun getLanguage(): java.util.Locale? = null
        override fun getLength(): Int = -1
        override fun getMediaType(): jakarta.ws.rs.core.MediaType? = null
        override fun getAcceptableMediaTypes(): MutableList<jakarta.ws.rs.core.MediaType> = mutableListOf()
        override fun getAcceptableLanguages(): MutableList<java.util.Locale> = mutableListOf()
        override fun getCookies(): MutableMap<String, jakarta.ws.rs.core.Cookie> = mutableMapOf()
        override fun hasEntity(): Boolean = false
        override fun getEntityStream(): java.io.InputStream = throw UnsupportedOperationException()
        override fun setEntityStream(input: java.io.InputStream?) {}
        override fun getSecurityContext(): jakarta.ws.rs.core.SecurityContext = throw UnsupportedOperationException()
        override fun setSecurityContext(context: jakarta.ws.rs.core.SecurityContext?) {}
        override fun abortWith(response: jakarta.ws.rs.core.Response?) {}
    }

    private abstract class StubResponseCtx : ContainerResponseContext {
        override fun getStatus(): Int = 200
        override fun setStatus(code: Int) {}
        override fun getStatusInfo(): jakarta.ws.rs.core.Response.StatusType = throw UnsupportedOperationException()
        override fun setStatusInfo(statusInfo: jakarta.ws.rs.core.Response.StatusType?) {}
        override fun getHeaders(): MultivaluedMap<String, Any> = throw UnsupportedOperationException()
        override fun getStringHeaders(): MultivaluedMap<String, String> = throw UnsupportedOperationException()
        override fun getHeaderString(name: String?): String? = null
        override fun getAllowedMethods(): MutableSet<String> = mutableSetOf()
        override fun getDate(): java.util.Date? = null
        override fun getLanguage(): java.util.Locale? = null
        override fun getLength(): Int = -1
        override fun getMediaType(): jakarta.ws.rs.core.MediaType? = null
        override fun getCookies(): MutableMap<String, NewCookie> = mutableMapOf()
        override fun getEntityTag(): jakarta.ws.rs.core.EntityTag? = null
        override fun getLastModified(): java.util.Date? = null
        override fun getLocation(): java.net.URI? = null
        override fun getLinks(): MutableSet<jakarta.ws.rs.core.Link> = mutableSetOf()
        override fun hasLink(relation: String?): Boolean = false
        override fun getLink(relation: String?): jakarta.ws.rs.core.Link? = null
        override fun getLinkBuilder(relation: String?): jakarta.ws.rs.core.Link.Builder? = null
        override fun hasEntity(): Boolean = false
        override fun getEntity(): Any? = null
        override fun getEntityClass(): Class<*> = Any::class.java
        override fun getEntityType(): java.lang.reflect.Type = Any::class.java
        override fun setEntity(entity: Any?) {}
        override fun setEntity(entity: Any?, annotations: Array<out Annotation>?, mediaType: jakarta.ws.rs.core.MediaType?) {}
        override fun getEntityAnnotations(): Array<out Annotation> = emptyArray()
        override fun getEntityStream(): java.io.OutputStream = throw UnsupportedOperationException()
        override fun setEntityStream(outputStream: java.io.OutputStream?) {}
    }
}

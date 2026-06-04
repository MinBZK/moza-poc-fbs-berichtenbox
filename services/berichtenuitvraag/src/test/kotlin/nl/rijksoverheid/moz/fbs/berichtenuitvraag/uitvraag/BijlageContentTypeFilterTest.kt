package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.NewCookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit-tests voor [BijlageContentTypeFilter] zonder MockK: het mocken van
 * `getProperty(String)` op JAX-RS-contexten triggert in MockK een property-vs-
 * methode-conflict (zie eerdere iteratie). Een minimal handmatige stub omzeilt
 * dat. Verifieert fail-closed-gedrag (`application/octet-stream + attachment`)
 * bij een onparsebaar MIME-type — de KDoc benoemt dit als stored-XSS-bescherming.
 */
class BijlageContentTypeFilterTest {

    private val filter = BijlageContentTypeFilter()

    @Test
    fun `parsebaar MIME-type wordt 1-op-1 doorgegeven met attachment-disposition`() {
        val req = FakeRequestCtx("application/pdf")
        val resp = FakeResponseCtx()

        filter.filter(req, resp)

        assertEquals("application/pdf", resp.headers.getFirst("Content-Type"))
        assertEquals("attachment", resp.headers.getFirst("Content-Disposition"))
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
    fun `zonder property doet filter niets`() {
        val req = FakeRequestCtx(null)
        val resp = FakeResponseCtx()

        filter.filter(req, resp)

        assertEquals(null, resp.headers.getFirst("Content-Type"))
        assertEquals(null, resp.headers.getFirst("Content-Disposition"))
    }

    private class FakeRequestCtx(private val mime: String?) : StubRequestCtx() {
        override fun getProperty(name: String?): Any? = mime.takeIf { name == BIJLAGE_MIME_TYPE_PROPERTY }
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

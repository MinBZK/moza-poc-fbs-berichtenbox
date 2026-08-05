package nl.rijksoverheid.moz.fbs.democonsole.dataset

import nl.rijksoverheid.moz.fbs.democonsole.generator.BijlageDto

/**
 * Eén kleine, geldige PDF (Base64) die de basisvulling aan een deel van de berichten hangt,
 * zodat de bijlage-download in de UI demonstreerbaar is. Het magazijn accepteert alleen
 * `application/pdf`. Bewust in code (niet herhaald in de dataset-JSON) om die schoon te houden.
 */
object DemoBijlage {

    private const val PDF_BASE64 =
        "JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4KZW5kb2Jq" +
            "CjIgMCBvYmoKPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4KZW5kb2Jq" +
            "CjMgMCBvYmoKPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCAzMDAg" +
            "MjAwXSAvQ29udGVudHMgNCAwIFIgL1Jlc291cmNlcyA8PCAvRm9udCA8PCAvRjEgNSAwIFIgPj4g" +
            "Pj4gPj4KZW5kb2JqCjQgMCBvYmoKPDwgL0xlbmd0aCA3OCA+PgpzdHJlYW0KQlQgL0YxIDE2IFRm" +
            "IDQwIDEyMCBUZCAoRGVtby1iaWpsYWdlKSBUaiAwIC0zMCBUZCAoRkJTIEJlcmljaHRlbmJveCBQ" +
            "b0MpIFRqIEVUCmVuZHN0cmVhbQplbmRvYmoKNSAwIG9iago8PCAvVHlwZSAvRm9udCAvU3VidHlw" +
            "ZSAvVHlwZTEgL0Jhc2VGb250IC9IZWx2ZXRpY2EgPj4KZW5kb2JqCnhyZWYKMCA2CjAwMDAwMDAw" +
            "MDAgNjU1MzUgZiAKMDAwMDAwMDAwOSAwMDAwMCBuIAowMDAwMDAwMDU4IDAwMDAwIG4gCjAwMDAw" +
            "MDAxMTUgMDAwMDAgbiAKMDAwMDAwMDI0MSAwMDAwMCBuIAowMDAwMDAwMzY5IDAwMDAwIG4gCnRy" +
            "YWlsZXIKPDwgL1NpemUgNiAvUm9vdCAxIDAgUiA+PgpzdGFydHhyZWYKNDM5CiUlRU9GCg=="

    fun bij(bestandsnaam: String): BijlageDto = BijlageDto(bestandsnaam, "application/pdf", PDF_BASE64)
}

package nl.rijksoverheid.moz.fbs.democonsole.dataset

import nl.rijksoverheid.moz.fbs.democonsole.generator.BijlageDto

/**
 * Eén kleine, geldige PDF (Base64) die de basisvulling aan een deel van de berichten hangt,
 * zodat de bijlage-download in de UI demonstreerbaar is. Het magazijn accepteert alleen
 * `application/pdf`. Bewust in code (niet herhaald in de dataset-JSON) om die schoon te houden.
 *
 * De PDF rendert twee tekstregels: "Demo-bijlage" en "FBS Berichtenbox". Wie de tekst wijzigt,
 * moet de hele PDF opnieuw genereren: de xref-tabel bevat byte-offsets die met de streamlengte
 * meeschuiven, dus losse Base64-bewerking levert een onleesbaar bestand op.
 */
object DemoBijlage {

    private const val PDF_BASE64 =
        "JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4KZW5kb2Jq" +
            "CjIgMCBvYmoKPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4KZW5kb2Jq" +
            "CjMgMCBvYmoKPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCAzMDAg" +
            "MjAwXSAvQ29udGVudHMgNCAwIFIgL1Jlc291cmNlcyA8PCAvRm9udCA8PCAvRjEgNSAwIFIgPj4g" +
            "Pj4gPj4KZW5kb2JqCjQgMCBvYmoKPDwgL0xlbmd0aCA3NSA+PgpzdHJlYW0KQlQgL0YxIDE2IFRm" +
            "IDQwIDEyMCBUZCAoRGVtby1iaWpsYWdlKSBUaiAwIC0zMCBUZCAoRkJTIEJlcmljaHRlbmJveCkg" +
            "VGogRVQKZW5kc3RyZWFtCmVuZG9iago1IDAgb2JqCjw8IC9UeXBlIC9Gb250IC9TdWJ0eXBlIC9U" +
            "eXBlMSAvQmFzZUZvbnQgL0hlbHZldGljYSA+PgplbmRvYmoKeHJlZgowIDYKMDAwMDAwMDAwMCA2" +
            "NTUzNSBmIAowMDAwMDAwMDA5IDAwMDAwIG4gCjAwMDAwMDAwNTggMDAwMDAgbiAKMDAwMDAwMDEx" +
            "NSAwMDAwMCBuIAowMDAwMDAwMjQxIDAwMDAwIG4gCjAwMDAwMDAzNjUgMDAwMDAgbiAKdHJhaWxl" +
            "cgo8PCAvU2l6ZSA2IC9Sb290IDEgMCBSID4+CnN0YXJ0eHJlZgo0MzUKJSVFT0YK"

    fun bij(bestandsnaam: String): BijlageDto = BijlageDto(bestandsnaam, "application/pdf", PDF_BASE64)
}

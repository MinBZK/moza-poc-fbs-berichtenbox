package nl.rijksoverheid.moz.fbs.democonsole.dataset

import nl.rijksoverheid.moz.fbs.democonsole.generator.BijlageDto

/**
 * Eén kleine, geldige PDF (Base64) die de basisvulling aan een deel van de berichten hangt,
 * zodat de bijlage-download in de UI demonstreerbaar is. Het magazijn accepteert alleen
 * `application/pdf`. Bewust in code (niet herhaald in de dataset-JSON) om die schoon te houden.
 *
 * De PDF rendert twee tekstregels: "Demo-bijlage" en "Federatief Berichtenstelsel". Wie de tekst
 * wijzigt, moet de hele PDF opnieuw genereren: de xref-tabel bevat byte-offsets die met de
 * streamlengte meeschuiven, dus losse Base64-bewerking levert een onleesbaar bestand op.
 */
object DemoBijlage {

    private const val PDF_BASE64 =
        "JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4KZW5kb2Jq" +
            "CjIgMCBvYmoKPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4KZW5kb2Jq" +
            "CjMgMCBvYmoKPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCAzMDAg" +
            "MjAwXSAvQ29udGVudHMgNCAwIFIgL1Jlc291cmNlcyA8PCAvRm9udCA8PCAvRjEgNSAwIFIgPj4g" +
            "Pj4gPj4KZW5kb2JqCjQgMCBvYmoKPDwgL0xlbmd0aCA4NiA+PgpzdHJlYW0KQlQgL0YxIDE2IFRm" +
            "IDQwIDEyMCBUZCAoRGVtby1iaWpsYWdlKSBUaiAwIC0zMCBUZCAoRmVkZXJhdGllZiBCZXJpY2h0" +
            "ZW5zdGVsc2VsKSBUaiBFVAplbmRzdHJlYW0KZW5kb2JqCjUgMCBvYmoKPDwgL1R5cGUgL0ZvbnQg" +
            "L1N1YnR5cGUgL1R5cGUxIC9CYXNlRm9udCAvSGVsdmV0aWNhID4+CmVuZG9iagp4cmVmCjAgNgow" +
            "MDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAwMDAwMDkgMDAwMDAgbiAKMDAwMDAwMDA1OCAwMDAwMCBu" +
            "IAowMDAwMDAwMTE1IDAwMDAwIG4gCjAwMDAwMDAyNDEgMDAwMDAgbiAKMDAwMDAwMDM3NiAwMDAw" +
            "MCBuIAp0cmFpbGVyCjw8IC9TaXplIDYgL1Jvb3QgMSAwIFIgPj4Kc3RhcnR4cmVmCjQ0NgolJUVP" +
            "Rgo="

    fun bij(bestandsnaam: String): BijlageDto = BijlageDto(bestandsnaam, "application/pdf", PDF_BASE64)
}

package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

/**
 * Een schending van een domein-invariant: de invoer klopt qua vorm, maar niet qua betekenis. Een
 * eigen type en geen kale [IllegalArgumentException], zodat de vertaling naar 400 alleen dít soort
 * fout raakt — een interne `IllegalArgumentException` hoort een 500 te blijven, niet stilzwijgend
 * een clientfout te worden.
 */
class DomeinFout(melding: String) : RuntimeException(melding)

/**
 * Als `require`, maar met [DomeinFout]. De melding wordt lui opgebouwd zodat het samenstellen niets
 * kost op het pad waar de invoer gewoon klopt.
 */
fun vereis(voldoet: Boolean, melding: () -> String) {
    if (!voldoet) throw DomeinFout(melding())
}

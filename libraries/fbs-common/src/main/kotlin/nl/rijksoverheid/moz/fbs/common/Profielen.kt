package nl.rijksoverheid.moz.fbs.common

/**
 * De profielen waarin transport-eisen (TLS, peer-verificatie, authenticatie) niet gelden,
 * zodat een lokale opstelling zonder certificaten werkt. Eén gedeelde verzameling voor alle
 * validators: liepen die uiteen, dan zou een profiel bij de ene wél en bij de andere niet
 * vrijgesteld zijn en ontstaat er een handhavingsgat dat nergens opvalt.
 *
 * Bewust een allowlist op de exacte profielnaam. Een samengesteld profiel (`dev,demo`) of een
 * andere schrijfwijze valt er dus buiten en krijgt de volle eis — fail-closed.
 */
val PROFIELEN_ZONDER_TLS_EIS = setOf("dev", "test")

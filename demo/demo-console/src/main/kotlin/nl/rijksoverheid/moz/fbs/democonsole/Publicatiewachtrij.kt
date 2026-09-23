package nl.rijksoverheid.moz.fbs.democonsole

/**
 * Waarom een aangeleverd bericht niet meteen in de Berichtenbox staat.
 *
 * Het magazijn slaat het bericht op en zet in dezelfde transactie een regel in zijn
 * publicatie-wachtrij; het aanmelden bij de uitvraag gebeurt daarna, in een eigen ronde. Tussen die
 * twee zit dus tijd, en in die tijd lijkt een geslaagde aanlevering op een knop die niets deed.
 *
 * Bewust zonder getal: hoe vaak die wachtrij wordt verwerkt is een instelling van het magazijn, en
 * een getal in dit paneel zou gaan afwijken zodra een operator hem bijstelt.
 */
const val PUBLICATIEWACHTRIJ_MELDING: String =
    "Aangeleverde berichten staan in de publicatie-wachtrij van het magazijn. Ze verschijnen in de " +
        "Berichtenbox zodra het magazijn die wachtrij verwerkt; dat gebeurt kort na het aanleveren, " +
        "niet op hetzelfde moment."

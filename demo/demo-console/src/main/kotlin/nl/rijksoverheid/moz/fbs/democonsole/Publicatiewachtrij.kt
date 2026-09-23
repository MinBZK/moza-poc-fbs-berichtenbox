package nl.rijksoverheid.moz.fbs.democonsole

/**
 * Waarom een aangeleverd bericht niet meteen in de Berichtenbox staat.
 *
 * Het magazijn slaat het bericht op en zet in dezelfde transactie een regel in zijn
 * publicatie-wachtrij; het aanmelden bij de uitvraag gebeurt daarna, in een eigen ronde. Tussen die
 * twee zit dus tijd, en in die tijd lijkt een geslaagde aanlevering op een knop die niets deed.
 *
 * Bewust zonder getal en zonder "kort": hoe vaak die wachtrij wordt verwerkt is een instelling van
 * het magazijn — met de huidige waarde duurt het gemiddeld een halve minuut, en een paneel dat
 * snelheid suggereert laat de bediener zoeken naar een fout die er niet is. Een getal zou boven-
 * dien gaan afwijken zodra een operator die instelling bijstelt.
 */
const val PUBLICATIEWACHTRIJ_MELDING: String =
    "Aangeleverde berichten staan in de publicatie-wachtrij van het magazijn. Ze verschijnen in de " +
        "Berichtenbox zodra het magazijn die wachtrij verwerkt."

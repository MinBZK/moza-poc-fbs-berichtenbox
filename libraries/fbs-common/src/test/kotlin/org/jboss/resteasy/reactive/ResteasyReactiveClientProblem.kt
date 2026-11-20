package org.jboss.resteasy.reactive

/**
 * Stand-in voor de markering van de REST-client van Quarkus, met dezelfde volledige naam.
 *
 * `ProblemExceptionMapper` herkent een doorgegeven client-fout aan die naam. De echte interface
 * staat in een library die een eigen `RuntimeDelegate` meebrengt, en die laat de mapper-tests
 * buiten Quarkus falen. Het echte pad toetst de uitvraag in `DualWriteFaultTest`.
 */
interface ResteasyReactiveClientProblem

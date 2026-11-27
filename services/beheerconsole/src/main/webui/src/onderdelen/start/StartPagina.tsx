import "@nldd/design-system/simple-section";
import "@nldd/design-system/title";
import "@nldd/design-system/rich-text";

export function StartPagina() {
  return (
    <nldd-simple-section>
      <nldd-title size="display-2">
        <h1>Beheer van het berichtenstelsel</h1>
      </nldd-title>
      <nldd-rich-text>
        <p>
          Hier beheert de stelselbeheerder het Federatief Berichtenstelsel: welke organisaties met
          een berichtenmagazijn zijn aangesloten, en of het ondertekende stelseldocument geldig is.
        </p>
        <p>
          Verkeer, fouten en storingen staan niet hier maar in de monitoring. Deze console toont de
          toestand van het stelsel en de beheerhandelingen.
        </p>
      </nldd-rich-text>
    </nldd-simple-section>
  );
}

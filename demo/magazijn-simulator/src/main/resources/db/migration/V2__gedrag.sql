-- Gedrag per gesimuleerd magazijn: hoe snel het antwoordt en of het fouten geeft of niet reageert.
--
-- In werkelijkheid reageert niet elke organisatie even snel en ligt er af en toe eentje eruit. Het
-- interessante gedrag van de Berichtenbox zit juist in die randen; een demo waarin alles het altijd
-- doet, laat niet zien wat een gebruiker merkt als het níét meezit.
--
-- Waarom in de database en niet alleen in het geheugen: zo is met een blik in de tabel te zien hoe
-- de simulator erbij staat, en kan een bedieningspaneel dat straks tonen. Het bijstellen tijdens een
-- demo werkt op de ingelezen set en niet op deze kolommen — de configuratie blijft de bron, en elke
-- reconcile schrijft hem hier overheen. Een herstart en het `legen`-pad zetten het gedrag daarmee
-- terug op de vastgelegde verdeling.

ALTER TABLE magazijn
    -- Als enum-naam en niet als getal: leesbaar in de database, en een herordening van de enum
    -- verandert de opgeslagen betekenis dan niet stilzwijgend.
    ADD COLUMN gedrag_modus    VARCHAR(16)      NOT NULL DEFAULT 'NORMAAL',
    -- Mediaan en 95e percentiel van de responstijd voor TRAAG. Twee punten en geen vaste vertraging:
    -- echte responstijden hebben een lange staart, en juist die uitschieters bepalen wanneer een
    -- ondernemer zijn lijst compleet ziet.
    ADD COLUMN latency_p50_ms  INTEGER          NOT NULL DEFAULT 50,
    ADD COLUMN latency_p95_ms  INTEGER          NOT NULL DEFAULT 50,
    -- Kans per aanroep dat HAPERT een fout geeft, tussen 0 en 1.
    ADD COLUMN foutkans        DOUBLE PRECISION NOT NULL DEFAULT 0,
    -- De statuscode die STUK, HAPERT en WEIGERT teruggeven.
    ADD COLUMN fout_status     INTEGER          NOT NULL DEFAULT 503;

-- Elke constraint een eigen ADD: PostgreSQL accepteert `ADD COLUMN … , CONSTRAINT …` niet in één
-- ALTER-opdracht.
ALTER TABLE magazijn
    ADD CONSTRAINT ck_magazijn_foutkans CHECK (foutkans >= 0 AND foutkans <= 1),
    ADD CONSTRAINT ck_magazijn_latency CHECK (latency_p50_ms >= 0 AND latency_p95_ms >= latency_p50_ms),
    ADD CONSTRAINT ck_magazijn_fout_status CHECK (fout_status BETWEEN 400 AND 599);

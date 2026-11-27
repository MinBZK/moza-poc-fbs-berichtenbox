import "@nldd/design-system/styles";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { start } from "./onderdelen/start/onderdeel";
import { Shell } from "./shell/Shell";
import type { Onderdeel } from "./shell/onderdeel";

// De onderdelen van deze console. Een samengevoegd beheerportaal voegt hier zijn eigen toe.
const onderdelen: Onderdeel[] = [start];

const root = document.getElementById("root");

if (root === null) throw new Error("element #root ontbreekt in index.html");

createRoot(root).render(
  <StrictMode>
    <BrowserRouter basename={import.meta.env.BASE_URL}>
      <Shell titel="Berichtenstelsel" ondertitel="Beheer" onderdelen={onderdelen}>
        <Routes>
          {onderdelen.flatMap((onderdeel) =>
            onderdeel.schermen.map((scherm) => (
              <Route key={scherm.pad} path={scherm.pad} element={scherm.inhoud} />
            )),
          )}
        </Routes>
      </Shell>
    </BrowserRouter>
  </StrictMode>,
);

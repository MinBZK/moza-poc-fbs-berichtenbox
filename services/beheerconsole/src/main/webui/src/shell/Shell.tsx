import "@nldd/design-system/page";
import "@nldd/design-system/page-footer";
import "@nldd/design-system/top-navigation-bar";
import "@nldd/design-system/menu-bar";
import "@nldd/design-system/menu-bar-item";
import "@nldd/design-system/skip-link";
import type { MouseEvent, ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import type { Onderdeel } from "./onderdeel";

interface ShellProps {
  titel: string;
  ondertitel?: string;
  onderdelen: Onderdeel[];
  children: ReactNode;
}

/**
 * Het paginaraamwerk dat elk beheerportaal deelt: navigatiebalk, inhoud en voet. Kent geen
 * FBS-begrippen; titel en menu komen uit de onderdelen die de app meegeeft.
 */
export function Shell({ titel, ondertitel, onderdelen, children }: ShellProps) {
  const locatie = useLocation();
  const navigeer = useNavigate();
  const schermen = onderdelen.flatMap((onderdeel) => onderdeel.schermen);

  // De menu-items zijn echte links, zodat openen in een nieuw tabblad blijft werken; een gewone
  // klik blijft binnen de app in plaats van de pagina opnieuw te laden.
  const volgLink = (pad: string) => (event: MouseEvent) => {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return;

    event.preventDefault();
    navigeer(pad);
  };

  return (
    <nldd-page sticky-header>
      <nldd-skip-link slot="header" href="#inhoud" />
      <nldd-top-navigation-bar slot="header" logo-title={titel} logo-subtitle={ondertitel} website-title={titel}>
        <nldd-menu-bar slot="global" accessible-label="Hoofdmenu">
          {schermen.map((scherm) => (
            <nldd-menu-bar-item
              key={scherm.pad}
              text={scherm.menutekst}
              icon={scherm.icoon}
              href={import.meta.env.BASE_URL + scherm.pad.replace(/^\//, "")}
              current={locatie.pathname === scherm.pad || undefined}
              onClick={volgLink(scherm.pad)}
            />
          ))}
        </nldd-menu-bar>
      </nldd-top-navigation-bar>
      <main id="inhoud">{children}</main>
      <nldd-page-footer slot="footer" />
    </nldd-page>
  );
}

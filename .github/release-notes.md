## Sell Macro für Minecraft 26.2 (Fabric)

Füllt die `/sell` GUI des Servers immer wieder mit Items, bis du sie selbst schließt.

### Neu in 1.2.9
- Aktualisieren-Intervall des Auto-Kaufs jetzt in Millisekunden, ab 50 ms (= 1 Tick, schneller geht in Minecraft nicht). In der GUI 50 ms bis 60 s, per Befehl `/ahbuy refresh <50-60000>`. Nach dem Klick auf Aktualisieren wird 100 ms auf die neuen Angebote gewartet.

### Neu in 1.2.8
- Auto-Kauf aktualisiert mit dem Aktualisieren-Knopf unten links im Auktionshaus, statt `/ah` jedes Mal neu zu öffnen.
- Neue GUI für den Auto-Kauf (`/ahbuy gui` oder Knopf „Auto-Kauf (AH) ...“ in der Sell-Macro-GUI): mehrere Regeln/Fälle mit Name, Höchstpreis und Mindest-Restzeit, z. B. „bis 95m ab 2 Tagen“ und „bis 50m ab 1 Tag“. Dazu Testmodus, Aktualisieren-Intervall, Budget und Starten/Stoppen.
- Unvollständige Regeln (kein Name, kein Preis, unlesbare Zeit) kaufen nie etwas.

### Neu in 1.2.7
- Auto-Kauf im Auktionshaus (`/ahbuy`): kauft Angebote, deren Name passt, deren Preis höchstens so hoch ist wie angegeben und deren Star-Tool-Restzeit mindestens so lang ist wie angegeben. Beispiel: `/ahbuy add 95m 2d star pickaxe`, dann `/ahbuy start`.
- Startet im **Testmodus**: passende Angebote werden nur im Chat angezeigt, nicht gekauft. Erst `/ahbuy test false` schaltet echtes Kaufen ein.
- Budget pro Lauf (`/ahbuy budget 200m`) und Aktualisierungs-Intervall (`/ahbuy refresh 5`). Stoppen mit Taste K, `/ahbuy stop` oder durch Schließen des Auktionshauses.

### Neu in 1.2.6
- AFK-Start: Hast du 3 Minuten lang keine Taste gedrückt und die Maus nicht bewegt, startet das zuletzt genutzte Makro von selbst. Die Zeit lässt sich mit `/sellmacro idle <minuten>` oder in der GUI („AFK-Start“) ändern, `0` schaltet es ab.
- Läuft Minecraft im Hintergrund (Alt+Tab), blockiert das automatisch geöffnete Pausemenü das Makro nicht mehr.

### Neu in 1.2.5
- Freischaltung per UUID statt Name: Ein Namenswechsel ändert nichts mehr.
- Die Liste der freigeschalteten Spieler steckt nicht mehr in der Mod, sondern wird online geladen (beim Start und alle 10 Minuten). Neue Spieler freischalten oder sperren geht ohne neue Mod-Version. Ohne Internetverbindung zu GitHub bleibt die Mod gesperrt.
- `/sellmacro uuid` zeigt die eigene UUID zum Kopieren, auch ohne Freischaltung.

### Neu in 1.2.4
- Einstellungs-GUI: `/sellmacro gui` oder Taste **J**. Items eintragen und starten/stoppen, Item in der Hand übernehmen, alle Einstellungen umschalten, Limits setzen.
- Presets in der GUI: Name eingeben und speichern, mit einem Klick starten, mit X löschen.
- Auto-Fortsetzen: Nach einem Server-Neustart, Transfer oder Kick wartet das Makro bis zu 10 Minuten, bis du wieder auf einem Server bist, und macht dann mit denselben Items weiter. Klappt `/sell` nicht sofort, wird es 2 Minuten lang immer wieder versucht. Bewusstes Verlassen über „Verbindung trennen“ beendet das Makro. Abschaltbar mit `/sellmacro autoresume false` oder in der GUI.

### Neu in 1.2.3
- Ein weiterer Spieler ist freigeschaltet.

### Neu in 1.2.2
- Verkauft jetzt über den grünen Haken in der /sell GUI: Die GUI bleibt offen und wird nur geleert, statt sie jede Runde zu schließen und mit `/sell` neu zu öffnen. Das sind viel weniger Pakete, also weniger Kick-Gefahr. Der Knopf wird automatisch erkannt. Ohne Knopf oder mit `/sellmacro confirm false` läuft es wie bisher.

### Neu in 1.2.1
- Geldbeträge mit Tausender-Punkten: `1.234.567` statt `1.23M`
- Der Wert pro Stunde ist jetzt der hochgerechnete Schnitt pro Minute seit dem Start (die erste Minute zählt voll, damit ein schneller erster Verkauf keinen riesigen Stundenwert ergibt). Angezeigt werden `/min` und `/h`, beim Stoppen der Schnitt pro Stunde.

### Neu in 1.2.0
- Mehrere Items auf einmal: `/sellmacro wheat carrot potato` (bis zu 9)
- Presets: `/sellmacro preset save farm`, danach `/sellmacro preset farm`
- `/sellmacro hand` verkauft das Item in der Hand
- Taste **K** (änderbar unter Steuerung → Sell Macro): Makro starten/stoppen, startet die zuletzt genutzten Items
- Verdienst-Anzeige: Geldbeträge aus den Server-Nachrichten werden gezählt, mit Gesamtsumme und Betrag pro Stunde
- Umbenannte oder verzauberte Items werden nie verkauft (`/sellmacro protect false` zum Abschalten)
- Auto-Stopp bei Schaden, nach X Runden (`/sellmacro limit rounds 50`) oder ab einem Geldbetrag (`/sellmacro limit money 100000`)
- Einstellungen und Presets bleiben in `config/sellmacro.json` gespeichert

### Installation
- Fabric Loader ≥ 0.19.5 und Fabric API für 26.2
- Die Jar in den `mods` Ordner legen
- Nur Client, auf dem Server muss nichts installiert werden

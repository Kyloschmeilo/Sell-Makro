## Sell Macro für Minecraft 26.2 (Fabric)

Füllt die `/sell` GUI des Servers immer wieder mit Items, bis du sie selbst schließt.

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

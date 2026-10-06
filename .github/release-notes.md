## Sell Macro für Minecraft 26.2 (Fabric)

Füllt die `/sell` GUI des Servers immer wieder mit Items, bis du sie selbst schließt.
Nur für freigeschaltete Spieler: kyloschmeilo, _danilo, genius187, Mtb1304.

### Neu in 1.3.0
- Hintergrund-Modus (Standard an): Die /sell GUI wird unsichtbar im Hintergrund befüllt, du kannst dich währenddessen normal umschauen, laufen und die Maus benutzen. Stoppen mit Taste K oder `/sellmacro stop`. Mit `/sellmacro background false` wieder wie vorher (GUI sichtbar, Stopp durch Schließen).

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

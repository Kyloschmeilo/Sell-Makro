# Sell Macro (Fabric, Minecraft 26.2)

Client-Mod, die die `/sell` GUI eines Servers automatisch immer wieder mit einem Item-Typ füllt.

## Ablauf

1. `/sellmacro <item>` eingeben, z. B. `/sellmacro wheat` oder `/sellmacro minecraft:diamond`.
2. Die Mod schickt `/sell` an den Server und wartet, bis die GUI offen ist.
3. Jeder Stack des gewählten Items aus deinem Inventar (inkl. Hotbar) wird per Shift-Klick in die GUI gelegt.
4. Die Mod schließt die GUI (dabei verkauft der Server die Items) und öffnet sie mit `/sell` erneut.
5. Das wiederholt sich so lange, bis **du** die `/sell` GUI selbst schließt (Esc oder Inventar-Taste).

Hast du gerade keine passenden Items, bleibt die GUI offen und die Mod legt neue Items ein, sobald welche
in deinem Inventar landen.

## Befehle

| Befehl | Beschreibung |
| --- | --- |
| `/sellmacro <item> [item ...]` | Makro mit bis zu 9 Items starten (Tab-Vervollständigung funktioniert) |
| `/sellmacro hand` | Item in der Hand verkaufen |
| `/sellmacro stop` | Makro sofort beenden |
| `/sellmacro preset save <name>` | Zuletzt gestartete Items als Preset speichern |
| `/sellmacro preset <name>` | Preset starten |
| `/sellmacro preset list` / `delete <name>` | Presets anzeigen / löschen |
| `/sellmacro delay <0-20>` | Ticks zwischen zwei Shift-Klicks (`0` = alles im selben Tick, Standard `1`) |
| `/sellmacro protect <true\|false>` | Umbenannte/verzauberte Items nie verkaufen (Standard an) |
| `/sellmacro damagestop <true\|false>` | Bei Schaden sofort stoppen und die GUI schließen (Standard an) |
| `/sellmacro limit rounds <n>` | Nach n Runden stoppen (`0` = aus) |
| `/sellmacro limit money <betrag>` | Ab diesem Verdienst stoppen (`0` = aus) |
| `/sellmacro earnings test <nachricht>` | Prüfen, welcher Betrag in einer Chat-Nachricht erkannt wird |
| `/sellmacro earnings pattern <regex>` / `reset` | Eigenes Muster für die Geld-Erkennung (erste Gruppe = Betrag) |
| `/sellmacro settings` | Alle Einstellungen anzeigen |

**Raus-Tabben:** Während das Makro läuft, kannst du Minecraft in den Hintergrund schicken (Alt+Tab) und etwas anderes
machen. Das Pausemenü, das Minecraft sonst beim Fokusverlust öffnet, wird dann übersprungen.

**Taste K** (änderbar unter Steuerung → Sell Macro) startet/stoppt das Makro mit den zuletzt genutzten Items,
beim ersten Mal mit dem Item in der Hand.

**Verdienst:** Während das Makro läuft, werden Geldbeträge aus Server-Nachrichten (z. B. `$1,234`, `500 Coins`)
zusammengezählt und mit Betrag pro Stunde angezeigt. Erkennt die Mod die Verkaufsnachricht deines Servers nicht,
mit `/sellmacro earnings test` prüfen und notfalls ein eigenes Muster setzen.

Falls der Server dich wegen zu schneller Klicks kickt, die Verzögerung erhöhen. Einstellungen und Presets werden in
`config/sellmacro.json` gespeichert.

## Download

Die fertige `sellmacro-<version>.jar` gibt es unter
[Releases](https://github.com/Kyloschmeilo/Sell-Makro/releases).

## Installation

Benötigt [Fabric Loader](https://fabricmc.net/use/) ≥ 0.19.5, [Fabric API](https://modrinth.com/mod/fabric-api)
für 26.2 und Java 25. Die `sellmacro-<version>.jar` in den `mods` Ordner legen. Die Mod läuft nur auf dem
Client, auf dem Server muss nichts installiert werden.

## Selbst bauen

```sh
./gradlew build
```

Die Jar liegt danach in `build/libs/`.


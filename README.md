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
| `/sellmacro <item>` | Makro mit diesem Item starten (Tab-Vervollständigung funktioniert) |
| `/sellmacro stop` | Makro sofort beenden |
| `/sellmacro delay` | Aktuelle Verzögerung zwischen zwei Shift-Klicks anzeigen |
| `/sellmacro delay <0-20>` | Verzögerung in Ticks setzen (`0` = alles im selben Tick, Standard `1`) |

Falls der Server dich wegen zu schneller Klicks kickt, die Verzögerung erhöhen.

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

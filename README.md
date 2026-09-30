# Contract Bridge

A **Contract Bridge** addon for [Charta](https://github.com/lucaargolo/charta), the Minecraft card-table
mod. It adds a fourth game to the table: a four player, two partnership duplicate board with a real
auction, a dummy, follow-suit play and full duplicate scoring — using Charta's own table, chairs, cards,
animations and sounds.

Everything you already know about the Charta table keeps working: the same deck, the same cloth, the
same chairs, the same chat log and the same drag-and-drop. This mod only adds the game on top.

## Requirements

| | |
|---|---|
| Minecraft | 1.21 – 1.21.1 |
| Loader | Fabric Loader 0.17.3+ |
| Dependencies | [Fabric API](https://modrinth.com/mod/fabric-api), [Charta](https://modrinth.com/mod/charta) 1.2.5 (below 1.3) |
| Side | Client **and** server |

Charta is pinned below 1.3 on purpose. This addon mixins into a few of Charta's screen and slot classes,
so a minor bump there can move a target silently — a hard "incompatible" beats a crash inside a mixin.

## Installing

Drop the jar into `mods/` together with Fabric API and Charta. Nothing else is needed, and no config has
to be written by hand.

## Playing

1. Lay a Charta cloth, put a **standard 52 card deck** on it, and place a chair on each of the four
   sides.
2. Open the table and **shift + left click** *Contract Bridge*.
3. Sit down. Nobody else around? Bots fill the empty chairs by default, so one player can start a whole
   board.

Which side you sat on is which side you play: sit north and your hand is along the top of the screen.
The view is never rotated, so everyone at the table sees the same picture.

The auction, the play, the scoring and the screen's controls are all described in the mod's in-game
**How to play** page, reachable from the game selection screen.

### At a glance

* **Auction** — a 7 × 5 bidding box plus pass, double and redouble, greyed out exactly where the rules
  forbid a call. Three passes close it, four to start with pass the board out and re-deal.
* **Play** — declarer's left hand opponent leads; follow suit when you can; trump beats everything.
* **The dummy** — face up for the whole table with a yellow frame, and played by declarer. Nobody else
  may touch those cards, not even the dummy.
* **Scoring** — duplicate scoring: contract points, game and slam bonuses, undertrick penalties, and
  the doubled and redoubled tables.
* **Trick counter and vulnerability** — both optional game rules, off/on as you like.

## Options

Shift-click the cog on the game selection screen:

* **Show trick counter** — which trick of the thirteen is being played.
* **Fill empty seats with bots** — on by default.
* **Vulnerable declaring side** — score the board as vulnerable.

## Rearranging the table

Press **F9** in the table screen to move and scale anything on it. `R` resets the box under the pointer,
`Ctrl + R` resets everything, and the result is saved to `config/bridge-layout.properties`. It is a
client-side setting, so each player can keep their own.

## Building from source

```sh
./gradlew build          # jar in build/libs
./gradlew deploy         # build, then copy into the PCL2 test instance
```

The build needs Charta's own artifact, which is not on a public maven — clone
[charta](https://github.com/lucaargolo/charta) and run `./gradlew publishToMavenLocal` first.

The long-form porting notes — building Charta, the proxy setup, and every pitfall hit along the way —
are in [CHARTA-SETUP.md](CHARTA-SETUP.md) (Chinese).

A dev-only shortcut opens a self-playing table as soon as you are in a world:

```sh
BRIDGE_DEBUG_SCREEN=1 ./gradlew runClient
```

## Credits and licence

Built on [Charta](https://github.com/lucaargolo/charta) by Luca Argolo — the table, the chairs, the card
rendering, the drag-and-drop, the history panel and the sound effects are all Charta's.

Released under the [Mozilla Public License 2.0](LICENSE).

# Contract Bridge

Four players, two partnerships (North-South against East-West), played as duplicate bridge. The deal,
the auction, the contract, the dummy and the scoring all follow the real rules.

## Setting up a table

1. Lay a cloth and put a standard 52 card deck on it as the pile.
2. Have four players stand on the four sides of the table (one is enough, see the options).
3. Open the table screen and **shift + left click** Contract Bridge.

## The interface

**Which side of the table you are on is where you sat down.** Sit on the north chair and your own fan is
along the top; sit on the east chair and it is an upright column on the right. The screen is never
rotated, so every player reads the same picture and "the East hand" means the same person to all four of
you.

* Your own hand is the one you can drag; the other three are face down.
* The middle of the panel is the trick pile, and the drop target for playing a card.
* Each seat has a name plate naming its compass, and the plate of whoever is on turn lights up.
* The chat log on the left records the deal, every call, every card and every trick.

### The auction

While the auction runs a **bidding box** covers the middle of the panel:

* The top four rows are the compass directions in order -- north, east, south, west -- and what each has
  called so far. Your own row is marked "you".
* The 7 x 5 grid under it is the bidding box itself: levels 1 to 7 down the side, ♣ ♦ ♥ ♠ NT across.
* The three buttons below are pass, double and redouble.
* Greyed out cells cannot be called right now; hover a cell for a reminder of what it is.
* When it is your turn, click a cell to call it.

The auction ends after three passes once somebody has bid. Four passes to start with pass the board out
and it is dealt again automatically.

### Play

The side that bid highest owns the contract:

* **Declaring side**: the player who bid the strain first is **declarer**, and declarer's partner is the
  **dummy**. The dummy's hand is turned **face up for everybody** and gets a yellow frame; while the
  dummy is on turn, **declarer plays the dummy's cards** by clicking them. Nobody else may touch them,
  not even the dummy.
* **Defenders**: the other two. The player to declarer's left leads the first trick.
* To play: click a card in your own (or the dummy's) hand so it lifts, then click the pile.
* **Follow suit**: if you still hold the suit that was led you must play it; only then may you discard
  or ruff.
* The trump strain beats every other suit. A notrump contract has no trump.

## Scoring

Duplicate scoring: the declaring side has to take 6 + the contract level in tricks.

* Made: contract points (♣ ♦ 20 a trick, ♥ ♠ 30, notrump 40 for the first and 30 after, times the
  level), a game bonus once the contract is worth 100 or more, and slam bonuses. Doubles and redoubles
  multiply the contract points and add insult.
* Defeated: 50 (not vulnerable) or 100 (vulnerable) a trick, and much more when doubled.

## Options

Shift-click the cog on the game selection screen:

* **Show trick counter**: show which trick is being played.
* **Fill empty seats with bots**: on by default, so one person can start a table. Turn it off to
  require four real players.
* **Vulnerable declaring side**: score the board as vulnerable, with bigger bonuses and penalties.

## Rearranging the table

Press **F9** in the table screen to open a layout overlay: drag any box to move it, scroll over it to
scale it, `R` to reset the box under the pointer and `Ctrl + R` to reset everything. The result is saved
to `config/bridge-layout.properties` and applies to every future table.

## Tips

* Playing alone, the bots bid and play the whole board; you can take over at any point.
* To watch a board run by quickly, keep passing: a passed out board is dealt again.

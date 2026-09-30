package dev.bridge.game;

import dev.bridge.BridgeMod;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.game.GameType;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import dev.lucaargolo.charta.common.menu.CardSlot;
import dev.lucaargolo.charta.common.menu.HandSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Menu for Contract Bridge. The base class handles synchronising every card slot, the carried-card
 * stack and player/current-turn state, so an addon menu only has to declare which slots exist, in what
 * order, and how the extra state (the auction, the contract, the score) is mirrored.
 *
 * <h2>Slots are positional</h2>
 *
 * <p>Slot indices are identical on both sides, which is what lets the click payload address them; only
 * the coordinates are client-side decoration. Slot 0 is the trick pile because it is the drop target
 * {@code PlaySlot} validates against.
 *
 * <p>Coordinates are <em>not</em> decided here any more: {@code BridgeLayout} owns them, applies them
 * once the screen knows the window size, and rewrites them whenever the player drags something in the
 * F9 editor. The constructor values are only placeholders for the dedicated-server side, which never
 * renders them. Slot order is the contract the layout relies on, so it is spelled out in
 * {@link #HAND_ORDER}.
 *
 * <h2>Mirroring the auction</h2>
 *
 * <p>Every call is a small integer (see {@link BridgeBid}), so the whole auction plus the contract fit
 * in one {@link ContainerData} block on top of the base slots. Charta already ships the data-slot
 * channel, which means no packet is needed to keep the bidding box and the score in step.
 *
 * <p>{@link #mirrored} is the seam: on the server (and in the debug screen, where nothing ever sends
 * data) the getters read the live game, while a connected client reads the values the server pushed.
 * That keeps a single set of accessors working for both.
 */
public class BridgeMenu extends AbstractCardMenu<BridgeGame, BridgeMenu> {

    /**
     * Panel size. The layout scales the popup from these, and the screen mirrors them.
     *
     * <p>Kept equal to {@code BridgeLayout.Element.POPUP}'s base box: the popup contents are written in
     * these coordinates and then shifted by the layout's own top-left, so the two drifting apart would
     * slide everything out of the sheet. Width 168 and height 192 to give the auction table's ordinal
     * header row and its call columns room to exist at all.
     */
    public static final int PANEL_WIDTH = 168;
    public static final int PANEL_HEIGHT = 192;

    public static final int PILE_X = (PANEL_WIDTH - (int) CardSlot.getWidth(CardSlot.Type.DEFAULT)) / 2;
    public static final int PILE_Y = 62;

    /**
     * Seat of each hand slot, as registered by the constructor and expected by the layout: seats
     * {@code 0, 3, 1, 2} in slot order, i.e. north and south seats first because those are the two
     * fans and are the two that want to be drawn on top.
     *
     * <p>This is a <em>seat</em> order, so it is stable for a whole board; which on-screen chair each
     * slot is then put in is decided by that seat's compass, which the roster's random rotation makes
     * different from its index -- see {@code BridgeGame#compass} and {@code BridgeLayout#apply}.
     */
    public static final int[] HAND_ORDER = {0, 3, 1, 2};

    /** Values pushed by the server, indexed exactly like {@link BridgeGame#syncValue(int)}. */
    private final int[] mirror = new int[BridgeGame.SYNC_SIZE];
    private boolean mirrored;

    /**
     * Turn/seat mirror.
     *
     * <p>{@code AbstractCardMenu} already ships a data slot for the current player, but its getter is
     * {@code players.indexOf(currentPlayer)} — and {@link BridgeBot#equals} makes {@code indexOf} report
     * the first real seat for every bot, so a bot's turn would be drawn as the local player's.
     * Registering our own slot at the end of the data list both fixes the value and, because the client
     * applies data slots in order, wins over the broken one.
     */
    private final ContainerData turnData = new ContainerData() {
        @Override
        public int get(int index) {
            return game.getSeatOfCurrentPlayer();
        }

        @Override
        public void set(int index, int value) {
            game.setCurrentPlayer(value);
        }

        @Override
        public int getCount() {
            return 1;
        }
    };

    /** The auction, the contract and the score, riding Charta's existing data-slot sync. */
    private final ContainerData bridgeData = new ContainerData() {
        @Override
        public int get(int index) {
            return mirrored ? mirror[index] : game.syncValue(index);
        }

        @Override
        public void set(int index, int value) {
            mirrored = true;
            mirror[index] = value;
        }

        @Override
        public int getCount() {
            return BridgeGame.SYNC_SIZE;
        }
    };

    public BridgeMenu(int containerId, Inventory inventory, Definition definition) {
        super(BridgeMod.BRIDGE_MENU, containerId, inventory, definition);

        // Slot 0: the central trick pile, and the drop target that registers a play. Its type is only
        // picked to dodge the one-frame the screen draws for a {@code DEFAULT}-sized slot: the pile's
        // declared box is the whole three-by-three compass block, not a single card, and the slot's own
        // widget is suppressed anyway (see {@code BridgeGame.PileSlot}). PREVIEW also happens to be the
        // one type whose {@code y} is read as an absolute screen coordinate, which is what the layout
        // writes for it.
        addCardSlot(new CardSlot<>(this.game, game -> game.getSlot(0), PILE_X, PILE_Y, CardSlot.Type.PREVIEW));

        // Seats in HAND_ORDER, i.e. north, west, east, south -- the local hand is one of them and lands
        // in its own chair rather than being forced to the bottom of the screen.
        for (int seat : HAND_ORDER) {
            addCardSlot(seatSlot(seat));
        }

        addDataSlots(turnData);
        addDataSlots(bridgeData);
    }

    /**
     * The hand of {@code seat}: the local player's own fan if it is theirs, otherwise a side-on view.
     *
     * <p>The shape follows the seat's compass direction, not its distance from the local player. North
     * and south are horizontal fans at the top and bottom of the table, west and east are upright
     * columns at its sides, so a player sitting east gets their own hand as a column -- which is what
     * "everyone sees the same table" costs and buys.
     */
    private CardSlot<BridgeGame, BridgeMenu> seatSlot(int seat) {
        // Clamped exactly like {@link #getLocalSeat}: before the player is seated the raw seat is
        // negative, and an unclamped comparison here would quietly build the local hand as a spectator
        // slot — no drag, no HandSlot.
        int localSeat = Math.max(0, this.game.getSeat(this.getCardPlayer()));
        CardSlot.Type type = handType(this.game.compass(seat));
        if (seat == localSeat) {
            return new HandSlot<>(
                    this.game,
                    // Charta's HandSlot.postUpdate blanks its censored slot every tick, and for the
                    // dummy the censored slot *is* the real hand -- that is how the whole table gets to
                    // see it face up. Leaving the blanking on would overwrite the dummy's own cards
                    // with blank ones every tick, i.e. their whole fan turns into card backs and the
                    // suit-following check loses the suits. So: blank it unless the local player is the
                    // dummy.
                    game -> !game.isDummy(this.getCardPlayer()),
                    this.getCardPlayer(),
                    PANEL_WIDTH / 2f - CardSlot.getWidth(type) / 2f,
                    -5,
                    type
            );
        }

        // Never PREVIEW: it is drawn at 0.33x and its fan clamps into the declared box, so the thirteen
        // cards of a hand end up about 20px of visible card and read as one lump.
        CardPlayer other = this.game.playerAt(seat);
        if (other == null) {
            // No player in that chair: keep the index stable for the layout with a dead, correctly
            // shaped slot.
            return new CardSlot<>(this.game, game -> new GameSlot(), 0, 0, type);
        }
        CardPlayer viewer = this.getCardPlayer();
        return new CardSlot<>(this.game, game -> game.getCensoredHand(viewer, other), 0, 0, type);
    }

    /**
     * North and south read as a fan along the top and bottom bars; west and east as a side column.
     *
     * <p>Keyed on the <em>compass</em> rather than the seat: which way a hand is drawn is a fact about
     * the chair its player is in, not about where the seat happens to fall in Charta's rotated roster.
     */
    private static CardSlot.Type handType(int compass) {
        return switch (Math.floorMod(compass, BridgeGame.PLAYERS)) {
            case 0, 2 -> CardSlot.Type.HORIZONTAL;
            default -> CardSlot.Type.VERTICAL;
        };
    }

    /** Slot index of a hand element, or {@code -1} if the layout hands the menu an unknown element. */
    public int handSlotIndex(int handOrderIndex) {
        // Slot 0 is the pile, so the hands start at 1 and follow HAND_ORDER.
        return 1 + handOrderIndex;
    }

    /** Panel origin in screen coordinates, for slots that are stored relative to it. */
    public int panelLeft(int screenWidth) {
        return (screenWidth - PANEL_WIDTH) / 2;
    }

    public int panelTop(int screenHeight) {
        return (screenHeight - PANEL_HEIGHT) / 2;
    }

    /** Seat of the player whose turn it is, as agreed with the server. */
    public int getCurrentSeat() {
        return turnData.get(0);
    }

    /** Seat of the local player, i.e. where they sit on the table. */
    public int getLocalSeat() {
        return Math.max(0, this.game.getSeat(this.getCardPlayer()));
    }

    public GameSlot getPileCards() {
        return this.game.getTrickPile();
    }

    // ---------------------------------------------------------------------------------------------
    // Mirrored auction / contract state
    // ---------------------------------------------------------------------------------------------

    public BridgeGame.Phase getPhase() {
        int ordinal = bridgeData.get(BridgeGame.SYNC_PHASE);
        BridgeGame.Phase[] phases = BridgeGame.Phase.values();
        return phases[Math.max(0, Math.min(phases.length - 1, ordinal))];
    }

    public int getDealerSeat() {
        return bridgeData.get(BridgeGame.SYNC_DEALER);
    }

    public int getAuctionSize() {
        return bridgeData.get(BridgeGame.SYNC_AUCTION_SIZE);
    }

    /** Call at {@code index}, or {@code -1} when the auction has not reached it yet. */
    public int getAuctionCall(int index) {
        return bridgeData.get(BridgeGame.SYNC_AUCTION_BASE + index);
    }

    public int getContract() {
        return bridgeData.get(BridgeGame.SYNC_CONTRACT);
    }

    public int getDeclarerSeat() {
        return bridgeData.get(BridgeGame.SYNC_DECLARER);
    }

    public int getDoubling() {
        return bridgeData.get(BridgeGame.SYNC_DOUBLING);
    }

    public int getRequiredTricks() {
        return bridgeData.get(BridgeGame.SYNC_REQUIRED);
    }

    public int getDeclarerTricks() {
        return bridgeData.get(BridgeGame.SYNC_DECLARER_TRICKS);
    }

    public int getDefenderTricks() {
        return bridgeData.get(BridgeGame.SYNC_DEFENDER_TRICKS);
    }

    public int getTrickNumber() {
        return bridgeData.get(BridgeGame.SYNC_TRICK_NUMBER);
    }

    /** Seat that opened the trick on the table, or {@code -1} while there is none. */
    public int getTrickLeaderSeat() {
        return bridgeData.get(BridgeGame.SYNC_TRICK_LEADER);
    }

    /**
     * Seat that won the trick on the table, or {@code -1} while it is still being played.
     *
     * <p>Non-negative exactly while the finished trick lies on the table waiting to be swept, which is
     * the window the collection animation runs in.
     */
    public int getTrickWinnerSeat() {
        return bridgeData.get(BridgeGame.SYNC_TRICK_WINNER);
    }

    /** {@code 1} made, {@code -1} defeated, {@code 0} unknown. */
    public int getResult() {
        return bridgeData.get(BridgeGame.SYNC_RESULT);
    }

    public int getScore() {
        return bridgeData.get(BridgeGame.SYNC_SCORE);
    }

    /** Seat of the dummy, or {@code -1} while the auction is still running. */
    public int getDummySeat() {
        int declarer = getDeclarerSeat();
        return declarer < 0 ? -1 : Math.floorMod(declarer + 2, BridgeGame.PLAYERS);
    }

    /**
     * Rebuilds the auction as the client sees it, so the bidding box can grey out illegal calls
     * without asking the server.
     */
    public Auction mirrorAuction() {
        Auction auction = new Auction(getDealerSeat());
        int size = getAuctionSize();
        for (int i = 0; i < size; i++) {
            auction.call(getAuctionCall(i), auction.nextSeat());
        }
        return auction;
    }

    /** {@code true} when the local player may call right now. */
    public boolean canCallNow() {
        return getPhase() == BridgeGame.Phase.AUCTION
                && menuIsUsable()
                && getCurrentSeat() == getLocalSeat();
    }

    private boolean menuIsUsable() {
        return isGameReady() && !this.game.isGameOver();
    }

    @Nullable
    public CardPlayer getPlayerAtSeat(int seat) {
        return this.game.getPlayers().isEmpty() ? null : this.game.playerAt(seat);
    }

    @Override
    public GameType<BridgeGame, BridgeMenu> getGameType() {
        return BridgeMod.BRIDGE;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return this.game != null && this.cardPlayer != null && !this.game.isGameOver();
    }

}

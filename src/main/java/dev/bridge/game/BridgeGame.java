package dev.bridge.game;

import dev.lucaargolo.charta.common.block.GameChairBlock;
import dev.lucaargolo.charta.common.block.entity.CardTableBlockEntity;
import dev.lucaargolo.charta.common.game.Ranks;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.DrawSlot;
import dev.lucaargolo.charta.common.game.api.GamePlay;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.PlaySlot;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import dev.lucaargolo.charta.common.game.api.game.Game;
import dev.lucaargolo.charta.common.game.api.game.GameOption;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import dev.lucaargolo.charta.common.sound.ModSounds;
import dev.lucaargolo.charta.common.utils.CardImage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Contract Bridge: a four seat, two-versus-two duplicate board played on Charta's public game API.
 *
 * <p>A board is dealt, auctioned, then declared: the winning side names a contract and a trump strain,
 * declarer tries to take the tricks the contract needs, and the board is scored like duplicate —
 * contract points, game and slam bonuses, or undertrick penalties. Nothing about card movement,
 * animation, sounds, packet sync, the history panel or the table block is reimplemented; all of that
 * is Charta's {@code Game}/{@code GameSlot}/{@code PlaySlot} machinery.
 *
 * <h2>Phases</h2>
 *
 * <p>{@code DEALING -> AUCTION -> PLAY -> RESULT}. {@link #runGame()} is the single entry point and
 * dispatches on the phase, which keeps the flow in one readable place; every transition re-enters it.
 * Charta calls {@code runGame()} once as soon as the scheduled deal runs out, which is what starts the
 * auction.
 *
 * <h2>Seats versus actors, and why the dummy needs both</h2>
 *
 * <p>{@link #seat} is the chair that owes something (a call, or a card). {@code currentPlayer} is the
 * {@code CardPlayer} who has to produce it. For three of the four chairs those are the same player, but
 * the dummy is played by declarer: when the dummy is on turn the seat says "dummy" while the actor is
 * declarer. That single indirection is what makes Charta's turn machinery work unchanged — the awaited
 * play future belongs to the actor, {@code PlaySlot} accepts the actor's drop, and
 * {@link #canPlay} only ever asks whether the card came out of the right chair's hand.
 *
 * <p>Consequently {@code currentPlayer} is <b>never</b> the dummy, and the dummy's own {@code tick}
 * never fires: bots only act when {@code getCurrentPlayer() == this}.
 *
 * <h2>The roster</h2>
 *
 * <p>{@code Game.players} is the seat list and seat {@code s} partners {@code s + 2}. A real table only
 * hands over the players that actually sat down, so the constructor copies that list and
 * {@link #rebuildRoster()} appends {@link BridgeBot}s until the fourth chair is taken — but only while
 * the bots option is on, which is also why {@link #getMinPlayers()} derives from the same option.
 * {@code setRawOptions} is the hook that makes this work on both sides identically.
 */
public class BridgeGame extends Game<BridgeGame, BridgeMenu> {

    public static final int PLAYERS = 4;
    public static final int TRICKS = 13;
    public static final int HAND_SIZE = 13;

    /** Hard cap on auction length; a legal auction is at most 35 calls. */
    public static final int MAX_AUCTION = 40;

    /** Ticks a completed trick stays face up before it is swept away. */
    private static final int TRICK_LINGER = 25;
    /** Ticks the final score stays on screen before the table resets. */
    private static final int RESULT_LINGER = 80;

    // -----------------------------------------------------------------------------------------
    // Container-data layout
    //
    // The auction and the contract are mirrored to the client through the menu's data slots. Calls
    // are small integers already, so one slot per call is enough and no packet is needed for state.
    // -----------------------------------------------------------------------------------------

    public static final int SYNC_PHASE = 0;
    public static final int SYNC_DEALER = 1;
    public static final int SYNC_AUCTION_SIZE = 2;
    public static final int SYNC_CONTRACT = 3;
    public static final int SYNC_DECLARER = 4;
    public static final int SYNC_DOUBLING = 5;
    public static final int SYNC_REQUIRED = 6;
    public static final int SYNC_TRUMP = 7;
    public static final int SYNC_DECLARER_TRICKS = 8;
    public static final int SYNC_DEFENDER_TRICKS = 9;
    public static final int SYNC_TRICK_NUMBER = 10;
    public static final int SYNC_RESULT = 11;
    public static final int SYNC_SCORE = 12;
    public static final int SYNC_TRICK_LEADER = 13;
    /**
     * Seat that took the trick currently lying on the table, or {@code -1} while it is still being
     * played. Set with the fourth card and cleared with the pile, so on the wire it is exactly the
     * window the client's "sweep the cards to the winner" animation runs in.
     */
    public static final int SYNC_TRICK_WINNER = 14;
    public static final int SYNC_PUBLIC_SIZE = 15;
    public static final int SYNC_AUCTION_BASE = SYNC_PUBLIC_SIZE;
    public static final int SYNC_SIZE = SYNC_AUCTION_BASE + MAX_AUCTION;

    public enum Phase {
        DEALING,
        AUCTION,
        PLAY,
        RESULT
    }

    private final GameOption.Bool SHOW_TRICK_COUNT = new GameOption.Bool(
            true,
            Component.translatable("rule.bridge.show_trick_count"),
            Component.translatable("rule.bridge.show_trick_count.description")
    );

    private final GameOption.Bool ENABLE_BOTS = new GameOption.Bool(
            true,
            Component.translatable("rule.bridge.enable_bots"),
            Component.translatable("rule.bridge.enable_bots.description")
    );

    private final GameOption.Bool VULNERABLE = new GameOption.Bool(
            false,
            Component.translatable("rule.bridge.vulnerable"),
            Component.translatable("rule.bridge.vulnerable.description")
    );

    /** Staging pile used while dealing. Not added as a slot, so it never renders. */
    private final GameSlot dealPile = new GameSlot();
    private final PileSlot trickPile;

    private final Random random = new Random();
    private final Map<CardPlayer, Integer> tricksTaken = new HashMap<>();
    private final List<PlayedCard> currentTrick = new ArrayList<>();

    private Phase phase = Phase.DEALING;
    private int dealerSeat;
    private Auction auction = new Auction(0);

    /** Seat that owes the next call or card; see the class comment on seats versus actors. */
    private int seat;
    private int declarerSeat = -1;

    /** Cached {@link #compass(int)} table, one entry per seat; rebuilt when the roster changes. */
    @Nullable
    private int[] compassBySeat;

    /**
     * Seat that opened the trick currently on the table, or {@code -1} when it is empty.
     *
     * <p>Mirrored to the client so the pile can be drawn one card per compass cell: entry {@code i} of
     * the pile belongs to {@code trickLeaderSeat + i} seats clockwise.
     */
    private int trickLeaderSeat = -1;

    /**
     * Seat that won the trick still on the table, or {@code -1} while it is unfinished.
     *
     * <p>Only used to aim the client's collection animation: the cards themselves are already on the
     * table by the time this is set, and the trick is swept the same either way.
     */
    private int trickWinnerSeat = -1;

    /**
     * Hand the carried card was picked up from, or {@code null} when nothing is being carried.
     *
     * <p>A carried card has already left its hand, so putting it down into a <em>different</em> hand
     * would leave that hand one card too big. Tracking the origin is what lets a hand slot refuse it and
     * makes the pile the only way to get rid of a card — which is the whole of "the dummy's cards must
     * not end up in the declarer's hand". Server-side only: the client never runs the insert.
     */
    @Nullable
    private CardPlayer pickupOwner;

    @Nullable
    private Suit ledSuit;
    private int trickNumber;

    private int resultScore;
    @Nullable
    private Boolean resultMade;

    /** Guards against {@code runGame} stacking a second {@code afterPlay} on the same future. */
    private boolean awaitingPlay;

    private int lingerTicks;
    @Nullable
    private Runnable afterLinger;

    private record PlayedCard(int seat, Card card) {
    }

    public BridgeGame(List<CardPlayer> players, Deck deck) {
        // A mutable copy: rebuildRoster() adds and removes bot seats in place.
        super(new ArrayList<>(players), deck);

        float centerX = CardTableBlockEntity.TABLE_WIDTH / 2f - CardImage.WIDTH / 2f;
        float centerY = CardTableBlockEntity.TABLE_HEIGHT / 2f - CardImage.HEIGHT / 2f;
        this.trickPile = addSlot(new PileSlot(this, new LinkedList<>(), centerX, centerY, 0f, 0f, null));
    }

    // ---------------------------------------------------------------------------------------------
    // Seating
    // ---------------------------------------------------------------------------------------------

    /**
     * Seat of {@code player}, by identity.
     *
     * <p>Deliberately not {@code List.indexOf}: {@link BridgeBot#equals} answers {@code true} for any
     * real player so the table block keeps the game alive, which would make {@code indexOf} report the
     * first real seat for every bot.
     *
     * @return the seat, or {@code -1} when the player is not at this table
     */
    public int getSeat(@Nullable CardPlayer player) {
        if (player == null) {
            return -1;
        }
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i) == player) {
                return i;
            }
        }
        return -1;
    }

    /** Seat {@code seatOffset} seats clockwise from {@code from}, wrapping around the table. */
    public int seatFrom(int from, int seatOffset) {
        int size = players.size();
        return size == 0 ? 0 : Math.floorMod(from + seatOffset, size);
    }

    public CardPlayer playerAt(int seat) {
        return players.get(Math.floorMod(seat, players.size()));
    }

    // ------------------------------------------------------------------ where people sit ---

    /**
     * Compass of a seat: {@code 0} north, {@code 1} east, {@code 2} south, {@code 3} west, clockwise.
     *
     * <h2>Why the seat index is not the compass</h2>
     *
     * <p>Charta hands the game its players already ordered around the table (see
     * {@code CardTableBlockEntity.getOrderedPlayers}), but the order is <em>rotated</em>: it picks a
     * random player, walks the ring clockwise from them, and the shuffling means seat 0 is whoever
     * happened to come out first. So "seat 2" says nothing about which side of the table somebody is
     * on, and nailing north to seat 0 puts the whole compass at a random angle -- a player sitting on
     * the north chair could be labelled west, and their own fan could be drawn down the side of the
     * screen.
     *
     * <p>The compass is therefore read back off the world: a chair faces the table, so the direction a
     * seated player's chair points is the exact opposite of the side they are sitting on. Bots have no
     * body to ask, but the seats between the ones we do know run clockwise, so the empty chairs are
     * filled in from a best-fit rotation of the known ones.
     *
     * <p>Computed identically on both sides from the same roster rather than synced: the client knows
     * every chair and every player's seat on it, and a mismatch would only ever be a frame of lag.
     */
    public int compass(int seat) {
        if (players.isEmpty()) {
            return Math.floorMod(seat, PLAYERS);
        }
        if (compassBySeat == null || compassBySeat.length != players.size()) {
            compassBySeat = computeCompass();
        }
        return compassBySeat[Math.floorMod(seat, compassBySeat.length)];
    }

    /** The seat sitting in {@code compass}, or {@code -1} when no seat maps there. */
    public int seatAtCompass(int compass) {
        int wanted = Math.floorMod(compass, PLAYERS);
        for (int seat = 0; seat < players.size(); seat++) {
            if (compass(seat) == wanted) {
                return seat;
            }
        }
        return -1;
    }

    /** Compass per seat, from the chairs the real players are sitting on; {@code -1} until filled in. */
    private int[] computeCompass() {
        int size = players.size();
        int[] map = new int[size];
        Arrays.fill(map, -1);
        boolean[] taken = new boolean[PLAYERS];

        for (int seat = 0; seat < size; seat++) {
            int side = seatedCompass(players.get(seat));
            // Two players on the same side can only happen while somebody is still standing up.
            if (side < 0 || taken[side]) {
                continue;
            }
            map[seat] = side;
            taken[side] = true;
        }

        // Seats run clockwise, so an unknown seat is "one more" than its neighbour. Which chair the run
        // starts on is not knowable from two or three known seats alone, so the offset that fits the
        // most of them wins; an empty table falls back to compass == seat.
        int offset = 0;
        int best = -1;
        for (int candidate = 0; candidate < PLAYERS; candidate++) {
            int fit = 0;
            for (int seat = 0; seat < size; seat++) {
                if (map[seat] == Math.floorMod(candidate + seat, PLAYERS)) {
                    fit++;
                }
            }
            if (fit > best) {
                best = fit;
                offset = candidate;
            }
        }

        for (int seat = 0; seat < size; seat++) {
            if (map[seat] >= 0) {
                continue;
            }
            for (int step = 0; step < PLAYERS; step++) {
                int side = Math.floorMod(offset + seat + step, PLAYERS);
                if (!taken[side]) {
                    map[seat] = side;
                    taken[side] = true;
                    break;
                }
            }
            if (map[seat] < 0) {
                map[seat] = Math.floorMod(seat, PLAYERS);
            }
        }
        return map;
    }

    /** Side of the table {@code player} is sitting on, or {@code -1} when they are not in a chair. */
    private static int seatedCompass(CardPlayer player) {
        LivingEntity entity = player.getEntity();
        if (entity == null) {
            return -1;
        }
        Direction facing = GameChairBlock.getSeatedDirection(entity);
        // The chair looks at the table, so the player is on the far side of it.
        return facing == null ? -1 : switch (facing) {
            case SOUTH -> 0;
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> -1;
        };
    }

    @Override
    public void setCurrentPlayer(int index) {
        if (players.isEmpty()) {
            return;
        }
        this.seat = Math.floorMod(index, players.size());
        // Never the dummy: the dummy is played by declarer, so declarer is the one who must act.
        this.currentPlayer = actorFor(this.seat);
    }

    /** The player who has to act for {@code seat}: declarer whenever the dummy is on turn. */
    private CardPlayer actorFor(int seat) {
        if (declarerSeat >= 0 && seat == getDummySeat()) {
            return playerAt(declarerSeat);
        }
        return playerAt(seat);
    }

    public Deck getDeck() {
        return deck;
    }

    /**
     * Seat that owes the next call or card.
     *
     * <p>Deliberately the <em>seat</em> and not {@code players.indexOf(currentPlayer)}: while the dummy
     * is on turn those are two different players, and it is the seat that the layout, the trick pile and
     * the turn mirror are all written against.
     */
    public int getSeatOfCurrentPlayer() {
        return seat;
    }

    /** Seat of the dummy, or {@code -1} before the auction has produced a contract. */
    public int getDummySeat() {
        return declarerSeat < 0 || players.size() < PLAYERS ? -1 : seatFrom(declarerSeat, 2);
    }

    public boolean isDummy(@Nullable CardPlayer player) {
        int dummy = getDummySeat();
        return dummy >= 0 && getSeat(player) == dummy;
    }

    /**
     * Whether {@code player} may pick a card up out of {@code owner}'s hand, or put one back into it.
     *
     * <p>Your own hand is yours. The dummy's is <em>not</em> the dummy's: in bridge it belongs to the
     * declarer, who plays both hands, so the dummy sits and watches their own cards being played. That
     * leaves exactly one person besides the owner, and the declarer is the dummy's partner, so this is
     * also the "only their partner touches the dummy's cards" rule.
     *
     * <p>This is only about <em>handling</em>; the play itself is gated separately by
     * {@link dev.lucaargolo.charta.common.game.api.PlaySlot}, which only ever lets {@code
     * currentPlayer} -- the declarer while the dummy is on turn -- put a card on the pile.
     *
     * <p>Charta's default is that any hand is fair game ({@code GameSlot.canRemoveCard} is just "not
     * empty"), and the two defenders' hands happen to be closed anyway because the menu sees their
     * censored slots, so the dummy was the one hole: without this, anybody at the table could drag the
     * dummy's cards around and the dummy could drag their own.
     */
    private boolean canHandleHand(@Nullable CardPlayer player, CardPlayer owner) {
        if (player == null) {
            return false;
        }
        if (player == owner) {
            // The dummy hands their own cards over to the declarer rather than playing them.
            return !isDummy(owner);
        }
        return isDummy(owner) && getSeat(player) == declarerSeat;
    }

    /** Wraps a bot identity check that has to work on the client too, where they are plain AutoPlayers. */
    private static boolean isBot(@Nullable CardPlayer player) {
        return player instanceof BridgeBot || (player != null && player.getEntity() == null);
    }

    /**
     * Recomputes the bot seats from the current option. Called from {@link #setRawOptions}, i.e. right
     * after construction on both the server and the client, which is what keeps the two rosters
     * identical.
     */
    private void rebuildRoster() {
        if (players.isEmpty()) {
            // Template games (option previews) stay player-less on purpose.
            return;
        }
        // Seats are about to move: a bot's chair is derived from the real players around it.
        compassBySeat = null;
        while (!players.isEmpty() && isBot(players.getLast())) {
            players.removeLast();
        }
        if (ENABLE_BOTS.get()) {
            int number = 1;
            while (players.size() < PLAYERS) {
                players.add(new BridgeBot(number++));
            }
        }
    }

    @Override
    public void setRawOptions(byte[] options) {
        super.setRawOptions(options);
        rebuildRoster();
    }

    // ---------------------------------------------------------------------------------------------
    // Deck / player validation
    // ---------------------------------------------------------------------------------------------

    @Override
    public Predicate<Deck> getDeckPredicate() {
        return deck -> deck.getCards().size() == 52
                && new HashSet<>(deck.getSuits()).equals(new HashSet<>(Suits.STANDARD));
    }

    @Override
    public Predicate<Card> getCardPredicate() {
        // Called from the super constructor, so it must not touch instance state.
        return card -> Suits.STANDARD.contains(card.suit()) && Ranks.STANDARD.contains(card.rank());
    }

    @Override
    public int getMinPlayers() {
        // With bots on, one person is enough: the other three chairs are filled for them.
        return ENABLE_BOTS.get() ? 1 : PLAYERS;
    }

    @Override
    public int getMaxPlayers() {
        return PLAYERS;
    }

    @Override
    public Optional<Component> playerPredicate(List<CardPlayer> players) {
        if (!ENABLE_BOTS.get() && players.size() % 2 != 0) {
            return Optional.of(Component.translatable("message.bridge.even_teams"));
        }
        return Optional.empty();
    }

    @Override
    public BridgeMenu createMenu(int containerId, Inventory playerInventory, AbstractCardMenu.Definition definition) {
        return new BridgeMenu(containerId, playerInventory, definition);
    }

    @Override
    public List<GameOption<?>> getOptions() {
        return List.of(SHOW_TRICK_COUNT, ENABLE_BOTS, VULNERABLE);
    }

    /**
     * A hand slot that hands out one card per click.
     *
     * <p>Charta's default {@code GameSlot.removeAll()} says "a click takes this card and everything
     * after it", which is right for rummy style games and wrong for a trick taker: dropping a fistful
     * of cards on the pile is rejected because a play is exactly one card.
     *
     * <p>The gate on putting a card back into the <em>wrong</em> hand lives here too; see
     * {@link HandSlot} and {@link #pickupOwner}.
     */
    @Override
    protected GameSlot createPlayerHand(CardPlayer player) {
        return new HandSlot(this, player, player.hand());
    }

    /**
     * The dummy's hand is face up in front of everybody.
     *
     * <p>The menu's preview slots ask for a censored hand, so overriding this for the dummy is all it
     * takes for every viewer to be pushed real cards — the server computes the slot contents and ships
     * them, so the client needs nothing extra.
     */
    @Override
    public GameSlot getCensoredHand(@Nullable CardPlayer viewer, CardPlayer player) {
        if (phase != Phase.DEALING && isDummy(player) && hands.containsKey(player)) {
            return getPlayerHand(player);
        }
        return super.getCensoredHand(viewer, player);
    }

    // ---------------------------------------------------------------------------------------------
    // Game flow
    // ---------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        if (lingerTicks > 0) {
            // Hold the finished trick, or the final score, on the table; freeze the bots meanwhile so
            // they cannot start the next thing early.
            if (--lingerTicks == 0 && afterLinger != null) {
                Runnable action = afterLinger;
                afterLinger = null;
                action.run();
            }
            return;
        }
        super.tick();
    }

    private void linger(int ticks, Runnable action) {
        this.afterLinger = action;
        this.lingerTicks = ticks;
    }

    @Override
    public void startGame() {
        // Whoever is sitting down now may not be who was sitting down when the roster was built.
        compassBySeat = null;
        dealPile.clear();
        trickPile.clear();
        currentTrick.clear();
        tricksTaken.clear();
        ledSuit = null;
        trickNumber = 0;
        trickLeaderSeat = -1;
        trickWinnerSeat = -1;
        pickupOwner = null;
        awaitingPlay = false;
        lingerTicks = 0;
        afterLinger = null;
        declarerSeat = -1;
        resultScore = 0;
        resultMade = null;
        phase = Phase.DEALING;

        dealerSeat = players.isEmpty() ? 0 : random.nextInt(players.size());
        auction = new Auction(dealerSeat);

        for (CardPlayer player : players) {
            player.resetPlay();
            getPlayerHand(player).clear();
            getCensoredHand(player).clear();
            tricksTaken.put(player, 0);
        }

        // A re-deal hands back cards that are already face up; put them face down first.
        for (Card card : gameDeck) {
            if (!card.flipped()) {
                card.flip();
            }
        }
        dealPile.addAll(gameDeck);
        dealPile.shuffle();

        // Deal round-robin, one card per scheduled tick, so the table animates.
        for (int i = 0; i < HAND_SIZE; i++) {
            for (CardPlayer player : players) {
                scheduledActions.add(() -> {
                    player.playSound(ModSounds.CARD_DRAW.get());
                    dealCards(dealPile, player, 1);
                });
                scheduledActions.add(() -> {
                });
            }
        }

        isGameReady = false;
        isGameOver = false;
        setCurrentPlayer(dealerSeat);

        table(Component.translatable("message.bridge.game_started"));
    }

    @Override
    public void runGame() {
        if (!isGameReady || isGameOver || awaitingPlay || currentPlayer == null) {
            return;
        }
        switch (phase) {
            case DEALING -> beginAuction();
            case AUCTION -> awaitCall();
            case PLAY -> awaitCard();
            case RESULT -> {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Auction
    // ---------------------------------------------------------------------------------------------

    private void beginAuction() {
        // Every card is out, so this is the one moment the fans can be rearranged without the player
        // watching a card move under their cursor. Deal order is random, and looking at a hand that is
        // in dealing order rather than suit order is the single biggest thing that makes a bridge hand
        // hard to read. Plays only ever remove cards, so the order holds for the rest of the deal.
        for (CardPlayer player : players) {
            sortHand(player);
        }
        phase = Phase.AUCTION;
        setCurrentPlayer(dealerSeat);
        table(Component.translatable("message.bridge.auction_started", playerAt(dealerSeat).getColoredName()));
        table(Component.translatable("message.charta.its_player_turn", currentPlayer.getColoredName()));
        awaitCall();
    }

    private void awaitCall() {
        CardPlayer bidder = currentPlayer;
        if (bidder == null || phase != Phase.AUCTION) {
            return;
        }
        awaitingPlay = true;
        bidder.afterPlay(play -> {
            awaitingPlay = false;
            bidder.resetPlay();
            if (phase != Phase.AUCTION) {
                return;
            }
            // A bot encodes its call in the (card-less) play's slot; a null play means pass.
            applyCall(play == null ? BridgeBid.PASS : play.slot());
        });
    }

    /** Hands a call to the player on turn, from a menu click. */
    public void submitCall(CardPlayer player, int call) {
        if (phase != Phase.AUCTION || isGameOver || !awaitingPlay || player != currentPlayer) {
            return;
        }
        player.play(new GamePlay(List.of(), call));
    }

    private void applyCall(int call) {
        if (phase != Phase.AUCTION || isGameOver) {
            return;
        }
        CardPlayer bidder = currentPlayer;
        int from = seat;

        if (!auction.isLegal(call, from)) {
            table(Component.translatable("message.bridge.illegal_call", BridgeBid.name(call)));
            call = BridgeBid.PASS;
        }
        auction.call(call, from);
        table(Component.translatable("message.bridge.called", bidder.getColoredName(), BridgeBid.name(call)));

        if (auction.isOver()) {
            concludeAuction();
            return;
        }

        setCurrentPlayer(seatFrom(from, 1));
        table(Component.translatable("message.charta.its_player_turn", currentPlayer.getColoredName()));
        awaitCall();
    }

    private void concludeAuction() {
        if (auction.isPassedOut()) {
            table(Component.translatable("message.bridge.passed_out"));
            startGame();
            return;
        }

        declarerSeat = auction.declarer();
        phase = Phase.PLAY;
        table(Component.translatable("message.bridge.contract", contractName(), playerAt(declarerSeat).getColoredName()));
        if (auction.doubling() > 0) {
            table(Component.translatable(auction.doubling() == 2
                    ? "message.bridge.redoubled_note" : "message.bridge.doubled_note"));
        }
        table(Component.translatable("message.bridge.dummy_is", playerAt(getDummySeat()).getColoredName()));

        // The declarer's left hand opponent leads.
        setCurrentPlayer(seatFrom(declarerSeat, 1));
        table(Component.translatable("message.charta.its_player_turn", currentPlayer.getColoredName()));
        awaitCard();
    }

    // ---------------------------------------------------------------------------------------------
    // Play
    // ---------------------------------------------------------------------------------------------

    private void awaitCard() {
        CardPlayer actor = currentPlayer;
        if (actor == null || phase != Phase.PLAY) {
            return;
        }
        awaitingPlay = true;
        actor.afterPlay(play -> {
            awaitingPlay = false;
            actor.resetPlay();
            if (phase != Phase.PLAY) {
                return;
            }
            if (play == null || play.cards().isEmpty()) {
                // Only reachable if a hand somehow empties early: keep the board moving.
                table(Component.translatable("message.bridge.passed", actor.getColoredName()));
                nextSeat();
                runGame();
                return;
            }

            Card card = play.cards().getLast();
            CardPlayer owner = playerAt(seat);
            actor.playSound(ModSounds.CARD_PLAY.get());

            // The trick's first card is played by its leader, and the pile is drawn one cell per seat
            // clockwise from there, so the client needs that seat rather than the cards alone.
            if (currentTrick.isEmpty()) {
                trickLeaderSeat = seat;
                trickWinnerSeat = -1;
            }
            pickupOwner = null;

            // Bots have no menu, so their card has to be moved by hand. A real player's card already
            // travelled hand -> carried -> pile through the slot click payload, whether it came out of
            // their own hand or (as declarer) out of the dummy's.
            if (actor.shouldCompute() && getPlayerHand(owner).remove(card)) {
                removePlaceholder(owner);
            }

            play(owner, Component.translatable("message.bridge.played_a_card",
                    Component.translatable(deck.getCardTranslatableKey(card)).withColor(deck.getCardColor(card))));

            if (ledSuit == null) {
                ledSuit = card.suit();
            }
            currentTrick.add(new PlayedCard(seat, card));
            syncTrickPile();

            if (currentTrick.size() >= players.size()) {
                completeTrick();
            } else {
                nextSeat();
                runGame();
            }
        });
    }

    /**
     * Rewrites the pile from the trick log, in play order.
     *
     * <p>Two paths put a card into the pile and they do not agree on where: a bot appends through
     * {@code addLast}, while a real player's card arrives through {@code CardSlot.insertCards}, which
     * prepends because the pile always reports itself empty (see {@link PileSlot}). Rebuilding the list
     * from {@link #currentTrick} — the one place a played card is recorded — makes the two agree, and it
     * is what lets the client place entry {@code i} on the leader's {@code i}th seat.
     */
    private void syncTrickPile() {
        List<Card> played = new ArrayList<>(currentTrick.size());
        for (PlayedCard entry : currentTrick) {
            played.add(entry.card());
        }
        trickPile.setCards(played);
    }

    /** Keeps the face-down hand-size mirror in step when a card is moved programmatically. */
    private void removePlaceholder(CardPlayer owner) {
        GameSlot placeholders = censoredHands.get(owner);
        if (placeholders != null && placeholders != getPlayerHand(owner) && !placeholders.isEmpty()) {
            placeholders.removeLast();
        }
    }

    private void nextSeat() {
        setCurrentPlayer(seatFrom(seat, 1));
        table(Component.translatable("message.charta.its_player_turn", currentPlayer.getColoredName()));
    }

    private void completeTrick() {
        int winner = findTrickWinner();
        if (winner < 0) {
            trickWinnerSeat = -1;
            sweepTrick(-1);
            return;
        }

        CardPlayer winnerPlayer = playerAt(winner);
        tricksTaken.merge(winnerPlayer, 1, Integer::sum);
        trickNumber++;
        // Published before the linger so the client can aim its collection animation at the winner;
        // the pile is still full at this point and stays full for the whole linger.
        trickWinnerSeat = winner;
        table(Component.translatable("message.bridge.trick_won", winnerPlayer.getColoredName(), tricksTaken.get(winnerPlayer)));

        boolean last = trickNumber >= TRICKS;
        sweepTrick(last ? -1 : winner);
    }

    /** Leaves the completed trick face up for a moment, then clears it and hands the lead over. */
    private void sweepTrick(int nextLeader) {
        afterLinger = () -> {
            trickPile.clear();
            currentTrick.clear();
            trickWinnerSeat = -1;
            ledSuit = null;
            // Anyone who clicked while the finished trick was still on the table completed an
            // unlistened future; throw those away before the next turn is set up.
            players.forEach(CardPlayer::resetPlay);

            if (nextLeader < 0) {
                endGame();
                return;
            }

            setCurrentPlayer(nextLeader);
            table(Component.translatable("message.charta.its_player_turn", currentPlayer.getColoredName()));
            runGame();
        };
        lingerTicks = TRICK_LINGER;
    }

    /**
     * Highest card of the trick: the highest trump if any was played, otherwise the highest card of
     * the suit that was led.
     *
     * <p>Ranks are compared through {@link #rankOrder}, <em>not</em> through {@code Ranks.compareTo}:
     * the enum is ordered ace-low (ace = 1 ... king = 13), so a raw comparison hands the trick to the
     * queen over the ace. This was the one rank comparison that missed the ace-high fold — the hand
     * sort already used it, which is why the fan looked right while the tricks were awarded wrong.
     *
     * @return the winning seat, or {@code -1} for an empty trick
     */
    private int findTrickWinner() {
        Suit trump = trumpSuit();
        PlayedCard best = null;
        for (PlayedCard played : currentTrick) {
            if (best == null) {
                best = played;
                continue;
            }
            boolean playedTrump = trump != null && played.card().suit() == trump;
            boolean bestTrump = trump != null && best.card().suit() == trump;
            if (playedTrump != bestTrump) {
                if (playedTrump) {
                    best = played;
                }
                continue;
            }
            if (!played.card().suit().equals(best.card().suit())) {
                // Neither is trump and it does not follow the current best suit: it cannot win.
                continue;
            }
            if (rankOrder(played.card()) > rankOrder(best.card())) {
                best = played;
            }
        }
        return best == null ? -1 : best.seat();
    }

    @Nullable
    private Suit trumpSuit() {
        return declarerSeat < 0 || !auction.hasContract() ? null : BridgeBid.suit(auction.highest());
    }

    @Override
    public boolean canPlay(CardPlayer player, GamePlay play) {
        // awaitingPlay must *not* gate this: while the turn is being awaited it is set, and that is
        // exactly when the current player (or their bot) is expected to hand a card over.
        if (phase != Phase.PLAY || !isGameReady || isGameOver || lingerTicks > 0 || player != currentPlayer) {
            return false;
        }

        List<Card> cards = play.cards();
        if (cards.size() != 1) {
            return false;
        }

        Card card = cards.getLast();
        if (!getCardPredicate().test(card)) {
            return false;
        }

        // The card has to come out of the acting seat's hand, i.e. the dummy's when the dummy is on
        // turn. A real player's card sits in their carried slot by now, so that counts too.
        CardPlayer owner = playerAt(seat);
        boolean fromOwner = getPlayerHand(owner).stream().anyMatch(held -> held.equals(card));
        if (!fromOwner && getFullHand(player).noneMatch(held -> held.equals(card))) {
            return false;
        }

        // Follow suit when able to.
        if (ledSuit != null && !card.suit().equals(ledSuit) && hasSuit(owner, ledSuit)) {
            return false;
        }

        return true;
    }

    private boolean hasSuit(CardPlayer player, Suit suit) {
        return getFullHand(player).anyMatch(card -> card.suit().equals(suit));
    }

    @Override
    public @Nullable GamePlay getBestPlay(CardPlayer player) {
        if (phase == Phase.AUCTION) {
            int bidder = getSeat(player);
            return bidder < 0 ? null : new GamePlay(List.of(), BridgeBidding.choose(auction, player, bidder));
        }
        if (phase != Phase.PLAY) {
            return null;
        }
        int slot = trickPile.getIndex();
        CardPlayer owner = playerAt(seat);
        for (Card card : getFullHand(owner).toList()) {
            if (canPlay(player, new GamePlay(List.of(card), slot))) {
                return new GamePlay(List.of(card), slot);
            }
        }
        for (Card card : getFullHand(player).toList()) {
            if (canPlay(player, new GamePlay(List.of(card), slot))) {
                return new GamePlay(List.of(card), slot);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Scoring
    // ---------------------------------------------------------------------------------------------

    @Override
    public void endGame() {
        if (isGameOver) {
            return;
        }
        if (phase == Phase.RESULT) {
            if (lingerTicks == 0) {
                announceResult();
            }
            return;
        }
        score();
        phase = Phase.RESULT;
        linger(RESULT_LINGER, this::announceResult);
    }

    /** Duplicate scoring: contract points and bonuses when made, undertrick penalties when not. */
    private void score() {
        resultScore = 0;
        resultMade = null;
        if (declarerSeat < 0 || !auction.hasContract()) {
            return;
        }

        int level = auction.level();
        int strain = auction.strain();
        int required = 6 + level;
        int taken = sideTricks(declarerSeat);
        boolean vulnerable = VULNERABLE.get();
        int multiplier = auction.doubling() == 0 ? 1 : auction.doubling() == 1 ? 2 : 4;

        if (taken >= required) {
            resultMade = true;
            int contractPoints = trickPoints(strain, level) * multiplier;
            int score = contractPoints;
            // Game bonus once the contract is worth 100 or more, part score below that.
            score += contractPoints >= 100 ? (vulnerable ? 500 : 300) : 50;
            if (level == 6) {
                score += vulnerable ? 750 : 500;
            } else if (level == 7) {
                score += vulnerable ? 1500 : 1000;
            }
            if (auction.doubling() == 1) {
                score += 50;
            } else if (auction.doubling() == 2) {
                score += 100;
            }
            resultScore = score;
        } else {
            resultMade = false;
            resultScore = -undertrickPenalty(required - taken, auction.doubling(), vulnerable);
        }
    }

    private static int trickPoints(int strain, int level) {
        int perTrick = switch (strain) {
            case 0, 1 -> 20;   // clubs, diamonds
            default -> 30;     // hearts, spades, notrump
        };
        // Notrump's first trick is worth 40, i.e. ten more than the rest.
        return perTrick * level + (strain == BridgeBid.NOTRUMP ? 10 : 0);
    }

    private static int undertrickPenalty(int undertricks, int doubling, boolean vulnerable) {
        if (doubling == 0) {
            return undertricks * (vulnerable ? 100 : 50);
        }
        int total = 0;
        for (int i = 1; i <= undertricks; i++) {
            if (vulnerable) {
                total += i == 1 ? 200 : 300;
            } else {
                total += i == 1 ? 100 : i <= 3 ? 200 : 300;
            }
        }
        return doubling == 2 ? total * 2 : total;
    }

    private void announceResult() {
        if (isGameOver) {
            return;
        }
        int declarerTricks = sideTricks(declarerSeat);
        int defendersTricks = trickNumber - declarerTricks;

        table(Component.translatable("message.bridge.board_over", contractName(), declarerTricks, 6 + auction.level()));

        for (int i = 0; i < players.size(); i++) {
            CardPlayer player = players.get(i);
            boolean declaring = Math.floorMod(i - declarerSeat, 2) == 0;
            boolean won = resultMade != null && (resultMade == declaring);
            Component title;
            if (resultMade == null) {
                title = Component.translatable("message.charta.draw").withStyle(ChatFormatting.YELLOW);
            } else if (won) {
                title = Component.translatable(declaring ? "message.bridge.contract_made" : "message.bridge.defence_success")
                        .withStyle(ChatFormatting.GREEN);
            } else {
                title = Component.translatable(declaring ? "message.bridge.contract_failed" : "message.bridge.defence_failed")
                        .withStyle(ChatFormatting.RED);
            }
            Component subtitle = resultMade == null
                    ? Component.translatable("message.bridge.no_contract")
                    : Component.translatable("message.bridge.score_line", contractName(), resultScore);
            player.sendTitle(title, subtitle);
        }

        table(Component.translatable("message.bridge.match_over", resultScore));
        isGameOver = true;
    }

    private int sideTricks(int side) {
        if (side < 0) {
            return 0;
        }
        return tricksTaken.getOrDefault(playerAt(side), 0) + tricksTaken.getOrDefault(playerAt(side + 2), 0);
    }

    // ---------------------------------------------------------------------------------------------
    // Accessors used by the menu / screen
    // ---------------------------------------------------------------------------------------------

    public Phase getPhase() {
        return phase;
    }

    public int getTricksTaken(CardPlayer player) {
        return tricksTaken.getOrDefault(player, 0);
    }

    public int getTrickNumber() {
        return trickNumber;
    }

    public int getDealerSeat() {
        return dealerSeat;
    }

    public Auction getAuction() {
        return auction;
    }

    public int getDeclarerSeat() {
        return declarerSeat;
    }

    /** The contract bid, or {@code 0} before there is one. */
    public int getContract() {
        return auction.hasContract() ? auction.highest() : 0;
    }

    public int getRequiredTricks() {
        return auction.hasContract() ? 6 + auction.level() : 0;
    }

    public int getResultScore() {
        return resultScore;
    }

    @Nullable
    public Boolean getResultMade() {
        return resultMade;
    }

    public boolean isShowingTrickCount() {
        return SHOW_TRICK_COUNT.get();
    }

    public boolean isVulnerable() {
        return VULNERABLE.get();
    }

    public PlaySlot getTrickPile() {
        return trickPile;
    }

    /** Seat that opened the trick on the table, or {@code -1} while the pile is empty. */
    public int getTrickLeaderSeat() {
        return trickLeaderSeat;
    }

    /** Seat that took the trick on the table, or {@code -1} while it is still in progress. */
    public int getTrickWinnerSeat() {
        return trickWinnerSeat;
    }

    // ---------------------------------------------------------------------------------------------
    // Hands
    // ---------------------------------------------------------------------------------------------

    /**
     * Sorts a hand the way a bridge player would hold it: suits grouped in the deck's own suit order,
     * ace high inside each group.
     *
     * <p>Mutates the same list the player entity handed to the game slot, so the entity, the slot and
     * the copy that gets mirrored to the client all stay the same list. That matters because a click
     * sends the card's <em>position</em> in the fan, so the client's order has to be the server's order.
     */
    private void sortHand(CardPlayer player) {
        List<Suit> order = deck.getSuits();
        @SuppressWarnings("unchecked")
        List<Card> hand = (List<Card>) getPlayerHand(player).getCards();
        hand.sort(Comparator
                .comparingInt((Card card) -> order.indexOf(card.suit()))
                .thenComparing(Comparator.comparingInt(BridgeGame::rankOrder).reversed()));
    }

    /**
     * Rank as an integer that puts the ace on top.
     *
     * <p>{@code Ranks} ordinals run ace = 1 up to king = 13, so sorting by them puts the <em>king</em>
     * first and the ace last — the exact opposite of how the game is scored. Folding the ace above the
     * king here, and reversing at the call site, gives the A-K-Q-...-2 hold the players expect.
     */
    private static int rankOrder(Card card) {
        return card.rank() == Ranks.ACE ? Ranks.KING.ordinal() + 1 : card.rank().ordinal();
    }

    /** A hand: remembers where the carried card came from, and refuses it everywhere else. */
    private static final class HandSlot extends GameSlot {

        private final BridgeGame game;
        private final CardPlayer owner;

        private HandSlot(BridgeGame game, CardPlayer owner, List<Card> cards) {
            super(cards);
            this.game = game;
            this.owner = owner;
        }

        @Override
        public void onRemove(CardPlayer player, List<Card> cards, int index) {
            game.pickupOwner = owner;
            super.onRemove(player, cards, index);
        }

        @Override
        public boolean canInsertCard(CardPlayer player, List<Card> cards, int index) {
            // A card lifted out of the dummy belongs to the dummy; dropping it back on the declarer's
            // own fan would leave the declarer holding fourteen cards. Making the other hand refuse it
            // leaves the card in hand, and the pile is then the only place it can go.
            return game.canHandleHand(player, owner)
                    && (game.pickupOwner == null || game.pickupOwner == owner);
        }

        /** Nobody gets to lift a card out of a hand they do not play; see {@link #canHandleHand}. */
        @Override
        public boolean canRemoveCard(CardPlayer player, int index) {
            return !isEmpty() && game.canHandleHand(player, owner);
        }

        /**
         * Charta's default {@code removeAll} says "a click takes this card and everything after it",
         * which is right for rummy style games and wrong for a trick taker: dropping a fistful of cards
         * on the pile is rejected because a play is exactly one card.
         */
        @Override
        public boolean removeAll() {
            return false;
        }
    }

    /**
     * The trick pile.
     *
     * <p>Differs from Charta's {@link PlaySlot} in one place only: it always reports itself empty.
     * {@code GameScreen} draws a slot's own widget whenever its contents are non-empty, and a plain
     * slot paints the last card at a single point, while a trick needs one card per compass direction.
     * Reporting empty keeps that widget out of the way and the painting is done by {@code BridgeScreen}
     * instead — everything else, including the insert validation and the mirroring, is untouched.
     */
    private static final class PileSlot extends PlaySlot {

        private PileSlot(Game<?, ?> game, List<Card> cards, float x, float y, float z, float angle,
                         @Nullable DrawSlot drawSlot) {
            super(game, cards, x, y, z, angle, drawSlot);
        }

        @Override
        public boolean isEmpty() {
            return true;
        }
    }

    /** Readable contract, e.g. {@code 4♥} or {@code 3NT}. */
    public Component contractName() {
        return auction.hasContract()
                ? BridgeBid.name(auction.highest())
                : Component.translatable("bid.bridge.pass");
    }

    /**
     * Value of one mirrored data slot.
     *
     * <p>Called on the server for every slot on every tick, so it must stay cheap and free of side
     * effects.
     */
    public int syncValue(int index) {
        if (index >= SYNC_AUCTION_BASE) {
            int call = index - SYNC_AUCTION_BASE;
            return call < auction.size() ? auction.code(call) : -1;
        }
        return switch (index) {
            case SYNC_PHASE -> phase.ordinal();
            case SYNC_DEALER -> dealerSeat;
            case SYNC_AUCTION_SIZE -> auction.size();
            case SYNC_CONTRACT -> auction.hasContract() ? auction.highest() : 0;
            case SYNC_DECLARER -> declarerSeat;
            case SYNC_DOUBLING -> auction.doubling();
            case SYNC_REQUIRED -> getRequiredTricks();
            case SYNC_TRUMP -> auction.hasContract() ? auction.strain() : -1;
            case SYNC_DECLARER_TRICKS -> sideTricks(declarerSeat);
            case SYNC_DEFENDER_TRICKS -> declarerSeat < 0 ? 0 : trickNumber - sideTricks(declarerSeat);
            case SYNC_TRICK_NUMBER -> trickNumber;
            case SYNC_RESULT -> resultMade == null ? 0 : (resultMade ? 1 : -1);
            case SYNC_SCORE -> resultScore;
            case SYNC_TRICK_LEADER -> trickLeaderSeat;
            case SYNC_TRICK_WINNER -> trickWinnerSeat;
            default -> 0;
        };
    }

}

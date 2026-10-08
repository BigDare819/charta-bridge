package dev.bridge.game;

/**
 * Standalone double-dummy solver for the card-play phase of a bridge deal.
 *
 * <p>Pure {@code java.util}/{@code java.lang}; no third-party dependency. A plain recursive
 * alpha-beta over bitboard state: the four hands live in four {@code long}s, a move is a bit clear,
 * and one array buffers the legal moves per ply so a node allocates nothing.
 *
 * <p>Four things carry the search: a material window (a sound lower and upper bound on the tricks
 * either side can still take), an equivalence fold that stops duplicate-equivalent cards being
 * generated at all, a move order that only hoists the transposition move in front of the generator's
 * own cheap-winner-first order, and an exact-only transposition table keyed on the whole position
 * with the ranks folded so two positions that differ only in which spots were spent share an entry.
 *
 * <p>Card encoding: {@code bit = suit * 13 + (rank - 2)}, suit 0=C 1=D 2=H 3=S, rank 2..14, so bit 0
 * is the two of clubs and bit 51 the ace of spades.
 *
 * <p>Value convention: every search value is "further tricks won by the maximising side", the side
 * whose two seats are in {@code sideMask}. A seat maximises exactly when it is in that mask, so the
 * two participants of one side always face each other across a trick.
 */
public final class BridgeDoubleDummy {

    public static final int CLUBS = 0, DIAMONDS = 1, HEARTS = 2, SPADES = 3, NOTRUMP = 4;
    public static final long ALL_CARDS = (1L << 52) - 1;

    /**
     * Node budget for one solve; past this the search returns a bound instead of hanging.
     *
     * <p>The default is set from what a real deal costs, not from a round number: four deals times every
     * strain times every leader is eighty ordinary solves, and the worst single position seen needed
     * about 89M nodes and twenty seconds. A capped solve is not merely imprecise, it breaks the a+b=13
     * invariant, so the cap has to clear the tail rather than the average, with room for a machine a
     * good deal slower than the one measured on. {@link #lastWasCapped()} is what lets a caller say
     * "approximate" instead of quietly reporting a wrong number.
     */
    private static final long NODE_CAP = Long.getLong("dd.cap", 150_000_000L);

    private static final int TABLE_BITS = Integer.getInteger("dd.ttbits", 21);
    private static final int TABLE_SIZE = 1 << TABLE_BITS;
    private static final int TABLE_MASK = TABLE_SIZE - 1;
    private static final int MAX_PROBE = 8;

    /** Deepest ply: 52 cards plus one resolved trick per lead. */
    private static final int MAX_PLY = 80;
    private static final int MAX_MOVES = 15;

    /** Allocation-free legal-move buffer, one slot per ply. */
    private static final int[][] MOVES = new int[MAX_PLY][MAX_MOVES];

    private static final long[] TABLE_KEY = new long[TABLE_SIZE];
    private static final long[] TABLE_VALUE = new long[TABLE_SIZE];

    private static final int[] CARDS = new int[52];

    private static long hand0, hand1, hand2, hand3;
    private static int trumpSuit;
    private static int sideBits;
    private static long ttHits;
    private static long ttMisses;

    private static long nodeCount;
    private static boolean capped;
    private static long generation;
    private static int maxTricks;
    private static boolean TRACE = false;
    private static final String[] SUITC = {"C", "D", "H", "S"};
    private static final String[] RANKC = {"2", "3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K", "A"};

    static {
        int n = 0;
        for (int s = 0; s < 4; s++) {
            for (int r = 0; r < 13; r++) CARDS[n++] = s * 13 + r;
        }
    }

    private BridgeDoubleDummy() {
    }

    /**
     * Maximum tricks the partnership whose two seats are in {@code sideMask} can take from this
     * position, with BOTH sides playing optimally (double dummy).
     *
     * @param hands    the bitmask of cards still held by seat s (seats 0..3 sit clockwise)
     * @param trump    CLUBS..SPADES, or NOTRUMP
     * @param leader   seat that leads to the current trick (0..3)
     * @param sideMask bitmask (1&lt;&lt;seatA)|(1&lt;&lt;seatB) of the seats whose total is returned
     * @return 0..13, clamped; a position that throws reports 0 rather than propagating
     */
    public static int tricksFor(long[] hands, int trump, int leader, int sideMask) {
        try {
            return solve(hands, trump, leader, sideMask);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Debug: enable move tracing on stderr. */
    public static void trace(boolean on) {
        TRACE = on;
    }

    /** Nodes visited by the last call, for profiling. */
    public static long lastNodes() {
        return nodeCount;
    }

    /** True when the last call hit the internal node cap and returned an approximation. */
    public static boolean lastWasCapped() {
        return capped;
    }

    /**
     * The proved window on the maximiser's total tricks at the start of a position, as
     * {@code [lower, upper]}. Exposed so a test can check the bounds against an independent search:
     * a bound that is wrong in either direction is the one kind of bug that silently corrupts every
     * answer the solver gives.
     */
    public static int[] windowFor(long[] hands, int trump, int leader, int sideMask) {
        prepare(hands, trump, sideMask);
        int[] w = materialWindow(0, leader & 3, -1, -1, 0);
        return new int[] {w[0], w[1]};
    }

    /** The same window for an arbitrary position part-way through a trick, for the same test. */
    public static int[] windowAt(long[] hands, int trump, int sideMask, int tricks, int ply, int seat,
                                 int leadSuit, int winCard, int winSeat, int banked) {
        prepare(hands, trump, sideMask);
        maxTricks = tricks;
        int[] w = materialWindow(ply, seat & 3, leadSuit, winCard, banked);
        return new int[] {w[0], w[1]};
    }

    /** Debug: the moves {@link #fill} would offer {@code seat}, as card names. */
    public static String movesDebug(long[] hands, int trump, int sideMask, int seat, int leadSuit,
                                    int winCard) {
        prepare(hands, trump, sideMask);
        int[] out = new int[MAX_MOVES];
        int n = fill(seat, leadSuit, winCard, out);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(cardName(out[i])).append(' ');
        }
        return sb.toString().trim();
    }

    private static void prepare(long[] hands, int trump, int sideMask) {
        hand0 = hands[0] & ALL_CARDS;
        hand1 = hands[1] & ALL_CARDS;
        hand2 = hands[2] & ALL_CARDS;
        hand3 = hands[3] & ALL_CARDS;
        trumpSuit = trump;
        sideBits = sideMask & 0xF;
        pairUp();
        maxTricks = (Long.bitCount(hand0) + Long.bitCount(hand1)
                + Long.bitCount(hand2) + Long.bitCount(hand3)) / 4;
    }

    private static int solve(long[] hands, int trump, int leader, int sideMask) {
        prepare(hands, trump, sideMask);

        nodeCount = 0L;
        capped = false;
        ttHits = 0L;
        ttMisses = 0L;
        generation++;

        // A single wide search from [0, maxTricks] barely prunes: at the root the maximiser's beta is
        // the whole deal, so the very first lead is explored almost at full width. Instead ask only
        // "is the value at least k?" for a series of k, each a one-wide window that both sides cut
        // against immediately, and bisect on the answer. Fail-soft alpha-beta returns a bound that is
        // valid in both directions, so `lo` and `hi` stay sound.
        int lo = 0;
        int hi = maxTricks;
        if (Boolean.getBoolean("dd.wide")) {
            return search(0, leader & 3, -1, -1, -1, 0, 0, maxTricks);
        }
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int value = search(0, leader & 3, -1, -1, -1, 0, mid, mid + 1);
            if (Boolean.getBoolean("dd.debug")) {
                System.err.println("iter mid=" + mid + " value=" + value + " nodes=" + nodeCount);
            }
            if (value > mid) lo = Math.max(lo, Math.min(value, maxTricks));
            else hi = Math.min(hi, Math.max(value, 0));
            if (capped) break;
        }
        int value = Math.max(0, Math.min(lo, maxTricks));
        return value;
    }

    /**
     * Alpha-beta over the current position.
     *
     * @param ply      cards already played, i.e. the index into the move buffers
     * @param seat     seat to play
     * @param leadSuit suit that was led this trick, or {@code -1} when this seat leads
     * @param winCard  card currently winning the trick, or {@code -1}
     * @param winSeat  seat currently winning the trick, or {@code -1}
     * @param banked   tricks the maximising side has already won
     * @return the maximising side's total tricks from here
     */
    private static int search(int ply, int seat, int leadSuit, int winCard, int winSeat,
                              int banked, int alpha, int beta) {
        if (capped) {
            return alpha;
        }
        if (handOf(seat) == 0L) {
            // The trick winner is out of cards: the deal is over and everything is already banked.
            return banked;
        }
        int[] win = materialWindow(ply, seat, leadSuit, winCard, banked);
        if (NO_WINDOW) {
            win = OPEN_WINDOW;
            OPEN_WINDOW[0] = 0;
            OPEN_WINDOW[1] = maxTricks;
        }
        if (TRACE) System.err.println("FFW ply" + ply + " seat" + seat + " mine="
                + Long.toBinaryString(mineMask()) + " theirs=" + Long.toBinaryString(theirsMask())
                + " w=" + win[0] + "," + win[1]);
        if (TRACE) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ply; i++) sb.append(' ');
            System.err.println(sb + "ply" + ply + " seat" + seat + " banked=" + banked
                    + " lead=" + leadSuit + " win=" + (winCard < 0 ? "-" : cardName(winCard))
                    + " a=" + alpha + " b=" + beta);
        }

        // Computed once per node and handed to both the probe and the store: the hands are put back
        // before the store runs, so the reduced pattern is the same one.
        long canon = CANONICAL ? canonicalPattern() : 0L;
        int ttMove = -1;
        if (!NO_TT) {
            if (lookup(canon, ply, seat, leadSuit, winCard, winSeat, banked, alpha, beta)) {
                ttHits++;
                return ttValue;
            }
            ttMove = ttMoveCard;
        }
        ttMisses++;

        if (win[0] >= beta) {
            store(canon, ply, seat, leadSuit, winCard, winSeat, banked, win[0], LOWER, ttMove);
            return win[0];
        }
        if (win[1] <= alpha) {
            store(canon, ply, seat, leadSuit, winCard, winSeat, banked, win[1], UPPER, ttMove);
            return win[1];
        }
        // Tighten the window with what is already proved about this position. Both numbers are sound
        // bounds on the value here, so they may narrow alpha and beta for the whole subtree, which is
        // what stops a wide root window from being inherited all the way down.
        if (win[0] > alpha) alpha = win[0];
        if (win[1] < beta) beta = win[1];
        int[] moves = MOVES[ply];
        int count = fill(seat, leadSuit, winCard, moves);
        order(moves, count, ttMove, ply, seat);
        boolean maximiser = sideBit(seat);
        int best = maximiser ? -1 : maxTricks + 1;
        int bestMove = -1;
        // The flags are judged against the window as it arrived, not as it drifted while moves were
        // searched: a value that never rose above alpha is an upper bound on this position, not the
        // exact answer, and storing it as exact poisons every later lookup of the same position.
        int alphaOrig = alpha;
        int betaOrig = beta;

        for (int i = 0; i < count; i++) {
            int card = moves[i];
            if (++nodeCount > NODE_CAP) {
                capped = true;
            }

            long before = handOf(seat);
            setHand(seat, before & ~(1L << card));

            int nLead = leadSuit;
            int nWinCard = winCard;
            int nWinSeat = winSeat;
            if (nLead < 0) {
                nLead = card / 13;
                nWinCard = card;
                nWinSeat = seat;
            } else if (beats(card, nWinCard, nLead)) {
                nWinCard = card;
                nWinSeat = seat;
            }

            int value;
            if (ply % 4 == 3) {
                // Fourth card of the trick: it is over, the winner banks it (or not) and leads next.
                int nBanked = banked + (sideBit(nWinSeat) ? 1 : 0);
                value = search(ply + 1, nWinSeat, -1, -1, -1, nBanked, alpha, beta);
            } else {
                value = search(ply + 1, (seat + 1) & 3, nLead, nWinCard, nWinSeat, banked, alpha, beta);
            }

            setHand(seat, before);

            if (maximiser) {
                if (value > best) {
                    best = value;
                    bestMove = card;
                }
                if (best > alpha) alpha = best;
                if (best >= beta) {
                    break;
                }
            } else {
                if (value < best) {
                    best = value;
                    bestMove = card;
                }
                if (best < beta) beta = best;
                if (best <= alpha) {
                    break;
                }
            }
        }
        int flag;
        if (maximiser) {
            flag = best >= betaOrig ? LOWER : best <= alphaOrig ? UPPER : EXACT;
        } else {
            flag = best <= alphaOrig ? UPPER : best >= betaOrig ? LOWER : EXACT;
        }
        store(canon, ply, seat, leadSuit, winCard, winSeat, banked, best, flag, bestMove);
        return best;
    }

    /**
     * Reorders a generated move list so the transposition move is tried first.
     *
     * <p>Only the transposition move, and deliberately not a killer or a cutoff-history table: both were
     * tried and both turned out to be a six- to eight-fold regression on some deals (see the notes in
     * CHARTA-SETUP.md). The generator's own order — the cheapest card that wins the trick, then the
     * cheapest that loses, and the highest card of each suit when leading — is already close to what a
     * pure rank-free search wants, and hoisting an out-of-context card in front of it costs more than it
     * pays.
     */
    private static void order(int[] moves, int count, int ttMove, int ply, int seat) {
        if (NO_ORDER || count < 2 || ttMove < 0) return;
        for (int i = 1; i < count; i++) {
            if (moves[i] == ttMove) {
                int t = moves[0];
                moves[0] = moves[i];
                moves[i] = t;
                return;
            }
        }
    }

    private static final boolean NO_TT = Boolean.getBoolean("dd.nott");
    private static final boolean NO_ORDER = Boolean.getBoolean("dd.noorder");
    private static final boolean NO_WINDOW = Boolean.getBoolean("dd.nowindow");
    private static final boolean CANONICAL = !"false".equals(System.getProperty("dd.canon"));
    private static final int[] OPEN_WINDOW = new int[2];

    /** Transposition hits and misses of the last solve, for tuning. */
    public static long lastHits() {
        return ttHits;
    }

    public static long lastMisses() {
        return ttMisses;
    }

    /**
     * Legal moves for {@code seat}, in the generator's crude order; {@link #order} refines it.
     *
     * <p>Only one card per rank-equivalence class is offered at all; see {@link #collapse}. What is
     * left is ordered by bridge sense rather than by rank: on lead the highest card first, following
     * with the cheapest card that takes the trick and then the cheapest that does not. Taking a trick
     * with the eight keeps the ace, and it puts the answer in front of the search instead of behind it.
     */
    private static int fill(int seat, int leadSuit, int winCard, int[] out) {
        long hand = handOf(seat);
        // The card currently winning the trick has already left its hand, so it is not in the four
        // hands any more — but it is still the card every follow has to be compared against, so it has
        // to count as live or a duck and a cover three ranks apart get folded into one.
        long live = hand0 | hand1 | hand2 | hand3;
        if (winCard >= 0) live |= 1L << winCard;
        int n = 0;
        if (leadSuit < 0) {
            // On lead nothing is forced: strongest first, so a strong lead is tried before a weak one.
            long h = collapse(hand, live);
            while (h != 0L) {
                int c = 63 - Long.numberOfLeadingZeros(h);
                h &= ~(1L << c);
                out[n++] = c;
            }
            return n;
        }
        // Following: a card of the led suit is forced if the hand holds one, otherwise anything goes
        // and every card is both a discard and a possible ruff.
        long pool = hand & suitMask(leadSuit);
        if (pool == 0L) pool = hand;
        pool = collapse(pool, live);
        long takes = 0L;
        long loses = 0L;
        long h = pool;
        while (h != 0L) {
            int c = Long.numberOfTrailingZeros(h);
            h &= h - 1;
            if (beats(c, winCard, leadSuit)) takes |= 1L << c;
            else loses |= 1L << c;
        }
        // Cheapest card that wins the trick, then the cheapest card that does not: taking a trick with
        // the eight keeps the ace, and the search reaches the stronger line first.
        long t = takes;
        while (t != 0L) {
            int c = Long.numberOfTrailingZeros(t);
            t &= t - 1;
            out[n++] = c;
        }
        long l = loses;
        while (l != 0L) {
            int c = Long.numberOfTrailingZeros(l);
            l &= l - 1;
            out[n++] = c;
        }
        return n;
    }

    private static String cardName(int card) {
        return SUITC[card / 13] + RANKC[card % 13];
    }

    /**
     * Drops every card in {@code pool} whose next-higher live card is one of ours.
     *
     * <p>Two of our ranks with nothing left in play between them are the same card. Every rule in the
     * game compares two cards and nothing else, so any other card beats both of them or neither, by the
     * same margin; playing one and keeping the other leaves a position isomorphic to playing the other
     * and keeping the one. One card per such class can therefore be left out of the move list, and this
     * keeps the top card of the class, which is the one a search wants to see first.
     *
     * <p>"Nothing left in play between them" is judged against {@code live}, every card still in the
     * four hands, not against {@code pool} alone, because a card of an <em>opponent's</em> between two of
     * ours makes them different (one beats it, the other does not) while an absent rank makes them the
     * same. Late in a deal the ranks are full of holes, so this is where most of the fold comes from.
     *
     * <p>This is not the same as the transposition table's rank folding: that identifies positions
     * after the fact, this stops the search generating the duplicate branch at all.
     */
    private static long collapse(long pool, long live) {
        long keep = 0L;
        long h = pool;
        while (h != 0L) {
            int c = Long.numberOfTrailingZeros(h);
            h &= h - 1;
            long above = live & ~((1L << (c + 1)) - 1);
            if (above != 0L) {
                int nxt = Long.numberOfTrailingZeros(above);
                // Same suit, and the next live card is one of ours: c is a twin of it, drop c.
                if (nxt / 13 == c / 13 && (pool & (1L << nxt)) != 0L) {
                    continue;
                }
            }
            keep |= 1L << c;
        }
        return keep;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean sideBit(int seat) {
        return ((sideBits >> seat) & 1) != 0;
    }

    private static boolean isTrump(int card) {
        return trumpSuit >= 0 && trumpSuit <= 3 && card / 13 == trumpSuit;
    }

    /** Whether {@code card} takes the trick from {@code winCard}, which was led in {@code leadSuit}. */
    private static boolean beats(int card, int winCard, int leadSuit) {
        if (winCard < 0) return true;
        boolean tc = isTrump(card);
        boolean tw = isTrump(winCard);
        if (tc != tw) return tc;
        if (tc) return (card % 13) > (winCard % 13);
        if (card / 13 != leadSuit) return false;
        if (winCard / 13 != leadSuit) return true;
        return (card % 13) > (winCard % 13);
    }

    private static void setHand(int seat, long h) {
        switch (seat) {
            case 0 -> hand0 = h;
            case 1 -> hand1 = h;
            case 2 -> hand2 = h;
            default -> hand3 = h;
        }
    }

    private static long handOf(int seat) {
        return switch (seat) {
            case 0 -> hand0;
            case 1 -> hand1;
            case 2 -> hand2;
            default -> hand3;
        };
    }

    /** Reused scratch: {@code [0]=lo}, {@code [1]=hi}. */
    private static final int[] MAT_W = new int[2];

    private static long suitMask(int suit) {
        return 0x1FFFL << (13 * suit);
    }

    private static boolean hasTrumpSuit() {
        return trumpSuit >= 0 && trumpSuit <= 3;
    }

    /**
     * Cards of {@code mine} in suit {@code s} that outrank every card {@code theirs} holds in it,
     * counted from the top and stopping at the first gap.
     *
     * <p>Against their D2-D5 our C4 and C3 are a run of two and our C2 is not part of it: the run has
     * to be consecutive, because the first card of ours that they can beat ends it.
     */
    private static int runIn(long mine, long theirs, int s) {
        long mySuit = mine & suitMask(s);
        if (mySuit == 0L) return 0;
        long oppSuit = theirs & suitMask(s);
        int top = Long.numberOfTrailingZeros(Long.highestOneBit(mySuit));
        int oppTop = oppSuit == 0L ? -1 : Long.numberOfTrailingZeros(Long.highestOneBit(oppSuit));
        int n = 0;
        for (int r = top; r > oppTop; r--) {
            if ((mySuit & (1L << r)) == 0L) break;
            n++;
        }
        return n;
    }

    /**
     * Trumps that {@code theirs} cannot beat, taken per hand and then as the larger hand.
     *
     * <p>Such a card wins the trick it is played in no matter who leads: if trumps are led it is the
     * highest one out, and if a side suit is led, playing a trump is a ruff and beats everything but a
     * higher trump. One hand's own cards land in that many distinct tricks (a hand plays once per
     * trick), so that hand takes at least that many; but the two partners play to the SAME trick, so
     * their top trumps can share a trick and the side's bound is the larger hand, not the sum.
     *
     * <p>Half-played tricks are the trap: a trump already lying on the table beats anything lower that
     * has not been played yet, and a hand with one card left has no choice but to play it into that
     * trick. For the hands still to play, the card already winning is therefore part of the bar as
     * well. Hands that have already played are done with this trick and are judged only against the
     * trumps still in their opponents' hands.
     */
    private static int topTrumps(long mine, long theirs, int ply, int seat, int winCard) {
        if (!hasTrumpSuit()) return 0;
        long theirTrumps = theirs & suitMask(trumpSuit);
        long floor = theirTrumps == 0L ? 0L
                : (Long.highestOneBit(theirTrumps) << 1) - 1L;
        long liveWinner = winCard >= 0 && winCard / 13 == trumpSuit ? (1L << winCard) : 0L;
        int played = ply & 3;
        int trickLeader = (seat - played) & 3;
        int best = 0;
        for (int s = 0; s < 4; s++) {
            long hand = handOf(s);
            if ((mine & hand) != hand) continue;
            long bar = floor;
            if (liveWinner != 0L && ((s - trickLeader) & 3) >= played) {
                bar = Math.max(bar, (liveWinner << 1) - 1L);
            }
            long above = (hand & suitMask(trumpSuit)) & ~bar;
            best = Math.max(best, Long.bitCount(above));
        }
        return best;
    }

    /** Scratch for {@link #cashOut}: the four hands and the hand currently on lead. */
    private static final long[] CASH = new long[4];
    private static int cashLead;
    private static int cashOpp1;
    private static int cashOpp2;

    /** For each seat, the other seat on its side. Which seats pair up is the side mask's decision. */
    private static final int[] PARTNER = new int[4];

    private static void pairUp() {
        for (int s = 0; s < 4; s++) {
            PARTNER[s] = -1;
            for (int t = 0; t < 4; t++) {
                if (t != s && sideBit(t) == sideBit(s)) PARTNER[s] = t;
            }
        }
    }

    /**
     * Tricks the side on lead takes by cashing winners in one suit. Walks the suit one trick at a time
     * and returns how far the run got.
     *
     * <p>The partner follows with its <em>lowest</em> card of the suit, so it only takes over when even
     * that card beats the leader's, and whoever wins leads the next one. Each opponent follows with its
     * own cheapest card, which is the worst case for the run and therefore keeps the count a lower
     * bound. The run ends the moment the leader's best card is no longer above both of them.
     */
    private static int cashOneSuit(int suit) {
        long mask = suitMask(suit);
        int taken = 0;
        while (taken < 13) {
            int partner = PARTNER[cashLead];
            int opp1Seat = cashOpp1;
            int opp2Seat = cashOpp2;
            long mine = CASH[cashLead] & mask;
            if (mine == 0L) break;
            long top = Long.highestOneBit(mine);
            long opp1 = CASH[opp1Seat] & mask;
            long opp2 = CASH[opp2Seat] & mask;
            long theirTop = Math.max(opp1 == 0L ? 0L : Long.highestOneBit(opp1),
                    opp2 == 0L ? 0L : Long.highestOneBit(opp2));
            if (theirTop != 0L && top < theirTop) break;
            CASH[cashLead] &= ~top;
            long follow = CASH[partner] & mask;
            long low = follow == 0L ? 0L : Long.lowestOneBit(follow);
            if (low != 0L) CASH[partner] &= ~low;
            if (opp1 != 0L) CASH[opp1Seat] &= ~Long.lowestOneBit(opp1);
            if (opp2 != 0L) CASH[opp2Seat] &= ~Long.lowestOneBit(opp2);
            if (low > top) cashLead = partner;
            taken++;
        }
        return taken;
    }

    /**
     * Tricks the side on lead can cash in a row without ever giving the lead back: trumps first (which
     * also draws theirs), then every side suit once they can no longer ruff.
     */
    private static int cashOut(int leadSeat, int remaining) {
        CASH[0] = hand0;
        CASH[1] = hand1;
        CASH[2] = hand2;
        CASH[3] = hand3;
        cashLead = leadSeat;
        cashOpp1 = -1;
        cashOpp2 = -1;
        for (int s = 0; s < 4; s++) {
            if (s != leadSeat && s != PARTNER[leadSeat]) {
                if (cashOpp1 < 0) cashOpp1 = s;
                else cashOpp2 = s;
            }
        }
        int total = 0;
        if (hasTrumpSuit()) {
            total += cashOneSuit(trumpSuit);
            // The cash-out runs for whichever side is on lead, which is not always the maximiser, so
            // "they still hold a trump" has to be judged against the leader's side.
            boolean theirTrumps = (CASH[cashOpp1] & suitMask(trumpSuit)) != 0L
                    || (CASH[cashOpp2] & suitMask(trumpSuit)) != 0L;
            if (theirTrumps) return Math.min(remaining, total);
        }
        for (int s = 0; s < 4; s++) {
            if (hasTrumpSuit() && s == trumpSuit) continue;
            total += cashOneSuit(s);
        }
        return Math.min(remaining, total);
    }

    /**
     * A sound lower bound on the tricks {@code mine} still wins against {@code theirs} from here.
     *
     * <p>Everything here is <em>proved</em> rather than estimated, because a bound that overstates the
     * opponents' tricks silently over-prunes the search and a bound that overstates our own silently
     * reports tricks nobody can take.
     *
     * <ul>
     *   <li>Always: every trump that outranks all their trumps is a trick (see {@link #topTrumps}).
     *   <li>On lead: {@link #cashOut} plays the hand out against the opponents' best defence and counts
     *       the tricks that fall out, which covers the partner overtaking as well as pulling trumps.
     *   <li>On lead and unable to draw their trumps: the run in a suit only cashes while the partner's
     *       cards there are lower than ours, which is the older, narrower {@link #runIn} count.
     * </ul>
     */
    private static int lowerBound(long mine, long theirs, int ply, int seat, int winCard,
                                  boolean onLead, int remaining) {
        int certain = topTrumps(mine, theirs, ply, seat, winCard);
        if (onLead) {
            int cashed = cashOut(seat, remaining);
            long leader = handOf(seat);
            long others = theirs | (mine & ~leader);
            int theirTrumps = hasTrumpSuit() ? Long.bitCount(theirs & suitMask(trumpSuit)) : 0;
            int leaderTops = hasTrumpSuit() ? runIn(leader, others, trumpSuit) : 0;
            int sum = leaderTops;
            if (theirTrumps == 0 || leaderTops >= theirTrumps) {
                for (int s = 0; s < 4; s++) {
                    if (hasTrumpSuit() && s == trumpSuit) continue;
                    sum += runIn(leader, others, s);
                }
            }
            certain = Math.max(certain, Math.max(cashed, sum));
        }
        return Math.min(remaining, certain);
    }

    /**
     * Bounds the maximiser's total tricks ( {@code banked} included) for the node whose next player is
     * {@code seat}, leading when {@code leadSuit < 0}.
     *
     * <p>{@code banked} and the tricks already over sum to {@code ply / 4}, so the two sides share the
     * {@code remaining} tricks still to come: everything the opponents are certain of is a trick the
     * maximiser certainly cannot have.
     */
    private static int[] materialWindow(int ply, int seat, int leadSuit, int winCard, int banked) {
        long mine = mineMask();
        long theirs = theirsMask();
        int remaining = maxTricks - (ply >> 2);
        // Mid-trick the trick is half played, so the leader-based parts of the bound do not apply and
        // the trump count has to allow for the card already lying on the table.
        boolean clear = leadSuit < 0;
        boolean ourLead = clear && sideBit(seat);
        boolean theirLead = clear && !sideBit(seat);
        int lo = banked + lowerBound(mine, theirs, ply, seat, winCard, ourLead, remaining);
        int hi = banked + remaining - lowerBound(theirs, mine, ply, seat, winCard, theirLead, remaining);
        if (hi < lo) hi = lo;
        MAT_W[0] = Math.max(0, Math.min(lo, maxTricks));
        MAT_W[1] = Math.max(MAT_W[0], Math.min(hi, maxTricks));
        return MAT_W;
    }

    private static long mineMask() {
        long mine = 0L;
        for (int s = 0; s < 4; s++) {
            if (sideBit(s)) mine |= handOf(s);
        }
        return mine;
    }

    private static long theirsMask() {
        long theirs = 0L;
        for (int s = 0; s < 4; s++) {
            if (!sideBit(s)) theirs |= handOf(s);
        }
        return theirs;
    }

    // ------------------------------------------------------------------ transposition table

    /**
     * The hands reduced to what actually decides a trick: for every suit, which hand holds the first,
     * second, ... highest remaining card.
     *
     * <p>Absolute ranks do not matter here. A six and a seven are the same card when nothing between
     * them is left, because every rule in the game compares two cards and nothing else. Two positions
     * that differ only in which ranks were spent are therefore the same position, and folding them
     * together is what keeps the table useful: without it the same shape is stored once per pair of
     * discarded spots and the table thrashes.
     */
    private static long canonicalPattern() {
        long h = 0x243F6A8885A308D3L;
        for (int s = 0; s < 4; s++) {
            long union = (hand0 | hand1 | hand2 | hand3) & (0x1FFFL << (13 * s));
            long pattern = 0L;
            int i = 0;
            long m = union;
            while (m != 0L) {
                int c = Long.numberOfTrailingZeros(m);
                m &= m - 1;
                int owner = ((int) ((hand0 >>> c) & 1L))
                        | ((int) ((hand1 >>> c) & 1L) << 1)
                        | ((int) ((hand2 >>> c) & 1L) << 2)
                        | ((int) ((hand3 >>> c) & 1L) << 3);
                pattern |= ((long) owner) << (i << 2);
                i++;
            }
            h = (h ^ pattern) * 0x100000001B3L + (s << 8) + i;
        }
        return h * 0x9E3779B97F4A7C15L;
    }

    /**
     * Position key: the hands, the trump, the trick state and who is on turn.
     *
     * <p>The fields are laid out in fixed bit ranges rather than mixed together, so two positions can
     * only share a key by sharing every one of them. The card that is winning the trick is described by
     * how many cards of its own suit still beat it plus whether it is a trump: its exact rank is
     * irrelevant, but its suit is not, because a ruff and a plain winner play out differently.
     */
    private static long keyOf(long canon, int ply, int seat, int leadSuit, int winCard, int winSeat,
                              int banked) {
        long k;
        if (CANONICAL) {
            k = canon;
        } else {
            k = hand0 * 0x9E3779B97F4A7C15L;
            k ^= hand1 * 0xC2B2AE3D27D4EB4FL;
            k ^= hand2 * 0x165667B19E3779F9L;
            k ^= hand3 * 0x27D4EB2F165667C5L;
        }
        long winning = (winCard + 1) & 0x3FL;
        long trumpWinner = 0L;
        if (winCard >= 0) {
            boolean wTrump = winCard / 13 == trumpSuit;
            if (CANONICAL) {
                long union = (hand0 | hand1 | hand2 | hand3) & (0x1FFFL << (13 * (winCard / 13)));
                winning = 1 + Long.bitCount(union & ((1L << winCard) - 1));
            }
            trumpWinner = wTrump ? 1L : 0L;
        }
        k ^= ((long) (banked & 31)) << 4;
        k ^= ((long) seat & 3L) << 9;
        k ^= ((long) (trumpSuit & 7)) << 11;
        k ^= ((long) (leadSuit + 1) & 7L) << 14;
        k ^= ((long) (ply & 3)) << 17;
        k ^= winning << 19;
        k ^= ((long) (winSeat + 1) & 7L) << 27;
        k ^= trumpWinner << 30;
        return k;
    }

    private static int probe(long key) {
        int h = (int) (key ^ (key >>> 32));
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & TABLE_MASK;
    }

    /** Transposition entry flags: what the stored number is relative to the true value. */
    private static final int EXACT = 0, LOWER = 1, UPPER = 2;

    private static int ttValue;
    private static int ttFlag;
    /** Best move recorded in the last entry {@link #lookup} touched, or -1. Ordering only. */
    private static int ttMoveCard;

    /**
     * Looks the position up. A hit is usable when it is exact, or when it is a bound that already
     * settles the window; a hit that is neither is reported as a miss.
     */
    private static boolean lookup(long canon, int ply, int seat, int leadSuit, int winCard,
                                  int winSeat, int banked, int alpha, int beta) {
        ttMoveCard = -1;
        long key = keyOf(canon, ply, seat, leadSuit, winCard, winSeat, banked);
        int start = probe(key);
        for (int p = 0; p < MAX_PROBE; p++) {
            int i = (start + p) & TABLE_MASK;
            long k = TABLE_KEY[i];
            if (k == 0L) return false;
            if (k != key) continue;
            long entry = TABLE_VALUE[i];
            if ((entry & 0xFFFFL) != (generation & 0xFFFFL)) return false;
            int move = (int) ((entry >>> 24) & 0x3FL);
            ttMoveCard = move == 0x3FL ? -1 : move;
            int flag = (int) ((entry >>> 16) & 3L);
            int value = (int) ((entry >>> 18) & 0x3FL);
            if (flag == EXACT || (flag == LOWER && value >= beta)
                    || (flag == UPPER && value <= alpha)) {
                ttValue = value;
                ttFlag = flag;
                return true;
            }
            return false;
        }
        return false;
    }

    private static void store(long canon, int ply, int seat, int leadSuit, int winCard,
                              int winSeat, int banked, int value, int flag, int bestMove) {
        if (value < 0 || value > 13) return;
        long key = keyOf(canon, ply, seat, leadSuit, winCard, winSeat, banked);
        int start = probe(key);
        long meta = (generation & 0xFFFFL) | ((long) (flag & 3) << 16) | ((long) (value & 0x3F) << 18)
                | ((long) (bestMove < 0 ? 0x3F : bestMove) << 24);
        for (int p = 0; p < MAX_PROBE; p++) {
            int i = (start + p) & TABLE_MASK;
            long k = TABLE_KEY[i];
            if (k == 0L || k == key || (TABLE_VALUE[i] & 0xFFFFL) != (generation & 0xFFFFL)) {
                TABLE_KEY[i] = key;
                TABLE_VALUE[i] = meta;
                return;
            }
        }
        TABLE_KEY[start] = key;
        TABLE_VALUE[start] = meta;
    }
}

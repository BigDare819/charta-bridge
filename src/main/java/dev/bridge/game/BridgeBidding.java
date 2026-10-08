package dev.bridge.game;

import dev.lucaargolo.charta.common.game.Ranks;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Suit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The bots' bidding system.
 *
 * <p>{@link BridgeStrength#EASY} is the original scheme, kept byte for byte so an easy table reads the way
 * it always did. {@link BridgeStrength#NORMAL} and above run {@link #system}, a natural five card major
 * system spelled out below.
 *
 * <h2>What was wrong with the old bidding</h2>
 *
 * <p>It derived every call from {@code myPoints + 12}. An opening bid "promises 12" was a constant, and
 * nothing the auction produced ever revised it, so:
 *
 * <ul>
 * <li>Neither partner knew what the other had <em>shown</em>, so both kept re-deriving the same optimistic
 * total from their own hand. A responder with 20 opposite an opening counted 33, bid six, the opener
 * counted 27 back, wanted four, and handed it to {@link #legalise} — which can only walk a call
 * <em>up</em>. Four of the suit became six, the responder tried six again, six became seven. Landing in a
 * grand was the default outcome of an auction, not an event.</li>
 * <li>There was no captaincy and no slam try, so there was no way to say "not enough" once the auction had
 * been pushed past what the combined hands were worth.</li>
 * </ul>
 *
 * <p>Two rules fix that and everything here follows from them:
 *
 * <ol>
 * <li><b>Every call promises a high card point range, and the partner bids off the range.</b>
 * {@link #read} replays the auction and works out what the partner has actually promised, so the combined
 * figure is a <em>range</em>. Games and slams compare the <em>bottom</em> of it to bid, the top to invite,
 * and a slam is either reached by the bottom of the range or confirmed by an asking bid.</li>
 * <li><b>A call in the agreed strain means that level.</b> {@link #place} returns the call, or passes when
 * our own side already has that strain at a higher level, instead of letting {@link #legalise} bump it.</li>
 * </ol>
 *
 * <h2>The system</h2>
 *
 * <p>Natural, five card majors, strong notrump, inverted minor raises, Jacoby 2NT, Blackwood, standard
 * overcalls, takeout double. Points are high card points, and a fit adds distribution.
 *
 * <ul>
 * <li><b>Open</b> — 2C 22+, 2NT 20-21, 1NT 15-17 balanced, one of a suit 11-21 with a five card major
 * before a longer minor, weak two 5-10 on a six card suit, three level pre-empt.</li>
 * <li><b>Respond</b> — 2M 6-9, 3M 10-12, Jacoby 2NT 13+ with four trumps, 1NT 6-10, 2NT 11-12, 3NT 13-15,
 * a new suit at the one level 6+ and at the two level 10+, inverted minor raises (2m 10+, 3m 6-9), Stayman
 * and transfers over 1NT.</li>
 * <li><b>Rebid</b> — off the combined range: sign off, invite, blast game, or ask for aces.</li>
 * <li><b>Slam</b> — 4NT Blackwood when there is a fit, the bottom of the combined range reaches
 * {@link #SLAM_ZONE} and the asker holds controls. The answer (5C 0 or 4, 5D 1, 5H 2, 5S 3) is what decides
 * five, six or seven, so a slam is never bid blind. Seven needs every ace and a range that reaches
 * {@link #GRAND_ZONE}.</li>
 * <li><b>Compete</b> — overcall 8-16 at the one level and 11-16 at the two, a 15-18 notrump overcall, a
 * takeout double with the unbid suits, raises of an overcall by range, and no sacrifices.</li>
 * </ul>
 *
 * <h2>Guarantees</h2>
 *
 * <p>Every decision is a pure function of the hand and the auction, so a bot never runs out of turns and
 * never produces an illegal call: {@link #legalise} is the backstop and {@link #system} refuses to pass
 * while partner's last call was forcing.
 */
public final class BridgeBidding {

    /** Combined points at which the bots will blast a game. */
    private static final int GAME_POINTS = 25;
    /** Prints the bot's reasoning while debugging. */
    private static boolean verboseDebug;
    /**
     * Bottom of the combined range at which a pair will spend 4NT to ask for aces.
     *
     * <p>Thirty-one rather than thirty: the combined range is high card points plus a distribution bonus
     * once a fit is known, so thirty here is a twenty-seven point hand in raw points, which is a game and
     * not a slam. Spending 4NT on it is how a good game turns into a bad six.
     */
    private static final int SLAM_ZONE = 31;
    /** Bottom of the combined range at which six is bid off an asking bid. */
    private static final int SMALL_SLAM_POINTS = 33;
    /** Bottom of the combined range at which seven can be bid, with every ace. */
    private static final int GRAND_ZONE = 33;
    /** Points an opening bid promises, used as the EASY partner's inferred strength. */
    private static final int OPENING_MINIMUM = 12;
    private static final int WEAK_TWO_POINTS = 6;

    /** Strains, matching {@link BridgeBid}: clubs and diamonds up to spades, then notrump. */
    private static final int CLUBS = 0;
    private static final int DIAMONDS = 1;
    private static final int HEARTS = 2;
    private static final int SPADES = 3;
    private static final int NOTRUMP = BridgeBid.NOTRUMP;
    /** "No suit", for a hand or a call that has not named one. */
    private static final int NO_STRAIN = -1;

    private BridgeBidding() {
    }

    /** High card points: ace 4, king 3, queen 2, jack 1. */
    public static int highCardPoints(CardPlayer player) {
        return highCardPoints(player.hand());
    }

    /** High card points of a set of cards; see {@link #highCardPoints(CardPlayer)}. */
    public static int highCardPoints(Iterable<Card> cards) {
        int points = 0;
        for (Card card : cards) {
            points += switch (card.rank().ordinal()) {
                case 1 -> 4;    // ace
                case 13 -> 3;   // king
                case 12 -> 2;   // queen
                case 11 -> 1;   // jack
                default -> 0;
            };
        }
        return points;
    }

    /**
     * Count of the cards that normally win a trick on their own: aces, and guarded kings and queens.
     *
     * <p>A king behind an ace still wins one, so a bare king is worth half a trick — counting it as a
     * full one is the classic way to talk yourself into a game that was never there. Queens only count
     * when the suit is long enough to protect them.
     */
    private static int quickTricks(CardPlayer player) {
        int tricks = 0;
        for (Suit suit : Suits.STANDARD) {
            int length = length(player, suit);
            for (Card card : player.hand()) {
                if (card.suit() != suit) {
                    continue;
                }
                if (card.rank() == Ranks.ACE) {
                    tricks += 2;
                } else if (card.rank() == Ranks.KING) {
                    tricks += length >= 2 ? 2 : 1;
                } else if (card.rank() == Ranks.QUEEN && length >= 3) {
                    tricks += 1;
                }
            }
        }
        return tricks;
    }

    /**
     * Picks a call for {@code seat}.
     *
     * @return a legal call code
     */
    public static int choose(Auction auction, CardPlayer player, int seat) {
        return choose(auction, player, seat, BridgeStrength.EASY);
    }

    /** Picks a call for {@code seat} at {@code strength}. */
    public static int choose(Auction auction, CardPlayer player, int seat, BridgeStrength strength) {
        if (auction == null) {
            return BridgeBid.PASS;
        }
        if (strength == BridgeStrength.EASY) {
            return legalise(auction, decide(auction, player, seat), seat);
        }
        return legalise(auction, system(auction, player, seat), seat);
    }

    // ---------------------------------------------------------------------------------------------
    // The original EASY system, unchanged
    // ---------------------------------------------------------------------------------------------

    private static int decide(Auction auction, CardPlayer player, int seat) {
        int points = highCardPoints(player);
        List<Suit> suits = suitsByLength(player);
        if (suits.isEmpty()) {
            return BridgeBid.PASS;
        }
        Suit longest = suits.getFirst();
        boolean balanced = isBalanced(player);

        if (!auction.hasContract()) {
            return opening(points, longest, balanced);
        }

        boolean partnerBid = Math.floorMod(auction.highestSeat() - seat, 2) == 0;
        int wanted;
        if (partnerBid) {
            wanted = reply(auction, player, seat, points, longest, suits, balanced);
        } else if (auction.level() >= 3 && auction.doubling() == 0 && points >= 10) {
            // Simple competitive bidding: only speak with real values, and double a high contract when
            // it is the opponents' and nothing has doubled it yet.
            wanted = BridgeBid.DOUBLE;
        } else if (points >= 15 || (points >= 9 && length(player, longest) >= 6)) {
            wanted = BridgeBid.code(Math.max(2, auction.level()), BridgeBid.strainOf(longest));
        } else {
            wanted = BridgeBid.PASS;
        }
        // The ceiling. Our own auction is limited by what the opening promise plus this hand can pay for;
        // an auction the opponents opened is only competed at the partscore level, because a simple
        // system has no way to tell a sacrifice from a make and the cheap levels are where the answer
        // usually is. Without the second half, two long suits take turns overbidding each other all the
        // way to a grand slam that neither side can make.
        int cap = ourSideOpened(auction, seat) ? levelCap(points + 12) : 3;
        if (BridgeBid.isContract(wanted) && BridgeBid.level(wanted) > cap) {
            return BridgeBid.PASS;
        }
        return wanted;
    }

    private static int opening(int points, Suit longest, boolean balanced) {
        if (points >= 15 && balanced) {
            return BridgeBid.code(1, BridgeBid.NOTRUMP);
        }
        if (points >= OPENING_MINIMUM) {
            return BridgeBid.code(1, BridgeBid.strainOf(longest));
        }
        if (points >= WEAK_TWO_POINTS && !balanced) {
            return BridgeBid.code(2, BridgeBid.strainOf(longest));
        }
        return BridgeBid.PASS;
    }

    /** What to do when partner owns the current contract. */
    private static int reply(Auction auction, CardPlayer player, int seat, int points, Suit longest,
                             List<Suit> suits, boolean balanced) {
        Suit partnerSuit = BridgeBid.suit(auction.highest());
        int partnerLevel = auction.level();

        if (partnerSuit == null) {
            // Partner's last call was notrump, so this is the notrump ladder: invite below game and stop
            // at it. Climbing one rung every time the auction comes back is the other half of the same
            // treadmill -- and being doubled is not a reason to bid a level higher, it is a reason to
            // believe the opponents.
            if (points >= 12 && partnerLevel < 3) {
                return BridgeBid.code(partnerLevel + 1, BridgeBid.NOTRUMP);
            }
            if (points >= 6 && length(player, longest) >= 5) {
                int strain = BridgeBid.strainOf(longest);
                int call = cheapest(auction, seat, strain);
                if (call != BridgeBid.PASS && BridgeBid.level(call) <= 3
                        && !sideHasStrain(auction, seat, strain)) {
                    return call;
                }
            }
            return BridgeBid.PASS;
        }

        boolean major = partnerSuit == Suits.SPADES || partnerSuit == Suits.HEARTS;
        if (length(player, partnerSuit) >= (major ? 3 : 4)) {
            // 12 is what partner's opening promised. The wanted level goes through place so that a raise
            // to a level the partner has already bid is a pass rather than a nudge: without this, 1H-2H-4H
            // offered "4H" to an auction already at 4H and legalise walked it up to five, then six, then
            // seven, which is why an easy table reached grand slams on twenty points.
            int combined = points + 12;
            int wanted = combined >= GAME_POINTS ? gameLevel(BridgeBid.strainOf(partnerSuit))
                    : combined >= 23 ? 3
                    : combined >= 19 ? 2
                    : 0;
            if (wanted == 0) {
                return BridgeBid.PASS;
            }
            return place(auction, seat, BridgeBid.strainOf(partnerSuit), wanted);
        }

        // No fit: show our own suit when it is worth it, otherwise pass or try notrump.
        Suit own = firstDifferent(suits, partnerSuit);
        if (own != null && points >= 10 && length(player, own) >= 5) {
            // Only while the suit is fresh and still cheap. Both hands naming their own suit take turns
            // being the cheapest level up, and without these two tests that lap round the same two suits
            // is what used to walk an easy table up to a grand slam on twenty points.
            int strain = BridgeBid.strainOf(own);
            int call = cheapest(auction, seat, strain);
            if (call != BridgeBid.PASS && BridgeBid.level(call) <= 3
                    && !sideHasStrain(auction, seat, strain)) {
                return call;
            }
        }
        if (balanced && points >= 13) {
            return BridgeBid.code(Math.max(2, partnerLevel), BridgeBid.NOTRUMP);
        }
        return BridgeBid.PASS;
    }

    // ---------------------------------------------------------------------------------------------
    // NORMAL / HARD: the system
    // ---------------------------------------------------------------------------------------------

    /**
     * One hand, read once.
     *
     * <p>Suits are indexed by strain rather than by {@link Suit} so the system can do its arithmetic in
     * ints, and {@code points} is high card points only: everything distributional is applied later and
     * only once a fit is known, because a singleton is worth nothing in notrump and a trick in a suit.
     */
    private static final class Hand {

        private final int[] length = new int[4];
        private final int points;
        private final int quick;
        private final int aces;
        private final int kings;
        private final int queens;
        private final boolean balanced;

        private Hand(CardPlayer player) {
            int points = 0;
            int aces = 0;
            int kings = 0;
            int queens = 0;
            for (Card card : player.hand()) {
                int strain = BridgeBid.strainOf(card.suit());
                if (strain >= 0 && strain < 4) {
                    length[strain]++;
                }
                switch (card.rank().ordinal()) {
                    case 1 -> {
                        points += 4;
                        aces++;
                    }
                    case 13 -> {
                        points += 3;
                        kings++;
                    }
                    case 12 -> {
                        points += 2;
                        queens++;
                    }
                    case 11 -> points += 1;
                    default -> {
                    }
                }
            }
            this.points = points;
            this.aces = aces;
            this.kings = kings;
            this.queens = queens;
            this.quick = quickTricks(player);
            this.balanced = isBalanced(player);
        }

        /** Non-empty strains, longest first; ties broken by the higher ranked strain. */
        private List<Integer> byLength() {
            Map<Integer, List<Integer>> groups = new TreeMap<>(Comparator.reverseOrder());
            for (int strain = 0; strain < 4; strain++) {
                if (length[strain] > 0) {
                    groups.computeIfAbsent(length[strain], key -> new ArrayList<>()).add(strain);
                }
            }
            List<Integer> ordered = new ArrayList<>();
            for (List<Integer> group : groups.values()) {
                group.sort(Comparator.reverseOrder());
                ordered.addAll(group);
            }
            return ordered;
        }

        private int longest() {
            List<Integer> suits = byLength();
            return suits.isEmpty() ? NO_STRAIN : suits.getFirst();
        }

        /** Aces plus kings: the currency of a slam try. */
        private int controls() {
            return aces * 2 + kings;
        }

        private int shortSuits() {
            int shortSuits = 0;
            for (int strain = 0; strain < 4; strain++) {
                if (length[strain] <= 1) {
                    shortSuits++;
                }
            }
            return shortSuits;
        }

        /**
         * What a fit is worth: ruffs in the side suits, plus the trump past the fourth.
         *
         * <p>Added only once a fit is known, and never counted for the agreed suit itself — a singleton in
         * trumps is not a ruff, it is a missing trump.
         */
        private int fitValue(int trump) {
            if (trump < 0 || trump > 3) {
                return 0;
            }
            int value = Math.max(0, length[trump] - 4);
            for (int strain = 0; strain < 4; strain++) {
                if (strain == trump) {
                    continue;
                }
                value += switch (length[strain]) {
                    case 0 -> 3;
                    case 1 -> 2;
                    case 2 -> 1;
                    default -> 0;
                };
            }
            return value;
        }
    }

    /**
     * What the auction says about the partner.
     *
     * <p>Replayed from the calls rather than remembered, which is what keeps the two hands honest: the
     * range is whatever the partner's bids actually promised, and a hand that has limited itself is marked
     * so nobody counts it as unlimited twice.
     */
    private static final class Reading {

        private boolean ourSide;
        private boolean iOpened;
        private int partnerMin;
        private int partnerMax = 21;
        private int partner = NO_STRAIN;
        private boolean partnerNotrump;
        private int agreed = NO_STRAIN;
        private int opponentsLevel;
        private boolean forcing;
        private boolean partnerOvercalled;
        private boolean partnerRaised;
        private boolean partnerAskedAces;
        /** Aces the partner has just shown, or {@code -1}. */
        private int aces = -1;
        private boolean acesCouldBeFour;
        /** The suit a Blackwood ask was about, which the 5C-5S answer no longer names. */
        private int aceStrain = NO_STRAIN;
        /** 0 none, 1 the partner raised our major with 2NT, 2 the partner answered ours. */
        private int jacoby;
        /** 0 none, 1 the partner asked Stayman, 2 we did. */
        private int stayman;
        /** 0 none, 1 the partner transferred, 2 we did. */
        private int transfer;
        /** The strain a transfer is aimed at, when one is live. */
        private int transferTarget = NO_STRAIN;
        /** Level of the partner's last call, for the notrump ladder. */
        private int partnerLevel;
        private boolean partnerLimited;
        /** Contract calls each hand has made, which is how "the auction has been described" is detected. */
        private int myCalls;
        private int partnerCalls;

        private boolean limited() {
            return partnerLimited;
        }
    }

    /** The next call for the hand: pick a role, then a call. */
    private static int system(Auction auction, CardPlayer player, int seat) {
        Hand hand = new Hand(player);
        if (!auction.hasContract()) {
            return openingBid(hand);
        }
        Reading reading = read(auction, seat);

        int wanted;
        if (verboseDebug) {
            System.out.println("DBG seat " + seat + " partnerAsked=" + reading.partnerAskedAces
                    + " aces=" + reading.aces + " aceStrain=" + reading.aceStrain + " agreed="
                    + reading.agreed + " jacoby=" + reading.jacoby);
        }
        if (reading.partnerAskedAces) {
            wanted = answerBlackwood(auction, seat, hand);
        } else if (reading.aces >= 0) {
            wanted = afterAces(auction, seat, hand, reading);
        } else if (reading.jacoby == 1) {
            wanted = answerJacoby(auction, seat, hand, reading);
        } else if (reading.jacoby == 2) {
            wanted = placeJacoby(auction, seat, hand, reading);
        } else if (reading.stayman == 1) {
            wanted = answerStayman(auction, seat, hand, reading);
        } else if (reading.transfer == 1) {
            wanted = acceptTransfer(auction, seat, hand, reading);
        } else if (!reading.ourSide) {
            wanted = defend(auction, seat, hand, reading);
        } else if (reading.iOpened) {
            wanted = rebid(auction, seat, hand, reading);
        } else {
            wanted = respond(auction, seat, hand, reading);
        }

        // A forcing call may not be passed out. Falling back to the cheapest notrump keeps the auction
        // alive without inventing a suit, and the level arithmetic keeps it as low as the rules allow.
        if (wanted == BridgeBid.PASS && reading.forcing) {
            int notrump = notrumpRebid(auction, seat, 3);
            if (notrump != BridgeBid.PASS) {
                return notrump;
            }
            int cheapestCall = cheapest(auction, seat, hand.longest());
            if (cheapestCall != BridgeBid.PASS && BridgeBid.level(cheapestCall) <= 3) {
                return cheapestCall;
            }
        }
        // The last word: whatever a branch decided, our own auction may not be pushed past what the
        // partnership's known minimum pays for. The ace-ask continuations are exempt, because the level
        // of a Blackwood answer and of the contract that follows it is fixed by the convention rather
        // than chosen by a branch, and the answer itself says nothing about strength.
        if (reading.ourSide && BridgeBid.isContract(wanted)
                && !reading.partnerAskedAces && reading.aces < 0
                && BridgeBid.level(wanted) > levelCap(hand.points + reading.partnerMin)) {
            return BridgeBid.PASS;
        }
        return wanted;
    }

    // ---------------------------------------------------------------------------------------------
    // Opening
    // ---------------------------------------------------------------------------------------------

    /**
     * The opening bid, or a pass.
     *
     * <p>Shape upgrades a point: an eleven count with a six card suit or two shortages opens, which is the
     * difference between a system and a rule about the number twelve. The weak two is checked after the
     * strong options, because a six card suit with fifteen points is a one level opening.
     */
    private static int openingBid(Hand hand) {
        int longest = hand.longest();
        if (longest == NO_STRAIN) {
            return BridgeBid.PASS;
        }
        int longestLength = hand.length[longest];

        if (hand.points >= 22) {
            return BridgeBid.code(2, CLUBS);
        }
        if (hand.balanced) {
            // A five card major opens in the major and rebids notrump, so the 2NT opening denies one.
            boolean fiveCardMajor = hand.length[SPADES] >= 5 || hand.length[HEARTS] >= 5;
            if (hand.points >= 20 && !fiveCardMajor) {
                return BridgeBid.code(2, NOTRUMP);
            }
            if (hand.points >= 15 && hand.points <= 17) {
                return BridgeBid.code(1, NOTRUMP);
            }
        }
        boolean shapely = longestLength >= 6 || hand.shortSuits() >= 2;
        if (longestLength >= 4 && (hand.points >= OPENING_MINIMUM || (hand.points >= 11 && shapely))) {
            return BridgeBid.code(1, openingStrain(hand));
        }
        if (hand.points >= WEAK_TWO_POINTS && hand.points <= 10) {
            if (longestLength == 6) {
                return BridgeBid.code(2, longest);
            }
            if (longestLength >= 7) {
                return BridgeBid.code(3, longest);
            }
        }
        return BridgeBid.PASS;
    }

    /**
     * Which suit to open: a five card major if there is one, otherwise the better minor.
     *
     * <p>Naming a five card major before an equally long minor is the whole of "find the major fit": a one
     * level major leaves room to raise it at the same level, and a major game outscores notrump. With only
     * minors it is the longer one, and with equal length the higher ranking — four four is 1D and three
     * three is 1C, the standard round-off that keeps 1C promising four cards.
     */
    private static int openingStrain(Hand hand) {
        for (int strain = SPADES; strain >= HEARTS; strain--) {
            if (hand.length[strain] >= 5) {
                return strain;
            }
        }
        boolean majorsAtMostFour = hand.longest() <= DIAMONDS;
        for (int strain = SPADES; strain >= HEARTS; strain--) {
            if (majorsAtMostFour && hand.length[strain] == 4) {
                return strain;
            }
        }
        if (hand.length[DIAMONDS] != hand.length[CLUBS]) {
            return hand.length[DIAMONDS] > hand.length[CLUBS] ? DIAMONDS : CLUBS;
        }
        return hand.length[DIAMONDS] >= 4 ? DIAMONDS : CLUBS;
    }

    // ---------------------------------------------------------------------------------------------
    // Responding
    // ---------------------------------------------------------------------------------------------

    /**
     * Our calls while partner is the one who opened.
     *
     * <p>Driven by two numbers: what partner has promised, and what this hand is worth with the fit. The
     * level comes from the bottom of the combined range to bid a game and the top to invite one, so a game
     * is reached when it is there rather than when it might be.
     */
    private static int respond(Auction auction, int seat, Hand hand, Reading reading) {
        if (reading.partnerNotrump && reading.transfer == 2 || reading.stayman == 2) {
            return afterNotrumpConvention(auction, seat, hand, reading);
        }
        if (reading.partnerNotrump) {
            return respondToNotrump(auction, seat, hand, reading);
        }
        int partner = reading.partner;
        if (partner == NO_STRAIN) {
            return cheapest(auction, seat, hand.longest());
        }

        int fit = hand.length[partner];
        boolean major = partner >= HEARTS;
        int needed = major ? 3 : 4;

        // A major fit with 13+ is worth a game force: 2NT says so and lets the opener place its minimum or
        // maximum, which is what makes a slam possible without either side guessing.
        if (major && fit >= 4 && hand.points >= 13 && hand.points <= 19
                && auction.isLegal(BridgeBid.code(2, NOTRUMP), seat)) {
            return BridgeBid.code(2, NOTRUMP);
        }

        if (fit >= needed) {
            int combinedMin = hand.points + hand.fitValue(partner) + reading.partnerMin;
            int combinedMax = Math.min(40, hand.points + hand.fitValue(partner) + reading.partnerMax);
            if (combinedMin >= GAME_POINTS) {
                if (combinedMin >= SLAM_ZONE && hand.controls() >= 3 && blackwoodAvailable(auction, seat, partner)) {
                    return BridgeBid.code(4, NOTRUMP);
                }
                return raiseTo(auction, seat, hand, reading, partner, gameLevel(partner));
            }
            if (reading.partnerRaised && reading.partnerLevel >= 3) {
                // Partner tried for game over our own raise, so this call decides it: accept on the top of
                // the range, sign off on the bottom. Passing is the point of a game try, so a maximum must
                // not be swallowed by the "the level is already taken" sign-off below.
                return combinedMax >= GAME_POINTS
                        ? raiseTo(auction, seat, hand, reading, partner, gameLevel(partner))
                        : raiseTo(auction, seat, hand, reading, partner, 2);
            }
            if (reading.myCalls >= 1) {
                // We have raised once already and nothing has changed: only compete if the level is still
                // below game, otherwise let the opponents take the last guess.
                return raiseTo(auction, seat, hand, reading, partner, 3);
            }
            if (combinedMax >= GAME_POINTS && hand.points >= 10) {
                // A limit raise: opener accepts on a maximum and passes otherwise.
                return raiseTo(auction, seat, hand, reading, partner, 3);
            }
            return raiseTo(auction, seat, hand, reading, partner, 2);
        }

        // No fit: show a suit when there is one, otherwise describe the strength in notrump.
        if (nothingNew(reading)) {
            return closeOut(auction, seat, hand, reading);
        }
        int best = hand.longest();
        int bestLength = best == NO_STRAIN ? 0 : hand.length[best];
        if (bestLength >= 5 || hand.points >= 16) {
            // Naming a fresh suit is worth a level or two. Repeating one our own side has already named,
            // or pushing a new one past the three level without a fit, is the treadmill: two hands take
            // turns being the cheapest level up and neither ever has a reason to stop.
            int call = cheapest(auction, seat, best);
            if (call != BridgeBid.PASS && BridgeBid.level(call) <= 3
                    && !sideHasStrain(auction, seat, best)) {
                return call;
            }
        }
        int notrump = notrumpRebid(auction, seat, hand.points >= 13 ? 3 : hand.points >= 11 ? 2 : 1);
        return notrump != BridgeBid.PASS ? notrump : BridgeBid.PASS;
    }

    /**
     * Answering a notrump opening: Stayman and transfers, then a call off the combined range.
     *
     * <p>Two conventions is the least that makes a strong notrump playable — without them a five card major
     * in the responding hand is either lost or guessed at.
     */
    private static int respondToNotrump(Auction auction, int seat, Hand hand, Reading reading) {
        // A 20-21 notrump: conventions move up a level, and a game needs almost nothing.
        if (reading.partnerMax >= 20) {
            boolean fourCardMajor = hand.length[HEARTS] >= 4 || hand.length[SPADES] >= 4;
            if (hand.length[SPADES] >= 5 && auction.isLegal(BridgeBid.code(3, HEARTS), seat)) {
                return BridgeBid.code(3, HEARTS);
            }
            if (hand.length[HEARTS] >= 5 && auction.isLegal(BridgeBid.code(3, DIAMONDS), seat)) {
                return BridgeBid.code(3, DIAMONDS);
            }
            if (hand.points >= 8 && fourCardMajor && auction.isLegal(BridgeBid.code(3, CLUBS), seat)) {
                return BridgeBid.code(3, CLUBS);
            }
            return hand.points >= 5 ? place(auction, seat, NOTRUMP, 3) : BridgeBid.PASS;
        }
        if (hand.length[SPADES] >= 5 && auction.isLegal(BridgeBid.code(2, HEARTS), seat)) {
            return BridgeBid.code(2, HEARTS);
        }
        if (hand.length[HEARTS] >= 5 && auction.isLegal(BridgeBid.code(2, DIAMONDS), seat)) {
            return BridgeBid.code(2, DIAMONDS);
        }
        boolean fourCardMajor = hand.length[HEARTS] >= 4 || hand.length[SPADES] >= 4;
        if (hand.points >= 8 && fourCardMajor && auction.isLegal(BridgeBid.code(2, CLUBS), seat)) {
            return BridgeBid.code(2, CLUBS);
        }
        if (hand.points >= 10) {
            return place(auction, seat, NOTRUMP, 3);
        }
        if (hand.points >= 8) {
            return place(auction, seat, NOTRUMP, 2);
        }
        return BridgeBid.PASS;
    }

    /** After Stayman or a transfer has been answered, the responder knows the fit and places the contract. */
    private static int afterNotrumpConvention(Auction auction, int seat, Hand hand, Reading reading) {
        int strain = reading.partner;
        int fit = strain != NO_STRAIN ? hand.length[strain] : 0;
        int combined = hand.points + reading.partnerMin;
        if (strain >= HEARTS && fit >= 3) {
            if (combined >= SLAM_ZONE && hand.controls() >= 3 && blackwoodAvailable(auction, seat, strain)) {
                return BridgeBid.code(4, NOTRUMP);
            }
            if (combined >= GAME_POINTS) {
                return place(auction, seat, strain, 4);
            }
            return place(auction, seat, strain, 3);
        }
        if (combined >= GAME_POINTS) {
            return place(auction, seat, NOTRUMP, 3);
        }
        if (combined >= 23) {
            return place(auction, seat, NOTRUMP, 2);
        }
        return BridgeBid.PASS;
    }

    /**
     * A raise in an agreed or implied strain, capped at game -- and one level past it only on a strong range.
     *
     * <p>Competition is what makes the cap necessary. If the opponents bid over our raise, {@link #place}
     * would answer "three diamonds" with the cheapest legal diamond, which after their four spades is five,
     * and after their five spades is six: the two sides walk each other up to a grand slam on twenty-three
     * points. Above the cap, the contract already on the table is worth more than one more description.
     */
    private static int raiseTo(Auction auction, int seat, Hand hand, Reading reading, int strain, int wanted) {
        int combinedMin = hand.points + hand.fitValue(strain) + reading.partnerMin;
        int ceiling = Math.max(wanted, combinedMin >= SLAM_ZONE ? gameLevel(strain) + 1 : gameLevel(strain));
        if (auction.hasContract() && auction.level() >= ceiling) {
            return BridgeBid.PASS;
        }
        return place(auction, seat, strain, wanted);
    }

    /**
     * True when both hands have described themselves and no fit has come out of it.
     *
     * <p>Two hands naming two different suits take turns being the cheapest level up, so without this the
     * auction is a treadmill: hearts at three, diamonds at four, hearts at four, diamonds at five. Once
     * each hand has bid twice and neither has supported the other, another lap round the same two suits
     * costs more than it tells the partner, and the auction should stop -- or go to game when the two
     * ranges actually pay for one.
     */
    private static boolean nothingNew(Reading reading) {
        return reading.agreed == NO_STRAIN && reading.myCalls >= 1 && reading.partnerCalls >= 1
                && reading.myCalls + reading.partnerCalls >= 3;
    }

    /** The call once there is nothing new to say: a pass, or game when the combined ranges say so. */
    private static int closeOut(Auction auction, int seat, Hand hand, Reading reading) {
        int combinedMin = hand.points + hand.fitValue(reading.partner) + reading.partnerMin;
        if (combinedMin < GAME_POINTS) {
            return BridgeBid.PASS;
        }
        // The ceiling is the cheapest game, and one level past it only when the bottom of the combined
        // range reaches the slam zone. Being at or above the ceiling already is what ends a competitive
        // auction instead of letting each side push the other up one more level for nothing.
        int longest = hand.longest();
        int game = longest == NO_STRAIN ? 3 : gameLevel(longest);
        int ceiling = combinedMin >= SLAM_ZONE ? game + 1 : game;
        if (auction.hasContract() && auction.level() >= ceiling) {
            return BridgeBid.PASS;
        }
        int notrump = notrumpRebid(auction, seat, 3);
        if (notrump != BridgeBid.PASS && BridgeBid.level(notrump) <= ceiling) {
            return notrump;
        }
        return place(auction, seat, longest, ceiling);
    }

    // ---------------------------------------------------------------------------------------------
    // Opener's rebid
    // ---------------------------------------------------------------------------------------------

    /**
     * Our calls after our own opening.
     *
     * <p>Where the captaincy pays off: the opener knows exactly what the partner promised, so the combined
     * range is a range and the decision is a comparison. A minimum signs off, a maximum bids game, and a
     * fit plus a strong range asks for aces instead of guessing at six.
     */
    private static int rebid(Auction auction, int seat, Hand hand, Reading reading) {
        if (reading.agreed != NO_STRAIN) {
            int agreed = reading.agreed;
            int combinedMin = hand.points + hand.fitValue(agreed) + reading.partnerMin;
            if (combinedMin >= SLAM_ZONE && hand.controls() >= 3 && blackwoodAvailable(auction, seat, agreed)) {
                return BridgeBid.code(4, NOTRUMP);
            }
            if (combinedMin >= SMALL_SLAM_POINTS) {
                return raiseTo(auction, seat, hand, reading, agreed, 6);
            }
            if (combinedMin >= GAME_POINTS) {
                return raiseTo(auction, seat, hand, reading, agreed, gameLevel(agreed));
            }
            if (reading.partnerRaised && reading.partnerMin <= 9 && hand.points >= 15) {
                // A single raise opposite a maximum opening: try the level below game so the responder can
                // accept on the top of its range instead of the game being blasted on a good five points.
                int invite = gameLevel(agreed) - 1;
                if (invite > 2) {
                    return raiseTo(auction, seat, hand, reading, agreed, invite);
                }
            }
            return raiseTo(auction, seat, hand, reading, agreed, 2);
        }

        // Partner described a balanced hand: the notrump ladder is a sign-off or an invitation, and the
        // only reason to bid on is a maximum.
        if (reading.partnerNotrump) {
            if (reading.partnerLevel >= 3 && hand.points <= 17) {
                return BridgeBid.PASS;
            }
            if (reading.partnerLevel == 2 && hand.points >= 17) {
                return place(auction, seat, NOTRUMP, 3);
            }
            if (reading.partnerLevel <= 1 && hand.points >= 15) {
                return place(auction, seat, NOTRUMP, 2);
            }
            return BridgeBid.PASS;
        }

        // Partner named a suit: raise it with real support, otherwise rebid the long suit or show the
        // strength in notrump, and a minimum never invents a new suit at the three level.
        if (nothingNew(reading)) {
            return closeOut(auction, seat, hand, reading);
        }
        int partner = reading.partner;
        if (partner != NO_STRAIN) {
            int fit = hand.length[partner];
            int needed = partner >= HEARTS ? 4 : 5;
            if (fit >= needed) {
                int combinedMin = hand.points + hand.fitValue(partner) + reading.partnerMin;
                if (combinedMin >= GAME_POINTS) {
                    return raiseTo(auction, seat, hand, reading, partner, gameLevel(partner));
                }
                if (hand.points >= 16) {
                    return raiseTo(auction, seat, hand, reading, partner, 3);
                }
                int fitRaise = raiseTo(auction, seat, hand, reading, partner, 2);
                if (fitRaise != BridgeBid.PASS) {
                    return fitRaise;
                }
            }
        }
        int longest = hand.longest();
        if (hand.points <= 14) {
            if (longest != NO_STRAIN && hand.length[longest] >= 6) {
                return place(auction, seat, longest, 2);
            }
            int notrump = notrumpRebid(auction, seat, 1);
            if (notrump != BridgeBid.PASS) {
                return notrump;
            }
            return place(auction, seat, longest, 2);
        }
        int notrump = notrumpRebid(auction, seat, 2);
        if (notrump != BridgeBid.PASS) {
            return notrump;
        }
        if (!reading.forcing) {
            return BridgeBid.PASS;
        }
        int strong = notrumpRebid(auction, seat, 3);
        return strong != BridgeBid.PASS ? strong : place(auction, seat, longest, 3);
    }

    // ---------------------------------------------------------------------------------------------
    // Defence
    // ---------------------------------------------------------------------------------------------

    /**
     * Calls when the opponents opened.
     *
     * <p>No sacrifices and no thin overcalls: an overcall at the one level promises 8-16 with a five card
     * suit, a takeout double the unbid suits with an opening hand, and raising partner's overcall is by
     * range like any other raise. When there is nothing to say, passing is a call.
     */
    private static int defend(Auction auction, int seat, Hand hand, Reading reading) {
        int partner = reading.partner;

        if (reading.partnerOvercalled && partner != NO_STRAIN) {
            int fit = hand.length[partner];
            if (fit >= 3) {
                int combinedMin = hand.points + hand.fitValue(partner) + reading.partnerMin;
                if (combinedMin >= GAME_POINTS) {
                    return raiseTo(auction, seat, hand, reading, partner, gameLevel(partner));
                }
                // Already raised once, and the opponents have to do the bidding: they will, so the extra
                // raise only repeats what the first one said. Competition is where this matters most,
                // because their next bid pushes the level before ours is even heard.
                if (reading.myCalls >= 1) {
                    return raiseTo(auction, seat, hand, reading, partner, 3);
                }
                return raiseTo(auction, seat, hand, reading, partner, hand.points >= 11 ? 3 : 2);
            }
            if (hand.points >= 12 && hand.balanced) {
                return place(auction, seat, NOTRUMP, 2);
            }
            int own = hand.longest();
            if (partner != NO_STRAIN && reading.partnerMax <= 17 && hand.balanced) {
                return BridgeBid.PASS;
            }
            if (hand.points >= 10 && own != NO_STRAIN && own != partner && hand.length[own] >= 5) {
                // Same discipline as the uncontested auction: a fresh suit at a level this hand can pay
                // for. Repeating a suit our own side has already named is how two defenders took turns
                // being one level higher until the auction reached seven.
                int call = cheapest(auction, seat, own);
                if (call != BridgeBid.PASS && BridgeBid.level(call) <= 3
                        && !sideHasStrain(auction, seat, own)) {
                    return call;
                }
            }
            return BridgeBid.PASS;
        }

        int longest = hand.longest();
        int longestLength = longest == NO_STRAIN ? 0 : hand.length[longest];
        int level = auction.level();

        if (level <= 1) {
            // A five card major is overcalled before anything else: doubling with one buries the suit.
            if (hand.points >= 8 && longestLength >= 5 && longest >= HEARTS) {
                return cheapest(auction, seat, longest);
            }
            if (hand.points >= 12 && hand.points <= 16 && takeoutShape(hand, reading)) {
                return BridgeBid.DOUBLE;
            }
            if (hand.points >= 15 && hand.points <= 18 && hand.balanced && hand.controls() >= 3) {
                int notrump = at(auction, seat, 1, NOTRUMP);
                if (notrump >= 0) {
                    return notrump;
                }
            }
            if (hand.points >= 8 && longestLength >= 5) {
                return cheapest(auction, seat, longest);
            }
            return BridgeBid.PASS;
        }
        if (level == 2) {
            if (hand.points >= 11 && longestLength >= 5) {
                return cheapest(auction, seat, longest);
            }
            if (hand.points >= 13 && hand.points <= 16 && takeoutShape(hand, reading)) {
                return BridgeBid.DOUBLE;
            }
            if (hand.points >= 15 && hand.balanced) {
                return place(auction, seat, NOTRUMP, 2);
            }
            return BridgeBid.PASS;
        }
        // Three and above: only a real suit or a penalty double.
        if (auction.doubling() == 0 && hand.points >= 9 && hand.quick >= 2) {
            return BridgeBid.DOUBLE;
        }
        if (hand.points >= 12 && longestLength >= 6) {
            return cheapest(auction, seat, longest);
        }
        return BridgeBid.PASS;
    }

    /** Two of the three unbid suits, which is what makes a double a takeout rather than a penalty. */
    private static boolean takeoutShape(Hand hand, Reading reading) {
        int covered = 0;
        for (int strain = 0; strain < 4; strain++) {
            if (strain != reading.partner && hand.length[strain] >= 3) {
                covered++;
            }
        }
        return covered >= 3;
    }

    // ---------------------------------------------------------------------------------------------
    // Conventions
    // ---------------------------------------------------------------------------------------------

    /** 2NT raise of partner's major: a minimum, a balanced maximum, or a strong hand with the fit. */
    private static int answerJacoby(Auction auction, int seat, Hand hand, Reading reading) {
        int major = reading.agreed != NO_STRAIN ? reading.agreed : HEARTS;
        if (hand.points >= 18) {
            return place(auction, seat, major, 4);
        }
        if (hand.points >= 15 && hand.balanced) {
            int notrump = place(auction, seat, NOTRUMP, 3);
            if (notrump != BridgeBid.PASS) {
                return notrump;
            }
        }
        return place(auction, seat, major, 3);
    }

    /** The 2NT raiser's second call: a minimum is passed into game, a maximum is asked about. */
    private static int placeJacoby(Auction auction, int seat, Hand hand, Reading reading) {
        int major = reading.agreed != NO_STRAIN ? reading.agreed : HEARTS;
        boolean asked = false;
        if (reading.partnerMin >= 15 && hand.points >= 15 && hand.controls() >= 3
                && blackwoodAvailable(auction, seat, major)) {
            asked = true;
        }
        if (asked) {
            return BridgeBid.code(4, NOTRUMP);
        }
        if (reading.partnerMin >= 18 && hand.points >= 17 && hand.controls() >= 4
                && blackwoodAvailable(auction, seat, major)) {
            return BridgeBid.code(4, NOTRUMP);
        }
        return place(auction, seat, major, 4);
    }

    /** Stayman: the next strain down denies a four card major, otherwise the lowest one is named. */
    private static int answerStayman(Auction auction, int seat, Hand hand, Reading reading) {
        // The answer sits on the convention's own level, so 2NT-3C is answered in the threes as well.
        int level = Math.max(2, reading.partnerLevel);
        int denial = at(auction, seat, level, DIAMONDS);
        if (hand.length[HEARTS] >= 4) {
            int hearts = at(auction, seat, level, HEARTS);
            if (hearts >= 0) {
                return hearts;
            }
        }
        if (hand.length[SPADES] >= 4) {
            int spades = at(auction, seat, level, SPADES);
            if (spades >= 0) {
                return spades;
            }
        }
        return denial;
    }

    /** Completes a transfer on the convention's own level, going one higher with four card support and a maximum. */
    private static int acceptTransfer(Auction auction, int seat, Hand hand, Reading reading) {
        int target = reading.transferTarget;
        if (target == NO_STRAIN) {
            return BridgeBid.PASS;
        }
        int level = Math.max(2, reading.partnerLevel);
        if (hand.length[target] >= 4 && hand.points >= 17) {
            int raise = at(auction, seat, level + 1, target);
            if (raise >= 0) {
                return raise;
            }
        }
        return at(auction, seat, level, target);
    }

    // ---------------------------------------------------------------------------------------------
    // Blackwood
    // ---------------------------------------------------------------------------------------------

    /** 4NT has to be legal and we have to have a suit to come back to. */
    private static boolean blackwoodAvailable(Auction auction, int seat, int agreed) {
        return agreed != NO_STRAIN && agreed != NOTRUMP && auction.isLegal(BridgeBid.code(4, NOTRUMP), seat);
    }

    /** Answering 4NT: 5C none or four, 5D one, 5H two, 5S three. */
    private static int answerBlackwood(Auction auction, int seat, Hand hand) {
        int call = switch (hand.aces) {
            case 0, 4 -> BridgeBid.code(5, CLUBS);
            case 1 -> BridgeBid.code(5, DIAMONDS);
            case 2 -> BridgeBid.code(5, HEARTS);
            default -> BridgeBid.code(5, SPADES);
        };
        int legal = at(auction, seat, BridgeBid.level(call), BridgeBid.strain(call));
        return legal >= 0 ? legal : BridgeBid.PASS;
    }

    /** The asker's second call: five, six or seven of the agreed strain, from the ace count. */
    private static int afterAces(Auction auction, int seat, Hand hand, Reading reading) {
        // The answer no longer names the trump, so the strain comes from when the ask was made.
        int agreed = reading.aceStrain != NO_STRAIN ? reading.aceStrain : reading.agreed;
        if (agreed == NO_STRAIN) {
            agreed = NOTRUMP;
        }
        int total = hand.aces + reading.aces;
        if (reading.acesCouldBeFour && hand.aces == 0) {
            // 5C answers "none or four"; with no ace in this hand it has to be four.
            total = 4;
        }
        if (total >= 4 && hand.points + reading.partnerMin >= GRAND_ZONE) {
            int seven = at(auction, seat, 7, agreed);
            if (seven >= 0) {
                return seven;
            }
        }
        if (total >= 3) {
            int six = at(auction, seat, 6, agreed);
            if (six >= 0) {
                return six;
            }
        }
        int five = at(auction, seat, 5, agreed);
        if (five >= 0) {
            return five;
        }
        return place(auction, seat, agreed, gameLevel(agreed));
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the auction back
    // ---------------------------------------------------------------------------------------------

    /**
     * Replays the auction and works out what the partner has promised.
     *
     * <p>Every range is the promise the system itself makes for that call, so the two halves of a
     * partnership cannot disagree about what a bid meant. The partner's <em>last</em> call is the live one:
     * an earlier bid is superseded, except when the last call is a convention answer, which says nothing
     * about strength and so leaves the previous promise standing.
     */
    private static Reading read(Auction auction, int seat) {
        Reading reading = new Reading();
        int partner = Math.floorMod(seat + 2, 4);
        int mySide = Math.floorMod(seat, 2);

        int firstContractSeat = -1;
        int partnerFirst = NO_STRAIN;
        int partnerFirstLevel = 0;
        int myFirst = NO_STRAIN;
        int myLast = NO_STRAIN;
        int myLastLevel = 0;
        int partnerLast = NO_STRAIN;
        int partnerLastLevel = 0;
        int myContracts = 0;
        int partnerContracts = 0;
        int opponentLevel = 0;
        boolean opponentBidFirst = false;
        boolean opponentBidBeforePartner = false;
        boolean partnerOvercalled = false;
        boolean partnerRaised = false;
        int agreed = NO_STRAIN;
        int jacoby = 0;
        int stayman = 0;
        int transfer = 0;
        int transferTarget = NO_STRAIN;
        int aces = -1;
        boolean acesCouldBeFour = false;
        boolean partnerAskedAces = false;
        boolean askedAces = false;
        boolean conventionAnswer = false;
        int askedStrain = NO_STRAIN;
        int aceStrain = NO_STRAIN;
        int prevMin = 0;
        int prevMax = 21;
        int partnerMin = 0;
        int partnerMax = 21;
        boolean partnerLimited = false;

        for (int i = 0; i < auction.size(); i++) {
            int from = auction.seatOf(i);
            int call = auction.code(i);
            boolean mine = from == seat;
            boolean partners = from == partner;
            if (call == BridgeBid.PASS) {
                continue;
            }
            if (call == BridgeBid.DOUBLE || call == BridgeBid.REDOUBLE) {
                continue;
            }
            int strain = BridgeBid.strain(call);
            int level = BridgeBid.level(call);
            if (firstContractSeat < 0) {
                firstContractSeat = from;
                opponentBidFirst = !mine && !partners;
            }
            if (!mine && !partners) {
                opponentLevel = Math.max(opponentLevel, level);
                if (partnerFirst == NO_STRAIN) {
                    opponentBidBeforePartner = true;
                }
                continue;
            }

            // A raise is a call in the strain the *other* hand named last. Testing against our own last
            // call alone would make every plain rebid of our own suit look like a raise, which is what let
            // an opener count a partner's brand new suit as support and jump to game on nothing.
            if (strain != NOTRUMP && partners && myLast == strain) {
                agreed = strain;
                partnerRaised = true;
            } else if (strain != NOTRUMP && mine && partnerLast == strain) {
                agreed = strain;
            }
            if (mine) {
                myContracts++;
                if (myFirst == NO_STRAIN) {
                    myFirst = strain;
                }
                if (level == 4 && strain == NOTRUMP) {
                    // Blackwood only when there is a suit to come back to: the agreed one, or whatever
                    // partner named last. Without this the asker's own 4NT is answered in notrump.
                    int fit = agreed != NO_STRAIN ? agreed : partnerLast;
                    if (fit != NO_STRAIN && fit != NOTRUMP) {
                        askedAces = true;
                        askedStrain = fit;
                    }
                }
                myLast = strain;
                myLastLevel = level;
                continue;
            }
            partnerContracts++;
            if (partnerFirst == NO_STRAIN) {
                partnerFirst = strain;
                partnerFirstLevel = level;
                partnerOvercalled = opponentBidBeforePartner;
            }
            if (askedAces && level == 5) {
                // The answer to *our* 4NT, so it is the partner's 5C-5S that has to be decoded here. Reading
                // it in our own branch instead meant the asker never saw an answer and passed one level
                // short of every slam it had just asked about.
                aces = switch (strain) {
                    case CLUBS -> 0;
                    case DIAMONDS -> 1;
                    case HEARTS -> 2;
                    default -> 3;
                };
                acesCouldBeFour = strain == CLUBS;
                askedAces = false;
                conventionAnswer = true;
                aceStrain = askedStrain;
            }
            if (level == 4 && strain == NOTRUMP) {
                int fit = agreed != NO_STRAIN ? agreed : myLast;
                if (fit != NO_STRAIN && fit != NOTRUMP) {
                    partnerAskedAces = true;
                }
            }
            prevMin = partnerMin;
            prevMax = partnerMax;
            partnerLast = strain;
            partnerLastLevel = level;
        }

        // Conventions, read off the shape of the auction rather than remembered in a field.
        if (partnerLast == NOTRUMP && partnerLastLevel == 2 && partnerContracts == 1
                && myContracts == 1 && myLastLevel == 1 && myLast >= HEARTS) {
            // We opened one of a major, partner raised with 2NT.
            jacoby = 1;
            agreed = myLast;
        }
        if (myLast == NOTRUMP && myLastLevel == 2 && myContracts == 1
                && partnerFirst >= HEARTS && partnerFirstLevel == 1 && partnerContracts >= 2) {
            // We raised partner's major with 2NT and they have answered. My 2NT was my only contract call,
            // so the answer is the partner's latest -- which is exactly what the count is checking.
            jacoby = 2;
            agreed = partnerFirst;
        }
        if (myLast == NOTRUMP && (myLastLevel == 1 || myLastLevel == 2)
                && partnerContracts == 1 && partnerLastLevel == myLastLevel + 1) {
            // We opened one or two notrump and partner has used a convention on the next level up.
            if (partnerLast == CLUBS) {
                stayman = 1;
            } else if (partnerLast == DIAMONDS || partnerLast == HEARTS) {
                transfer = 1;
                transferTarget = partnerLast == DIAMONDS ? HEARTS : SPADES;
            }
        }
        if (partnerFirst == NOTRUMP && (partnerFirstLevel == 1 || partnerFirstLevel == 2)
                && myContracts == 1 && myLastLevel == partnerFirstLevel + 1) {
            if (myLast == CLUBS) {
                stayman = 2;
            } else if (myLast == DIAMONDS || myLast == HEARTS) {
                transfer = 2;
            }
        }

        if (partnerContracts > 0) {
            int[] range = promise(partnerFirst, partnerFirstLevel, firstContractSeat == partner,
                    partnerLast, partnerLastLevel, partnerContracts, partnerOvercalled, partnerRaised);
            partnerMin = range[0];
            partnerMax = range[1];
            partnerLimited = range[1] < 21;
            if (conventionAnswer) {
                // A Blackwood answer describes aces, not points: keep the promise it was asked off.
                partnerMin = prevMin;
                partnerMax = prevMax;
                partnerLimited = prevMax < 21;
            }
            if (jacoby == 2) {
                // The Jacoby answer does say something: 3M is a minimum, 3NT a balanced maximum, 4M a
                // strong hand. It is the one convention answer worth trusting.
                partnerMin = switch (partnerLastLevel) {
                    case 3 -> partnerLast == NOTRUMP ? 15 : 12;
                    default -> 18;
                };
                partnerMax = switch (partnerLastLevel) {
                    case 3 -> partnerLast == NOTRUMP ? 17 : 14;
                    default -> 21;
                };
                partnerLimited = partnerLastLevel == 3;
            }
        }

        boolean newSuit = partnerLast != NO_STRAIN && partnerLast != NOTRUMP && partnerLast != agreed
                && !partnerRaised;
        boolean partnerResponding = partnerContracts == 1 && firstContractSeat == seat;
        reading.forcing = partnerAskedAces
                || jacoby == 1 || stayman == 1 || transfer == 1
                || (newSuit && partnerResponding && !partnerOvercalled && partnerLastLevel <= 2);
        reading.ourSide = firstContractSeat >= 0 && Math.floorMod(firstContractSeat, 2) == mySide;
        reading.iOpened = reading.ourSide && firstContractSeat == seat;
        reading.partner = partnerLast == NOTRUMP ? NO_STRAIN : partnerLast;
        // Only the partner's *last* call being notrump puts us in a notrump conversation. Testing "they
        // ever opened notrump" instead keeps a 1NT opener's later suit answer from being re-answered as
        // though the auction were still about notrump.
        reading.partnerNotrump = partnerLast == NOTRUMP;
        reading.partnerMin = partnerMin;
        reading.partnerMax = partnerMax;
        reading.partnerLimited = partnerLimited;
        reading.agreed = agreed;
        reading.opponentsLevel = opponentLevel;
        reading.partnerOvercalled = partnerOvercalled;
        reading.partnerRaised = partnerRaised;
        reading.partnerAskedAces = partnerAskedAces;
        reading.aces = aces;
        reading.acesCouldBeFour = acesCouldBeFour;
        reading.aceStrain = aceStrain;
        reading.jacoby = jacoby;
        reading.stayman = stayman;
        reading.transfer = transfer;
        reading.transferTarget = transferTarget;
        reading.partnerLevel = partnerLastLevel;
        reading.myCalls = myContracts;
        reading.partnerCalls = partnerContracts;
        return reading;
    }

    /**
     * The range a call promises.
     *
     * <p>One table, one place, which is the whole reason two bots can agree on what a bid meant. Entries
     * are what the system promises when it makes that call; anything unrecognised falls back to "a good
     * hand, unlimited", which is the safe direction for a slam try and the wrong one for a blast.
     *
     * @return {@code {min, max}}
     */
    private static int[] promise(int first, int firstLevel, boolean opened, int last, int lastLevel,
                                 int calls, boolean overcalled, boolean raised) {
        // A notrump opening stays limited through every later call: Stayman answers and transfer
        // completions say nothing about strength.
        if (opened && first == NOTRUMP) {
            return firstLevel <= 1 ? new int[]{15, 17} : new int[]{20, 21};
        }
        if (opened && first == CLUBS && firstLevel == 2) {
            return new int[]{22, 40};
        }
        if (raised) {
            if (overcalled) {
                return lastLevel <= 3 ? new int[]{8, 11} : new int[]{12, 15};
            }
            if (!opened) {
                return switch (lastLevel) {
                    case 2 -> new int[]{6, 9};
                    case 3 -> new int[]{10, 12};
                    default -> new int[]{13, 17};
                };
            }
            return switch (lastLevel) {
                case 2 -> new int[]{12, 14};
                case 3 -> new int[]{15, 16};
                case 4, 5 -> new int[]{12, 16};
                default -> new int[]{17, 21};
            };
        }
        if (calls == 1 && !overcalled) {
            if (opened) {
                if (last == NOTRUMP) {
                    return new int[]{15, 17};
                }
                if (lastLevel == 1) {
                    return new int[]{11, 21};
                }
                if (lastLevel == 2) {
                    return new int[]{5, 10};
                }
                return new int[]{5, 9};
            }
            // A response, first and only call so far.
            if (last == NOTRUMP) {
                return switch (lastLevel) {
                    case 1 -> new int[]{6, 10};
                    case 2 -> new int[]{11, 12};
                    default -> new int[]{13, 15};
                };
            }
            return lastLevel <= 1 ? new int[]{6, 40} : new int[]{10, 40};
        }
        if (overcalled) {
            return lastLevel <= 1 ? new int[]{8, 16} : new int[]{11, 16};
        }
        // A later descriptive call by a hand that has already bid.
        if (last == NOTRUMP) {
            return lastLevel <= 2 ? new int[]{15, 17} : new int[]{18, 19};
        }
        if (opened) {
            return switch (lastLevel) {
                case 2 -> new int[]{12, 15};
                case 3 -> new int[]{13, 17};
                default -> new int[]{18, 21};
            };
        }
        return switch (lastLevel) {
            case 2 -> new int[]{10, 14};
            case 3 -> new int[]{11, 16};
            default -> new int[]{13, 21};
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Level arithmetic
    // ---------------------------------------------------------------------------------------------

    /**
     * The call at exactly {@code level} in {@code strain}, or a pass.
     *
     * <p>Returns a pass when our own side already has that strain at that level or higher: a call in the
     * agreed suit means that level and nothing else, so "four hearts" offered to an auction already at
     * four hearts is a sign-off rather than a nudge to five. Handing it to {@link #legalise} instead is
     * what used to walk a quiet auction up into a grand slam. When the <em>opponents</em> hold the strain,
     * a pass would leave us defending it, so one level higher is bid instead.
     */
    private static int place(Auction auction, int seat, int strain, int level) {
        if (strain == NO_STRAIN) {
            return BridgeBid.PASS;
        }
        int clamped = Math.min(BridgeBid.MAX_LEVEL, Math.max(1, level));
        int call = BridgeBid.code(clamped, strain);
        if (auction.isLegal(call, seat)) {
            return call;
        }
        if (agreedHeldBy(auction, seat, strain)) {
            return BridgeBid.PASS;
        }
        return cheapest(auction, seat, strain);
    }

    /** True when our own side has already named {@code strain} anywhere in this auction. */
    private static boolean sideHasStrain(Auction auction, int seat, int strain) {
        int side = Math.floorMod(seat, 2);
        for (int i = 0; i < auction.size(); i++) {
            int call = auction.code(i);
            if (BridgeBid.isContract(call) && BridgeBid.strain(call) == strain
                    && Math.floorMod(auction.seatOf(i), 2) == side) {
                return true;
            }
        }
        return false;
    }

    /**
     * The highest level a partnership whose <em>known minimum</em> is {@code combinedMin} may bid.
     *
     * <p>Every branch of the system decides its own level, and the failure this class was rewritten
     * around was the branches disagreeing: each one bid "one more" and the total ran to seven on a
     * twenty point deal. This is the one place that says what the partnership can afford, so a call
     * that no branch should have produced cannot escape onto the table. Below an opening's worth of
     * points the partnership is only competing at the two and three level; game needs the middle of
     * the twenties, a small slam the top, and a grand slam the whole thirty-three.
     */
    private static int levelCap(int combinedMin) {
        if (combinedMin >= GRAND_ZONE) {
            return 7;
        }
        if (combinedMin >= 27) {
            return 6;
        }
        if (combinedMin >= GAME_POINTS) {
            return 5;
        }
        if (combinedMin >= 23) {
            return 4;
        }
        if (combinedMin >= 20) {
            return 3;
        }
        return 2;
    }

    /**
     * True when our side made the first contract call, so the auction is ours to push or to stop.
     *
     * <p>The ceiling is about not walking our own auction up the levels. In competition the opponents
     * set the level, and a raise of partner's overcall is a legitimate three level call on eight points,
     * so the ceiling has no business there.
     */
    private static boolean ourSideOpened(Auction auction, int seat) {
        int side = Math.floorMod(seat, 2);
        for (int i = 0; i < auction.size(); i++) {
            if (BridgeBid.isContract(auction.code(i))) {
                return Math.floorMod(auction.seatOf(i), 2) == side;
            }
        }
        return false;
    }

    /** True when our own side already owns {@code strain}; the opponents holding it is the other case. */
    private static boolean agreedHeldBy(Auction auction, int seat, int strain) {
        if (!auction.hasContract() || BridgeBid.strain(auction.highest()) != strain) {
            return false;
        }
        return Math.floorMod(auction.highestSeat(), 2) == Math.floorMod(seat, 2);
    }

    /** The cheapest legal level in {@code strain}, or a pass when even the seven level is too low. */
    private static int cheapest(Auction auction, int seat, int strain) {
        if (strain == NO_STRAIN) {
            return BridgeBid.PASS;
        }
        for (int level = 1; level <= BridgeBid.MAX_LEVEL; level++) {
            int call = BridgeBid.code(level, strain);
            if (auction.isLegal(call, seat)) {
                return call;
            }
        }
        return BridgeBid.PASS;
    }

    /** The call at exactly this level and strain, or {@code -1}. */
    private static int at(Auction auction, int seat, int level, int strain) {
        int call = BridgeBid.code(level, strain);
        return auction.isLegal(call, seat) ? call : -1;
    }

    /**
     * The cheapest legal notrump up to {@code maxLevel}, or a pass.
     *
     * <p>Capping the level is what stops {@link #place} from answering "2NT" with 5NT: once the auction is
     * that high, a house minimum belongs in the suit it already has rather than in a notrump ladder nobody
     * is climbing. Runaway level escalation is the failure mode this whole class was rewritten around.
     */
    private static int notrumpRebid(Auction auction, int seat, int maxLevel) {
        for (int level = 1; level <= maxLevel; level++) {
            int call = BridgeBid.code(level, NOTRUMP);
            if (auction.isLegal(call, seat)) {
                return call;
            }
        }
        return BridgeBid.PASS;
    }

    /** The cheapest game in a strain: four of a major, five of a minor, three notrump. */
    private static int gameLevel(int strain) {
        return switch (strain) {
            case HEARTS, SPADES -> 4;
            case CLUBS, DIAMONDS -> 5;
            default -> 3;
        };
    }

    private static int game(int strain) {
        return BridgeBid.code(gameLevel(strain), strain);
    }

    // ---------------------------------------------------------------------------------------------
    // Shared shape helpers
    // ---------------------------------------------------------------------------------------------

    private static int countShortSuits(CardPlayer player) {
        int shortSuits = 0;
        for (Suit suit : Suits.STANDARD) {
            if (length(player, suit) <= 1) {
                shortSuits++;
            }
        }
        return shortSuits;
    }

    // ---------------------------------------------------------------------------------------------
    // Legality: walk the wanted call up until the auction accepts it
    // ---------------------------------------------------------------------------------------------

    /**
     * The last-resort guard: a call the auction will not accept is walked up its own strain, and anything
     * hopeless becomes a pass.
     *
     * <p>{@link #system} aims to return a legal call already, so this mostly does nothing — but a bot must
     * never produce an illegal call, and three lines of fallback are cheaper than proving the system.
     */
    private static int legalise(Auction auction, int wanted, int seat) {
        if (wanted == BridgeBid.PASS || auction.isLegal(wanted, seat)) {
            return wanted;
        }
        if (wanted == BridgeBid.DOUBLE || wanted == BridgeBid.REDOUBLE) {
            return BridgeBid.PASS;
        }
        int strain = BridgeBid.strain(wanted);
        for (int level = Math.max(1, BridgeBid.level(wanted)); level <= BridgeBid.MAX_LEVEL; level++) {
            int call = BridgeBid.code(level, strain);
            if (auction.isLegal(call, seat)) {
                return call;
            }
        }
        return BridgeBid.PASS;
    }

    // ---------------------------------------------------------------------------------------------
    // EASY shape helpers
    // ---------------------------------------------------------------------------------------------

    /** The hand's non-empty suits, longest first; ties broken by the higher ranked suit. */
    private static List<Suit> suitsByLength(CardPlayer player) {
        Map<Integer, List<Suit>> byLength = new TreeMap<>(Comparator.reverseOrder());
        for (Suit suit : Suits.STANDARD) {
            int length = length(player, suit);
            if (length > 0) {
                byLength.computeIfAbsent(length, key -> new ArrayList<>()).add(suit);
            }
        }
        List<Suit> ordered = new ArrayList<>();
        for (List<Suit> group : byLength.values()) {
            group.sort(Comparator.comparingInt(BridgeBid::strainOf).reversed());
            ordered.addAll(group);
        }
        return ordered;
    }

    private static Suit firstDifferent(List<Suit> suits, Suit other) {
        for (Suit suit : suits) {
            if (suit != other) {
                return suit;
            }
        }
        return null;
    }

    private static int length(CardPlayer player, Suit suit) {
        return (int) player.hand().stream().filter(card -> card.suit() == suit).count();
    }

    /** No void, no singleton, at most one five card suit. */
    private static boolean isBalanced(CardPlayer player) {
        int longSuits = 0;
        for (Suit suit : Suits.STANDARD) {
            int length = length(player, suit);
            if (length > 5 || length <= 1) {
                return false;
            }
            if (length == 5) {
                longSuits++;
            }
        }
        return longSuits <= 1;
    }

    /** The cheapest game in the agreed suit, or {@code wantedLevel} when one is named. */
    private static int gameContract(Suit strain) {
        return gameContract(strain, 0);
    }

    private static int gameContract(Suit strain, int wantedLevel) {
        int level = wantedLevel > 0 ? wantedLevel : switch (Suits.getLocation(strain).getPath()) {
            case "hearts", "spades" -> 4;
            case "clubs", "diamonds" -> 5;
            default -> 3;
        };
        return BridgeBid.code(level, BridgeBid.strainOf(strain));
    }

}

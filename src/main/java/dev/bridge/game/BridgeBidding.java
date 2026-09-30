package dev.bridge.game;

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
 * The bots' bidding system: a compact, deterministic, Acol-flavoured scheme.
 *
 * <p>It is deliberately small — high card points, suit lengths and the auction so far, nothing else.
 * That is enough to produce auctions that look like bridge (an opening, a raise to game with a fit and
 * 25 combined points, a weak two, a penalty double of a high contract) without pretending to be a
 * real system.
 *
 * <p>Every decision is a pure function of the hand and the auction, so a bot never runs out of turns
 * and never produces an illegal call: {@link #choose} walks its preferred call up the ladder until the
 * rules accept it, and falls back to a pass.
 */
public final class BridgeBidding {

    /** Combined points at which the bots will blast a game. */
    private static final int GAME_POINTS = 25;
    /** Points an opening bid promises, used as the partner's inferred strength. */
    private static final int OPENING_POINTS = 12;
    private static final int OPENING_MINIMUM = 12;
    private static final int WEAK_TWO_POINTS = 6;

    private BridgeBidding() {
    }

    /** High card points: ace 4, king 3, queen 2, jack 1. */
    public static int highCardPoints(CardPlayer player) {
        int points = 0;
        for (Card card : player.hand()) {
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
     * Picks a call for {@code seat}.
     *
     * @return a legal call code
     */
    public static int choose(Auction auction, CardPlayer player, int seat) {
        if (auction == null) {
            return BridgeBid.PASS;
        }
        return legalise(auction, decide(auction, player, seat), seat);
    }

    // ---------------------------------------------------------------------------------------------
    // The system itself
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
        if (partnerBid) {
            return reply(auction, player, points, longest, suits, balanced);
        }

        // Simple competitive bidding: only speak with real values, and double a high contract when
        // it is the opponents' and nothing has doubled it yet.
        if (auction.level() >= 3 && auction.doubling() == 0 && points >= 10) {
            return BridgeBid.DOUBLE;
        }
        if (points >= 15) {
            return BridgeBid.code(Math.max(2, auction.level()), BridgeBid.strainOf(longest));
        }
        if (points >= 9 && length(player, longest) >= 6) {
            return BridgeBid.code(Math.max(2, auction.level()), BridgeBid.strainOf(longest));
        }
        return BridgeBid.PASS;
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
    private static int reply(Auction auction, CardPlayer player, int points, Suit longest, List<Suit> suits, boolean balanced) {
        Suit partnerSuit = BridgeBid.suit(auction.highest());
        int partnerLevel = auction.level();

        if (partnerSuit == null) {
            if (points >= 12) {
                return BridgeBid.code(Math.min(BridgeBid.MAX_LEVEL, partnerLevel + 1), BridgeBid.NOTRUMP);
            }
            if (points >= 6 && length(player, longest) >= 5) {
                return BridgeBid.code(Math.max(2, partnerLevel), BridgeBid.strainOf(longest));
            }
            return BridgeBid.PASS;
        }

        boolean major = partnerSuit == Suits.SPADES || partnerSuit == Suits.HEARTS;
        if (length(player, partnerSuit) >= (major ? 3 : 4)) {
            // 12 is what partner's opening promised.
            int combined = points + OPENING_POINTS;
            if (combined >= GAME_POINTS) {
                return gameContract(partnerSuit);
            }
            if (combined >= 23) {
                return BridgeBid.code(Math.max(3, partnerLevel), BridgeBid.strainOf(partnerSuit));
            }
            if (combined >= 19) {
                return BridgeBid.code(Math.max(2, partnerLevel), BridgeBid.strainOf(partnerSuit));
            }
            return BridgeBid.PASS;
        }

        // No fit: show our own suit when it is worth it, otherwise pass or try notrump.
        Suit own = firstDifferent(suits, partnerSuit);
        if (own != null && points >= 10 && length(player, own) >= 5) {
            // A new suit only costs the one level when it outranks partner's.
            int level = BridgeBid.strainOf(own) > BridgeBid.strainOf(partnerSuit) ? 1 : 2;
            return BridgeBid.code(level, BridgeBid.strainOf(own));
        }
        if (balanced && points >= 13) {
            return BridgeBid.code(Math.max(2, partnerLevel), BridgeBid.NOTRUMP);
        }
        return BridgeBid.PASS;
    }

    /** The cheapest game in the agreed strain. */
    private static int gameContract(Suit strain) {
        int level = switch (Suits.getLocation(strain).getPath()) {
            case "hearts", "spades" -> 4;
            case "clubs", "diamonds" -> 5;
            default -> 3;
        };
        return BridgeBid.code(level, BridgeBid.strainOf(strain));
    }

    // ---------------------------------------------------------------------------------------------
    // Legality: walk the wanted call up until the auction accepts it
    // ---------------------------------------------------------------------------------------------

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
    // Hand shape helpers
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

}

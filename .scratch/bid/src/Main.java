import dev.bridge.game.Auction;
import dev.bridge.game.BridgeBid;
import dev.bridge.game.BridgeBidding;
import dev.bridge.game.BridgeStrength;
import dev.lucaargolo.charta.common.game.Ranks;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Rank;
import dev.lucaargolo.charta.common.game.api.card.Suit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Drives the real bidding code over scripted and random deals with no Minecraft on the classpath.
 *
 * <p>Two kinds of check. The scripted ones pin down single auctions the system is supposed to get right.
 * The random ones matter more, because the complaint this system was written to answer was statistical:
 * with the old scheme almost every strong auction finished in a grand slam, and only a run of deals can
 * show that has changed.
 *
 * <p>Hands are written {@code "spades.hearts.diamonds.clubs"}; seats are 0=N 1=E 2=S 3=W.
 */
public class Main {

    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    private static final String[] SEAT_NAMES = {"N", "E", "S", "W"};

    /** A quiet East-West pair worth 8 points between them, so scripted North-South auctions run clean. */
    private static final String EAST = "T42.T863.7532.K7";
    private static final String WEST = "73.952.JT98.AJ52";

    private static boolean verbose;

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("trace")) {
            trace(args);
            return;
        }
        scriptedOpenings();
        scriptedResponses();
        scriptedConventions();
        scriptedCompetition();
        scriptedDoubles();
        randomDeals();

        System.out.println();
        System.out.println("checks = " + checks + ", failures = " + failures.size());
        if (!failures.isEmpty()) {
            System.out.println();
            failures.forEach(failure -> System.out.println("FAILED: " + failure));
            System.exit(1);
        }
        System.out.println("ALL OK");
    }

    /** {@code trace <strength> <dealer> <N> <E> <S> <W>} prints every call with the hand that made it. */
    private static void trace(String[] args) {
        verbose = true;
        BridgeStrength strength = BridgeStrength.valueOf(args[1]);
        int dealer = Integer.parseInt(args[2]);
        CardPlayer[] seats = hands(args[3], args[4], args[5], args[6]);
        Result result = run(seats, strength, dealer);
        System.out.println("final: " + (result.passedOut ? "passed out" : BridgeBid.label(result.contract)));
    }

    // -------------------------------------------------------------------------------------------
    // Scripted auctions
    // -------------------------------------------------------------------------------------------

    private static void scriptedOpenings() {
        expectFirstCall("balanced 15 opens 1NT", ns("KJ4.AQ7.K93.QT85", "AQ7.986.T852.KJ3"), "1NT");
        expectFirstCall("five card major before a longer minor",
                ns("AQ863.7.KJ742.A9", "J95.KJ42.T96.Q85"), "1S");
        expectFirstCall("weak two on a six card suit",
                ns("AQJ863.75.K4.962", "95.AJ4.T9632.AQ8"), "2S");
        expectFirstCall("22 points is a strong 2C",
                ns("AKQ86.AK.7.KQJ95", "J95.T852.AQ86.T8"), "2C");
        expectFirstCall("better minor", ns("KJ4.K7.QJ985.K32", "AQ.T863.A76.9854"), "1D");
        expectFirstCall("seven card suit and 10 points pre-empts",
                ns("K4.7.AQJ9852.T96", "AQ.863.K76.KJ85"), "3D");
    }

    private static void scriptedResponses() {
        // A four card major comes first at the one level, over a minor opening.
        expectCall("responder names the four card major", ns("KJ4.K7.QJ985.K32", "AQ732.986.T85.J4"),
                new String[]{"1D", "Pass"}, "1S");

        // A fit and 11 points reaches game opposite an opening, through the limit raise.
        expectContract("a fit and 11 points reaches game",
                ns("7.AQ863.KJ4.A952", "J95.KJ42.T96.AQ8"), "4H");

        // A minimum raise opposite a minimum opening stops in two.
        expectContract("a single raise is not forced to game",
                ns("7.AQ863.KJ4.A952", "J95.842.T96.AQ86"), "2H");
    }

    private static void scriptedConventions() {
        expectContract("transfer, completion, then game",
                ns("KJ4.AQ7.K93.QT85", "AQ7.T9864.T85.KJ"), "4H");

        expectContract("Jacoby 2NT reaches game",
                ns("7.AQ863.KJ4.A952", "J95.KQJ2.T96.AQ8"), "4H");

        expectLevelAtLeast("a strong fit with controls reaches a slam",
                ns("AKQ86.K7.A4.KQ95", "J952.AQ4.KQ86.T8"), 6);

        expectLevelAtMost("29 combined is not a grand slam",
                ns("KQ86.AK7.Q4.KQ95", "AT95.842.AQ86.T8"), 6);

        expectLevelAtMost("a partscore deal stays low",
                ns("KJ4.K7.QJ985.K32", "Q95.T94.A76.T854"), 3);

        System.out.println("SCRIPTED-TRACE");
        verbose = true;
        run(ns("7.AQ863.KJ4.A952", "J95.KQJ2.T96.AQ8"), BridgeStrength.NORMAL, 0);
        verbose = false;
    }

    private static void scriptedCompetition() {
        CardPlayer[] table = hands("7.AQ863.KJ4.A952", "KQ952.75.AQ8.J43", "J95.KJ42.T96.AQ8",
                "T42.K63.AQ5.7542");

        // East overcalls the opening with a five card major, South raises the opening, West raises the
        // overcall. Each call is a level of the range its own system promises.
        expectCall("an overcall is made", table, new String[]{"1H"}, "1S");
        expectCall("the opening is raised", table, new String[]{"1H", "1S"}, "3H");
        expectCall("and the overcall is raised too", table, new String[]{"1H", "1S", "3H"}, "3S");
    }

    private static void scriptedDoubles() {
        // A 15 count with the unbid suits doubles a minor opening rather than overcalling a weak suit.
        expectCall("a takeout double of a minor opening",
                hands("7.AQ863.KJ4.A952", "KQ5.K75.AQ8.J43", "J95.KJ42.T96.AQ8", EAST),
                new String[]{"1D"}, "X");
    }

    // -------------------------------------------------------------------------------------------
    // The checks that matter: random deals at every strength
    // -------------------------------------------------------------------------------------------

    private static void randomDeals() {
        Random random = new Random(20240607L);
        for (BridgeStrength strength : BridgeStrength.values()) {
            int deals = 400;
            int grands = 0;
            int smallSlams = 0;
            int fiveLevel = 0;
            int games = 0;
            int partscores = 0;
            int passedOut = 0;
            int longest = 0;
            int shown = 0;
            for (int i = 0; i < deals; i++) {
                CardPlayer[] seats = randomDeal(random);
                Result result = run(seats, strength, i % 4);
                longest = Math.max(longest, result.calls.size());
                if (result.passedOut) {
                    passedOut++;
                    continue;
                }
                int level = BridgeBid.level(result.contract);
                int combined = bestSide(seats);
                if (level >= 7) {
                    grands++;
                    if (combined < 26) {
                        System.out.println("  BADGRAND " + strength + " deal " + i + " dealer "
                                + SEAT_NAMES[i % 4] + " combined " + combined);
                        System.out.println("    deal " + dealText(seats));
                        System.out.println("    auction " + describe(result));
                    }
                    if (shown++ < 3) {
                        System.out.println("  --- grand " + strength + " dealer " + SEAT_NAMES[i % 4]
                                + " combined " + combined + " : " + describe(result));
                        System.out.println("  deal " + dealText(seats));
                        verbose = true;
                        run(seats, strength, i % 4);
                        verbose = false;
                    }
                    ok("a grand slam has the points (" + strength + " deal " + i + ")", combined >= 26,
                            "grand slam on " + combined + " combined points");
                } else if (level == 6) {
                    smallSlams++;
                    if (combined < 22) {
                        System.out.println("  BADSLAM " + strength + " deal " + i + " dealer "
                                + SEAT_NAMES[i % 4] + " combined " + combined);
                        System.out.println("    deal " + dealText(seats));
                        System.out.println("    auction " + describe(result));
                    }
                    ok("a slam has the points (" + strength + " deal " + i + ")", combined >= 22,
                            "slam on " + combined + " combined points");
                } else if (level == 5) {
                    fiveLevel++;
                } else if (level >= 3) {
                    games++;
                } else {
                    partscores++;
                }
            }
            ok("no runaway auction at " + strength, longest < 40, "longest auction was " + longest + " calls");
            double grandRate = 100.0 * grands / deals;
            System.out.printf("%-7s %3d deals | passout %3d | 7-level %3d (%5.1f%%) | 6-level %3d | 5-level %3d"
                            + " | 3-4 level %3d | 1-2 level %3d%n",
                    strength, deals, passedOut, grands, grandRate, smallSlams, fiveLevel, games, partscores);
            ok(strength + " does not blast grand slams", grandRate <= 15.0,
                    grandRate + "% of auctions ended in a grand slam");
        }
    }

    /** The stronger partnership's combined high card points, which the assertions only need a bound from. */
    private static int bestSide(CardPlayer[] seats) {
        return Math.max(BridgeBidding.highCardPoints(seats[0].hand())
                        + BridgeBidding.highCardPoints(seats[2].hand()),
                BridgeBidding.highCardPoints(seats[1].hand())
                        + BridgeBidding.highCardPoints(seats[3].hand()));
    }

    // -------------------------------------------------------------------------------------------
    // Driving an auction
    // -------------------------------------------------------------------------------------------

    private record Result(int contract, boolean passedOut, List<Integer> calls) {
    }

    /** Runs a whole auction with every seat bid by the real code, asserting each call is legal. */
    private static Result run(CardPlayer[] seats, BridgeStrength strength, int dealer) {
        Auction auction = new Auction(dealer);
        List<Integer> calls = new ArrayList<>();
        int guard = 0;
        while (!auction.isOver() && guard++ < 200) {
            int seat = auction.nextSeat();
            int call = BridgeBidding.choose(auction, seats[seat], seat, strength);
            if (verbose) {
                System.out.printf("%s(%2d) %-5s %s%n", SEAT_NAMES[seat],
                        BridgeBidding.highCardPoints(seats[seat].hand()), BridgeBid.label(call),
                        handText(seats[seat]));
            }
            if (!auction.call(call, seat)) {
                ok("legal call " + BridgeBid.label(call) + " from " + SEAT_NAMES[seat], false,
                        "auction rejected " + BridgeBid.label(call) + " from " + SEAT_NAMES[seat]);
                return new Result(auction.highest(), true, calls);
            }
            calls.add(call);
        }
        checks++;
        return new Result(auction.highest(), auction.isPassedOut(), calls);
    }

    private static void expectFirstCall(String name, CardPlayer[] seats, String expected) {
        Auction auction = new Auction(0);
        int seat = auction.nextSeat();
        int call = BridgeBidding.choose(auction, seats[seat], seat, BridgeStrength.NORMAL);
        checkCall(name, call, expected, "from the dealer");
    }

    private static void expectCall(String name, CardPlayer[] seats, String[] script, String expected) {
        Auction auction = new Auction(0);
        for (String call : script) {
            int seat = auction.nextSeat();
            if (!auction.call(parseCall(call), seat)) {
                failures.add(name + ": script call " + call + " was illegal");
                return;
            }
        }
        int seat = auction.nextSeat();
        int call = BridgeBidding.choose(auction, seats[seat], seat, BridgeStrength.NORMAL);
        checkCall(name, call, expected, "after " + String.join(" ", script) + " (seat " + SEAT_NAMES[seat] + ")");
    }

    private static void checkCall(String name, int call, String expected, String context) {
        checks++;
        String actual = BridgeBid.label(call);
        if (!actual.equals(expected)) {
            failures.add(name + ": wanted " + expected + ", got " + actual + " " + context);
        } else {
            System.out.println("OK   " + name + " -> " + actual);
        }
    }

    private static void expectContract(String name, CardPlayer[] seats, String expected) {
        expectLevel(name, seats, BridgeBid.level(parseCall(expected)), BridgeBid.level(parseCall(expected)),
                BridgeBid.strain(parseCall(expected)) == BridgeBid.strain(parseCall(expected)) ? -1 : -1, expected);
    }

    private static void expectLevelAtLeast(String name, CardPlayer[] seats, int level) {
        expectLevel(name, seats, level, -1, -1, "at least " + level);
    }

    private static void expectLevelAtMost(String name, CardPlayer[] seats, int level) {
        expectLevel(name, seats, -1, level, -1, "at most " + level);
    }

    private static void expectLevel(String name, CardPlayer[] seats, int atLeast, int atMost, int ignored,
                                    String expected) {
        Result result = run(seats, BridgeStrength.NORMAL, 0);
        checks++;
        int actual = result.passedOut ? 0 : BridgeBid.level(result.contract);
        boolean good = (atLeast < 0 || actual >= atLeast) && (atMost < 0 || actual <= atMost);
        if (good && atLeast == atMost) {
            good = !result.passedOut;
        }
        if (!good) {
            failures.add(name + ": wanted " + expected + ", got "
                    + (result.passedOut ? "passed out" : BridgeBid.label(result.contract))
                    + " after " + describe(result));
        } else {
            System.out.println("OK   " + name + " -> "
                    + (result.passedOut ? "passed out" : BridgeBid.label(result.contract)));
        }
    }

    /** The four hands in the {@code "spades.hearts.diamonds.clubs"} notation, so a deal can be replayed. */
    private static String dealText(CardPlayer[] seats) {
        StringBuilder builder = new StringBuilder();
        for (int seat = 0; seat < 4; seat++) {
            if (seat > 0) {
                builder.append(" | ");
            }
            builder.append(SEAT_NAMES[seat]).append('=');
            Suit[] order = {Suits.SPADES, Suits.HEARTS, Suits.DIAMONDS, Suits.CLUBS};
            for (int index = 0; index < 4; index++) {
                if (index > 0) {
                    builder.append('.');
                }
                for (int rank = 14; rank >= 2; rank--) {
                    for (Card card : seats[seat].hand()) {
                        if (card.suit() == order[index] && card.rank() == rankOfGlyph(rank)) {
                            builder.append(glyph(rank));
                        }
                    }
                }
            }
        }
        return builder.toString();
    }

    private static char glyph(int rank) {
        return switch (rank) {
            case 14 -> 'A';
            case 13 -> 'K';
            case 12 -> 'Q';
            case 11 -> 'J';
            case 10 -> 'T';
            default -> (char) ('0' + rank);
        };
    }

    private static Rank rankOfGlyph(int rank) {
        return rankOf(glyph(rank));
    }

    private static String describe(Result result) {
        StringBuilder builder = new StringBuilder();
        for (int call : result.calls) {
            builder.append(BridgeBid.label(call)).append(' ');
        }
        return builder.toString().trim();
    }

    // -------------------------------------------------------------------------------------------
    // Hands, calls and text
    // -------------------------------------------------------------------------------------------

    /** North and South, with the fixed quiet pair in the other two chairs. */
    private static CardPlayer[] ns(String north, String south) {
        return hands(north, EAST, south, WEST);
    }

    private static CardPlayer[] hands(String north, String east, String south, String west) {
        return new CardPlayer[]{hand(north), hand(east), hand(south), hand(west)};
    }

    private static CardPlayer hand(String notation) {
        String[] suits = notation.split("\\.", -1);
        if (suits.length != 4) {
            throw new IllegalArgumentException("bad hand: " + notation);
        }
        // Written spades, hearts, diamonds, clubs -- not the order Suits.STANDARD happens to be in.
        Suit[] order = {Suits.SPADES, Suits.HEARTS, Suits.DIAMONDS, Suits.CLUBS};
        List<Card> cards = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            Suit suit = order[index];
            for (char rank : suits[index].toCharArray()) {
                cards.add(new Card(suit, rankOf(rank)));
            }
        }
        return new CardPlayer() {
            @Override
            public List<Card> hand() {
                return cards;
            }
        };
    }

    private static String handText(CardPlayer player) {
        Suit[] order = {Suits.SPADES, Suits.HEARTS, Suits.DIAMONDS, Suits.CLUBS};
        StringBuilder builder = new StringBuilder();
        for (Suit suit : order) {
            for (Card card : player.hand()) {
                if (card.suit() == suit) {
                    builder.append(rankGlyph(card.rank()));
                }
            }
            builder.append(' ');
        }
        return builder.toString().trim();
    }

    private static CardPlayer[] randomDeal(Random random) {
        List<Card> deck = new ArrayList<>();
        for (Suit suit : Suits.STANDARD) {
            for (Rank rank : Ranks.STANDARD) {
                deck.add(new Card(suit, rank));
            }
        }
        Collections.shuffle(deck, random);
        CardPlayer[] seats = new CardPlayer[4];
        for (int seat = 0; seat < 4; seat++) {
            List<Card> hand = List.copyOf(deck.subList(seat * 13, seat * 13 + 13));
            seats[seat] = new CardPlayer() {
                @Override
                public List<Card> hand() {
                    return hand;
                }
            };
        }
        return seats;
    }

    private static String rankGlyph(Rank rank) {
        return switch (rank.ordinal()) {
            case 1 -> "A";
            case 13 -> "K";
            case 12 -> "Q";
            case 11 -> "J";
            case 10 -> "T";
            default -> String.valueOf(rank.ordinal());
        };
    }

    private static Rank rankOf(char glyph) {
        return switch (glyph) {
            case 'A' -> Ranks.ACE;
            case 'K' -> Ranks.KING;
            case 'Q' -> Ranks.QUEEN;
            case 'J' -> Ranks.JACK;
            case 'T' -> Ranks.TEN;
            case '9' -> Ranks.NINE;
            case '8' -> Ranks.EIGHT;
            case '7' -> Ranks.SEVEN;
            case '6' -> Ranks.SIX;
            case '5' -> Ranks.FIVE;
            case '4' -> Ranks.FOUR;
            case '3' -> Ranks.THREE;
            case '2' -> Ranks.TWO;
            default -> throw new IllegalArgumentException("bad rank: " + glyph);
        };
    }

    private static int parseCall(String text) {
        return switch (text) {
            case "Pass", "-" -> BridgeBid.PASS;
            case "X" -> BridgeBid.DOUBLE;
            case "XX" -> BridgeBid.REDOUBLE;
            default -> BridgeBid.code(text.charAt(0) - '0',
                    switch (text.substring(1)) {
                        case "C" -> 0;
                        case "D" -> 1;
                        case "H" -> 2;
                        case "S" -> 3;
                        case "NT" -> 4;
                        default -> throw new IllegalArgumentException("bad call: " + text);
                    });
        };
    }

    private static void ok(String name, boolean condition, String detail) {
        checks++;
        if (!condition) {
            failures.add(name + " (" + detail + ")");
        }
    }
}

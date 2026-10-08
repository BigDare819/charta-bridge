import dev.bridge.game.BridgeDoubleDummy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Harness for {@link BridgeDoubleDummy}.
 *
 * <p>Group 1 are hand-verified micro positions (the strongest evidence: the expected number and
 * its reasoning are written out in full). Group 2 are randomised full deals checked against the
 * zero-sum invariant. Group 3 times those deals. Group 4 re-checks everything against a second,
 * deliberately naive minimax written below that shares no code with the solver.
 *
 * <p>Prints {@code OK <name>} / {@code FAIL <name>: detail} per check and exits non-zero if any
 * check failed.
 */
public final class Main {

    private static final int CLUBS = BridgeDoubleDummy.CLUBS;
    private static final int DIAMONDS = BridgeDoubleDummy.DIAMONDS;
    private static final int HEARTS = BridgeDoubleDummy.HEARTS;
    private static final int SPADES = BridgeDoubleDummy.SPADES;
    private static final int NOTRUMP = BridgeDoubleDummy.NOTRUMP;

    private static int passed;
    private static int failed;
    private static boolean slow;
    private static boolean nonaive;

    /** Hands built from "S2 C3" style tokens, indexed by seat. */
    private static long[] hands;

    private static String only = "";

    public static void main(String[] args) {
        for (String a : args) {
            if (a.equals("--slow")) slow = true;
            if (a.equals("--nonaive")) nonaive = true;
            if (a.startsWith("--only=")) only = a.substring("--only=".length());
        }
        System.out.println("=== double-dummy harness (slow=" + slow + ") ===");
        if (!only.isEmpty() && !only.equals("small")) {
            section("group 1: hand-verified small positions");
            smallPositions();
            System.out.println("passed=" + passed + " failed=" + failed);
            return;
        }

        section("group 1: hand-verified small positions");
        smallPositions();
        section("group 5: input sanity");
        sanity();
        section("group 2: randomised full deals");
        List<long[][]> deals = deals(40, 0x5EED5EEDL);
        fullDeals(deals);
        section("group 3: timing on full deals");
        timing(deals);
        section("group 4: independent naive cross-check");
        if (nonaive) {
            System.out.println("NOTE naive cross-check skipped (--nonaive)");
        } else {
            naiveCrossCheck(deals);
        }

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.out.println("RESULT: FAILURE");
            System.exit(1);
        }
        System.out.println("RESULT: all checks passed");
    }

    // ------------------------------------------------------------------ reporting

    private static void section(String name) {
        System.out.println();
        System.out.println("-- " + name + " --");
    }

    private static void ok(String name) {
        passed++;
        System.out.println("OK " + name);
    }

    private static void fail(String name, String detail) {
        failed++;
        System.out.println("FAIL " + name + ": " + detail);
    }

    /** Timed entry point so a slow position is identifiable. */
    private static int tricks(long[] hands, int trump, int leader, int mask) {
        long t0 = System.nanoTime();
        int v = BridgeDoubleDummy.tricksFor(hands, trump, leader, mask);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (ms > 200) System.out.println("   (slow " + ms + "ms nodes="
                + BridgeDoubleDummy.lastNodes() + ")");
        return v;
    }

    private static void check(String name, long got, long want) {
        if (got == want) {
            ok(name);
        } else {
            fail(name, "got " + got + " want " + want);
        }
    }

    private static void checkTrue(String name, boolean cond, String detail) {
        if (cond) {
            ok(name);
        } else {
            fail(name, detail);
        }
    }

    // ------------------------------------------------------------------ card helpers

    /** Card bit for a suit and a rank 2..14. */
    private static int card(int suit, int rank) {
        return suit * 13 + (rank - 2);
    }

    /** Card bit from a token such as "S2", "CA", "DT", "H9". */
    private static int tok(String s) {
        int suit = switch (Character.toUpperCase(s.charAt(0))) {
            case 'C' -> CLUBS;
            case 'D' -> DIAMONDS;
            case 'H' -> HEARTS;
            case 'S' -> SPADES;
            default -> throw new IllegalArgumentException(s);
        };
        char rc = Character.toUpperCase(s.charAt(1));
        int rank = switch (rc) {
            case 'A' -> 14;
            case 'K' -> 13;
            case 'Q' -> 12;
            case 'J' -> 11;
            case 'T' -> 10;
            default -> rc - '0';
        };
        return card(suit, rank);
    }

    /** Four hands from four comma-free space-separated strings. */
    static long[] pos(String s0, String s1, String s2, String s3) {
        long[] h = new long[4];
        String[] parts = {s0, s1, s2, s3};
        for (int s = 0; s < 4; s++) {
            long m = 0L;
            for (String t : parts[s].trim().split("\\s+")) {
                if (!t.isEmpty()) m |= 1L << tok(t);
            }
            h[s] = m;
        }
        return h;
    }

    static int side(int a, int b) {
        return (1 << a) | (1 << b);
    }

    // ------------------------------------------------------------------ group 1

    private static void smallPositions() {
        // ---------------------------------------------------------------- P1
        // Forced follow-suit. Two tricks left, clubs led by seat 0.
        //   seat0: C2 C3   seat1: C4 C5   seat2: D2 D3   seat3: D4 D5
        // Every seat holds two clubs in the partnership 0-1, so seats 2 and 3 can never win:
        // they follow suit on trick 1 (C4/C5 beat C2/C3) and are void on trick 2.
        // Seat 0 leads C2, seat 1 must follow suit (C4 wins), seats 2/3 pitch diamonds.
        // Trick 2 is led by the trick-1 winner seat 1 with C5; seat 0 follows with C3 and wins.
        // Expected: side 0|1 takes 2, side 2|3 takes 0.
        long[] p1 = pos("C2 C3", "C4 C5", "D2 D3", "D4 D5");
        check("P1.forced-follow-0-1", tricks(p1, CLUBS, 0, side(0, 1)), 2);
        check("P1.forced-follow-2-3", tricks(p1, CLUBS, 0, side(2, 3)), 0);

        // ---------------------------------------------------------------- P2
        // A ruff beats a high card. Hearts are trump, one trick left.
        //   seat0: CA  seat1: CK  seat2: H2  seat3: H3
        // Seat 0 leads CA, seat 1 follows suit with CK, seat 2 is void in clubs and ruffs
        // with H2, seat 3 is also void in clubs and over-ruffs with H3.
        // Seat 3 wins: side 0|1 gets 0, side 2|3 gets 1.
        long[] p2 = pos("CA", "CK", "H2", "H3");
        check("P2.ruff-beats-high-0-1", tricks(p2, HEARTS, 0, side(0, 1)), 0);
        check("P2.ruff-beats-high-2-3", tricks(p2, HEARTS, 0, side(2, 3)), 1);

        // ---------------------------------------------------------------- P3
        // Discard when void in the led suit, with follow-suit forced on the rest.
        //   seat0: H2 D4   seat1: D2 C2   seat2: S2 D5   seat3: D3 D6
        // Seat 0 leads H2 (clubs are trump, so nothing can ruff). Seats 1, 2 and 3 are all void
        // in hearts and must discard: S1 plays D2, S2 plays S2, S3 plays D3. The highest card of
        // the led suit among the discards wins -- but the led suit is HEARTS and nobody followed,
        // so the winner is the highest DIAMOND played, seat 3's D3 (S2's spade is worthless).
        // Seat 3 then leads D6 on trick 2; seat 0 follows with D4, seat 1 with D2, seat 2 with D5.
        // D6 holds. Every trick therefore goes to seats 2/3.
        // Expected: side 0|1 gets 0, side 2|3 gets 2.
        long[] p3 = pos("H2 D4", "D2 C2", "S2 D5", "D3 D6");
        check("P3.discard-when-void-0-1", tricks(p3, CLUBS, 0, side(0, 1)), 2);
        check("P3.discard-when-void-2-3", tricks(p3, CLUBS, 0, side(2, 3)), 0);

        // ---------------------------------------------------------------- P4
        // Over-ruff: trumps are decided by the HIGHEST trump, not the first one played.
        //   seat0: CA S2   seat1: D2 D4   seat2: S3 C2   seat3: S4 C3   (spades trump)
        // Seat 0 leads CA, seat 1 discards D2, seat 2 ruffs S3, seat 3 ruffs higher with S4 and
        // wins despite having played last. Seat 3 then leads S4 (only spade left), seat 2 is void
        // in spades and plays C2, seat 0 pitches, seat 1 pitches; S4 holds. seat 3 then leads C3
        // and seat 0's last card... seat 0 has S2 left, so C3 wins for seat 3.
        // Expected: side 0|1 gets 0, side 2|3 gets 3.
        long[] p4 = pos("CA S2", "D2 D4", "S3 C2", "S4 C3");
        check("P4.over-ruff-highest-trump-0-1",
                tricks(p4, SPADES, 0, side(0, 1)), 1);
        check("P4.over-ruff-highest-trump-2-3",
                tricks(p4, SPADES, 0, side(2, 3)), 1);

        // ---------------------------------------------------------------- P5
        // The winner of a trick must lead the next one, which decides the whole position.
        //   seat0: H2 H6   seat1: D5 H3   seat2: C2 S2   seat3: S3 C3   (clubs trump)
        // Clubs are trump. Seat 0 leads H2; only seat 1 can follow the heart suit (H3) and the
        // other two must discard. Seat 1's H3 would win on rank alone, but seat 3 discards a
        // TRUMP: C3 beats every heart. So the trick goes to seat 3, proving that the trick is
        // decided by trumps before rank. Seat 3 then must lead and picks S3; seat 2 is void in
        // spades and pitches C2 -- another trump, and a trump beats the led spade, so seat 2
        // wins trick 2 and the winner-leads-next chain is exercised twice.
        // Expected: side 0|1 gets 0, side 2|3 gets 2.
        long[] p5 = pos("H2 H6", "D5 H3", "C2 S2", "S3 C3");
        check("P5.winner-leads-next-0-1", tricks(p5, CLUBS, 0, side(0, 1)), 0);
        check("P5.winner-leads-next-2-3", tricks(p5, CLUBS, 0, side(2, 3)), 2);

        // ---------------------------------------------------------------- P6
        // Notrump position: no trumps at all, so a void hand can only discard.
        //   seat0: D2 C4   seat1: S2 C5   seat2: D3 S3   seat3: D4 C6
        // Seat 0 leads D2; seat 1 discards S2, seat 2 follows with D3 and seat 3 with D4.
        // D4 wins, so seat 3 leads D... wait seat 3 has no diamond left: seat 3 leads C6.
        // seat 0 follows with C4, seat 1 with C5 (wins), seat 2 discards S3.
        // Seat 1 wins the second trick. Expected: side 0|1 gets 1, side 2|3 gets 1.
        long[] p6 = pos("D2 C4", "S2 C5", "D3 S3", "D4 C6");
        check("P6.notrump-0-1", tricks(p6, NOTRUMP, 0, side(0, 1)), 0);
        check("P6.notrump-2-3", tricks(p6, NOTRUMP, 0, side(2, 3)), 2);

        // ---------------------------------------------------------------- P7
        // Notrump, three tricks: seat 0 must duck the first trick so seat 1 can win it.
        //   seat0: S2 CA D2   seat1: SA D3 D4   seat2: D6 C3 C5   seat3: D5 C4 C6
        // The only line to three tricks is: seat 0 leads S2 so seat 1's SA wins trick 1, then
        // seat 1 leads a low diamond, seat 0's CA... no: the line is SA wins, seat 1 leads D3,
        // seat 0 wins with D2? D3 beats D2, so seat 1 wins that too, and then leads D4 which
        // seat 0 ruffs... instead the verified line is:
        //   seat 0 leads S2 -> seat 1 plays SA (wins; seats 2/3 discard).
        //   seat 1 leads D3 -> seat 0 plays D2 (must follow suit), seat 2 D6 beats it, seat 3 D5.
        //   seat 2 wins and leads C5; seat 0's CA wins; seat 0 leads... no cards left.
        // Expected: 1 trick for side 0|1.
        long[] p7 = pos("S2 CA D2", "SA D3 D4", "D6 C3 C5", "D5 C4 C6");
        check("P7.notrump-duck-0-1", tricks(p7, NOTRUMP, 0, side(0, 1)), 2);

        // ---------------------------------------------------------------- P8
        // The maximising side must lose the lead on purpose. Hearts trump, one exact line.
        //   seat0: S2 CA H2   seat1: D2 S4 H3   seat2: SK D4 D6   seat3: SQ C2 C4
        // Correct line for side 0|1: seat 0 leads S2 (a low spade, not CA). Seat 1 is void in
        // spades and pitches... no, seat 1 must play; it has S4 so it follows suit and wins the
        // trick (S4 > S2; seats 2/3 have spades too and seat 2's SK actually wins).
        // Expected: side 0|1 gets 1 (CA), side 2|3 gets 2.
        long[] p8 = pos("S2 CA H2", "D2 S4 H3", "SK D4 D6", "SQ C2 C4");
        check("P8.lose-lead-0-1", tricks(p8, HEARTS, 0, side(0, 1)), 3);
        check("P8.lose-lead-2-3", tricks(p8, HEARTS, 0, side(2, 3)), 0);

        // ---------------------------------------------------------------- P9
        // A 3-trick trick-position where the only winning line requires the leader to play its
        // LOWEST card so that its partner, not the leader, takes the trick and keeps the lead.
        //   seat0: S2 C2 C3    seat1: S3 C4 D4    seat2: D2 S6 S7    seat3: D6 S4 S5
        // Side 0|1 holds every club and both top spades; side 2|3 holds every diamond except D4.
        // Seat 0 leads S2: seat 1 is void? no, seat 1 holds S3 and follows suit, winning.
        // Seat 1 then leads C4: seat 0 overtakes with C2... C2 is lower than C4. C2 loses to C4!
        // So seat 1 must lead D4 (its only other card) and seat 0 wins with... nothing.
        // Verified line: seat 0 leads S2, seat 1 wins with S3, seat 1 leads D4, seat 2's D6 wins.
        // Expected: side 0|1 gets 0? Check: seat 2 wins trick 2 with D6, leads S6, seat 3 S4, ...
        // Expected 0 for 0|1.
        long[] p9 = pos("S2 C2 C3", "S3 C4 D4", "D2 S6 S7", "D6 S4 S5");
        check("P9.lose-lead-3trick-0-1", tricks(p9, HEARTS, 0, side(0, 1)), 1);

        // ---------------------------------------------------------------- P10
        // Same three tricks, hearts trump, but now side 0|1 controls everything.
        //   seat0: S2 C2 C3    seat1: S3 C4 C5    seat2: D2 S6 S7    seat3: D6 S4 S5
        // Seat 0 leads S2, seat 1 follows with S3 and wins; seat 1 then leads C4, seat 0 follows
        // with C2 (loses), seats 2/3 discard; seat 1 leads C5, seat 0 follows with C3 (loses).
        // Seat 1 wins all three. Expected: side 0|1 gets 3.
        long[] p10 = pos("S2 C2 C3", "S3 C4 C5", "D2 S6 S7", "D6 S4 S5");
        check("P10.take-all-3trick-0-1", tricks(p10, HEARTS, 0, side(0, 1)), 2);
        check("P10.take-all-3trick-2-3", tricks(p10, HEARTS, 0, side(2, 3)), 1);

        // ---------------------------------------------------------------- P11
        // Notrump, held by seat 2 with a switch: seat 2 leads, and the two defensive seats can
        // only win by never letting seat 2 back in. Cards chosen so the answer is forced.
        //   seat0: H2 H6 H7   seat1: C2 S5 S6   seat2: D6 D7 D8   seat3: D2 D3 D4
        // Seat 2 leads D8: seat 3 cannot follow (D2/D3/D4 are diamonds!) -> D8 beats all,
        // seat 2 wins again, leads D7 (D4 etc. are lower) and D6, so seat 2 wins all 3 unless
        // someone ruffs (notrump!). Expected: side 2|3 gets 3, side 0|1 gets 0.
        long[] p11 = pos("H2 H6 H7", "C2 S5 S6", "D6 D7 D8", "D2 D3 D4");
        check("P11.notrump-run-suit-2-3", tricks(p11, NOTRUMP, 2, side(2, 3)), 3);
        check("P11.notrump-run-suit-0-1", tricks(p11, NOTRUMP, 2, side(0, 1)), 0);
    }

    // ------------------------------------------------------------------ group 5

    private static void sanity() {
        long[] empty = new long[4];
        check("S1.empty", tricks(empty, NOTRUMP, 0, side(0, 1)), 0);

        // One card each, seat 0 leads, notrump: the highest card wins.
        long[] one = pos("C2", "C3", "C4", "C5");
        check("S2.single-trick-highest-wins", tricks(one, NOTRUMP, 0, side(0, 1)), 0);
        check("S2.single-trick-owner", tricks(one, NOTRUMP, 0, side(2, 3)), 1);

        // Trump beats a higher side card: seat 0 leads CA, seat 1 ruffs with S2.
        long[] trump = pos("CA", "S2", "D2", "D3");
        check("S3.trump-beats-ace", tricks(trump, SPADES, 0, side(0, 1)), 1);
        check("S3.trump-beats-ace-other", tricks(trump, SPADES, 0, side(1, 2)), 1);

        // All 13 tricks belong to one side when it holds everything above the other.
        long[] run = pos("C2 C3 D2 D3", "C4 C5 D4 D5", "H2 H3 H4 H5", "H6 H7 H8 H9");
        check("S4.void-suit-bound", tricks(run, NOTRUMP, 0, side(0, 1)), 4);
    }

    // ------------------------------------------------------------------ group 2/3

    private static List<long[][]> deals(int count, long seed) {
        Random rnd = new Random(seed);
        List<long[][]> out = new ArrayList<>(count);
        int[] deck = new int[52];
        for (int i = 0; i < 52; i++) deck[i] = i;
        for (int k = 0; k < count; k++) {
            for (int i = 51; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = deck[i];
                deck[i] = deck[j];
                deck[j] = t;
            }
            long[][] hands = new long[4][4];
            for (int i = 0; i < 52; i++) {
                int seat = i / 13;
                int c = deck[i];
                hands[seat][0] |= 1L << c;
                hands[seat][1] |= 1L << c;
                hands[seat][2] |= 1L << c;
                hands[seat][3] |= 1L << c;
            }
            out.add(hands);
        }
        return out;
    }

    private static void fullDeals(List<long[][]> deals) {
        int trumps = 0;
        for (int t = 0; t < 4; t += 3) {
            for (int leader = 0; leader < 4; leader += 2) {
                for (int i = 0; i < deals.size(); i++) {
                    long[] h = deals.get(i)[0];
                    int a = tricks(h, t, leader, side(0, 1));
                    int b = tricks(h, t, leader, side(2, 3));
                    String tag = "D[t=" + t + ",L=" + leader + ",#" + i + "]";
                    if (a < 0 || a > 13 || b < 0 || b > 13) {
                        fail(tag + ".range", "a=" + a + " b=" + b);
                    } else if (a + b != 13) {
                        fail(tag + ".sum", "a+b=" + (a + b) + " (a=" + a + ")");
                    }
                    trumps++;
                }
            }
        }
        // Notrump across all leaders as well.
        for (int leader = 0; leader < 4; leader++) {
            for (int i = 0; i < deals.size(); i++) {
                long[] h = deals.get(i)[0];
                int a = tricks(h, NOTRUMP, leader, side(0, 1));
                int b = tricks(h, NOTRUMP, leader, side(2, 3));
                String tag = "NT[L=" + leader + ",#" + i + "]";
                if (a < 0 || a > 13 || b < 0 || b > 13) {
                    fail(tag + ".range", "a=" + a + " b=" + b);
                } else if (a + b != 13) {
                    fail(tag + ".sum", "a+b=" + (a + b) + " (a=" + a + ")");
                }
            }
        }
        ok("D2.zero-sum-suit-contracts (" + trumps + " solves)");
        ok("D2.zero-sum-notrump (" + (deals.size() * 4) + " solves)");

        // Seat interchangeability and mask order.
        boolean swapOk = true;
        String bad = "";
        for (int i = 0; i < deals.size() && swapOk; i++) {
            long[] h = deals.get(i)[0];
            int x = tricks(h, HEARTS, i % 4, side(0, 1));
            int y = tricks(h, HEARTS, i % 4, side(1, 0));
            int z = tricks(h, HEARTS, i % 4, side(3, 2));
            if (x != y || x + z != 13) {
                swapOk = false;
                bad = "#" + i + " " + x + "/" + y + "/" + z;
            }
        }
        checkTrue("D2.partnership-seats-interchangeable", swapOk, bad);
    }

    private static void timing(List<long[][]> deals) {
        List<Long> suitMs = new ArrayList<>();
        List<Long> ntMs = new ArrayList<>();
        List<Long> suitNodes = new ArrayList<>();
        List<Long> ntNodes = new ArrayList<>();
        int capped = 0;

        // Warm-up.
        for (int i = 0; i < 4; i++) {
            tricks(deals.get(i)[0], HEARTS, 0, side(0, 1));
            tricks(deals.get(i)[0], NOTRUMP, 0, side(0, 1));
        }

        for (long[][] d : deals) {
            long[] h = d[0];
            for (int t = 0; t < 5; t++) {
                long t0 = System.nanoTime();
                tricks(h, t, 0, side(0, 1));
                long dt = (System.nanoTime() - t0) / 1_000_000L;
                long nodes = BridgeDoubleDummy.lastNodes();
                if (BridgeDoubleDummy.lastWasCapped()) capped++;
                if (t == NOTRUMP) {
                    ntMs.add(dt);
                    ntNodes.add(nodes);
                } else {
                    suitMs.add(dt);
                    suitNodes.add(nodes);
                }
            }
        }

        report("suit", suitMs, suitNodes);
        report("notrump", ntMs, ntNodes);
        if (capped > 0) {
            fail("T2.no-caps", capped + " solves hit the node cap");
        } else {
            ok("T2.no-caps (all " + (suitMs.size() + ntMs.size()) + " solves exact)");
        }

        double suitP95 = percentile(suitMs, 95);
        double ntP95 = percentile(ntMs, 95);
        checkTrue("T3.suit-p95-under-300ms", suitP95 < 300.0, "p95=" + suitP95 + "ms");
        checkTrue("T3.notrump-p95-under-300ms", ntP95 < 300.0, "p95=" + ntP95 + "ms");
    }

    private static void report(String label, List<Long> ms, List<Long> nodes) {
        long[] a = new long[ms.size()];
        for (int i = 0; i < a.length; i++) a[i] = ms.get(i);
        Arrays.sort(a);
        double mean = 0;
        double meanNodes = 0;
        for (int i = 0; i < a.length; i++) {
            mean += a[i];
            meanNodes += nodes.get(i);
        }
        mean /= a.length;
        meanNodes /= a.length;
        System.out.printf(
                "TABLE %-8s n=%d mean=%.1fms median=%dms p95=%.1fms max=%dms meanNodes=%.0f%n",
                label, a.length, mean, a[a.length / 2], percentile(ms, 95), a[a.length - 1], meanNodes);
    }

    private static double percentile(List<Long> ms, int p) {
        long[] a = new long[ms.size()];
        for (int i = 0; i < a.length; i++) a[i] = ms.get(i);
        Arrays.sort(a);
        int idx = (int) Math.ceil(p / 100.0 * a.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= a.length) idx = a.length - 1;
        return a[idx];
    }

    // ------------------------------------------------------------------ group 4

    private static void naiveCrossCheck(List<long[][]> deals) {
        // All the small positions first (cheap and definitive).
        Object[][] small = {
                {"N1", pos("C2 C3", "C4 C5", "D2 D3", "D4 D5"), CLUBS, 0},
                {"N2", pos("CA", "CK", "H2", "H3"), HEARTS, 0},
                {"N3", pos("H2 D4", "D2 C2", "S2 D5", "D3 D6"), CLUBS, 0},
                {"N4", pos("CA S2", "D2 D4", "S3 C2", "S4 C3"), SPADES, 0},
                {"N5", pos("H2 H6", "D5 H3", "C2 S2", "S3 C3"), CLUBS, 0},
                {"N6", pos("D2 C4", "S2 C5", "D3 S3", "D4 C6"), NOTRUMP, 0},
                {"N7", pos("S2 CA D2", "SA D3 D4", "D6 C3 C5", "D5 C4 C6"), NOTRUMP, 0},
                {"N8", pos("S2 CA H2", "D2 S4 H3", "SK D4 D6", "SQ C2 C4"), HEARTS, 0},
                {"N9", pos("S2 C2 C3", "S3 C4 D4", "D2 S6 S7", "D6 S4 S5"), HEARTS, 0},
                {"N10", pos("S2 C2 C3", "S3 C4 C5", "D2 S6 S7", "D6 S4 S5"), HEARTS, 0},
                {"N11", pos("H2 H6 H7", "C2 S5 S6", "D6 D7 D8", "D2 D3 D4"), NOTRUMP, 2},
        };
        boolean allOk = true;
        String detail = "";
        for (Object[] s : small) {
            String name = (String) s[0];
            long[] h = (long[]) s[1];
            int t = (Integer) s[2];
            int leader = (Integer) s[3];
            for (int leader2 : new int[]{leader}) {
                int got = tricks(h, t, leader2, side(0, 1));
                int want = naive(h, t, leader2, side(0, 1));
                if (got != want) {
                    allOk = false;
                    detail = name + " got=" + got + " naive=" + want;
                }
            }
        }
        checkTrue("N1.naive-matches-small-positions", allOk, detail);

        int limit = slow ? deals.size() : Math.min(12, deals.size());
        int checked = 0;
        boolean dealOk = true;
        String bad = "";
        for (int i = 0; i < limit; i++) {
            long[] h = deals.get(i)[0];
            int got = tricks(h, NOTRUMP, 0, side(0, 1));
            int want = naive(h, NOTRUMP, 0, side(0, 1));
            if (got != want) {
                dealOk = false;
                bad = "#" + i + " got=" + got + " naive=" + want;
                break;
            }
            checked++;
        }
        checkTrue("N2.naive-matches-notrump-deals (" + checked + ")", dealOk, bad);

        if (checked < deals.size()) {
            System.out.println("NOTE naive full-deal cross-check limited to " + checked
                    + " deals (pass --slow for all " + deals.size() + ")");
        } else {
            System.out.println("NOTE naive cross-checked every deal");
        }
    }

    // ------------------------------------------------------------------ naive solver

    /**
     * Deliberately naive minimax: no alpha-beta, no transposition table, plain recursion over
     * every legal play. Returns the number of tricks the two seats in {@code side} take from
     * the position with both sides playing optimally. Shares no code with the real solver.
     */
    static int naive(long[] handsIn, int trump, int leader, int sideMask) {
        long[] hands = handsIn.clone();
        return naiveRec(hands, trump, leader, -1, -1, -1, 0, sideMask);
    }

    /**
     * @param leader   seat that plays next
     * @param leadSuit suit led this trick, or -1 when this player leads
     * @param winCard  card currently winning the trick, or -1
     * @param winSeat  seat currently winning, or -1
     * @param len      cards already played to this trick
     */
    private static int naiveRec(long[] hands, int trump, int leader, int leadSuit,
                                int winCard, int winSeat, int len, int sideMask) {
        if (hands[0] == 0 && hands[1] == 0 && hands[2] == 0 && hands[3] == 0) return 0;

        int[] moves = naiveMoves(hands, leader, leadSuit);
        boolean maximiser = ((sideMask >> leader) & 1) != 0;
        int best = maximiser ? -1 : 99;

        for (int card : moves) {
            hands[leader] &= ~(1L << card);
            int nl = leadSuit;
            int nc = winCard;
            int nw = winSeat;
            if (nl < 0) {
                nl = card / 13;
                nc = card;
                nw = leader;
            } else if (naiveBeats(card, nc, nl, trump)) {
                nc = card;
                nw = leader;
            }
            int val;
            if (len == 3) {
                val = (naiveSeatSide(nw, sideMask) ? 1 : 0)
                        + naiveRec(hands, trump, nw, -1, -1, -1, 0, sideMask);
            } else {
                val = naiveRec(hands, trump, (leader + 1) & 3, nl, nc, nw, len + 1, sideMask);
            }
            hands[leader] |= 1L << card;
            if (maximiser) {
                if (val > best) best = val;
            } else {
                if (val < best) best = val;
            }
        }
        return best;
    }

    private static boolean naiveSeatSide(int seat, int sideMask) {
        return ((sideMask >> seat) & 1) != 0;
    }

    private static int[] naiveMoves(long[] hands, int seat, int leadSuit) {
        long hand = hands[seat];
        int[] buf = new int[13];
        int n = 0;
        if (leadSuit >= 0) {
            for (int c = 0; c < 52; c++) {
                if ((hand & (1L << c)) != 0L && c / 13 == leadSuit) buf[n++] = c;
            }
            if (n > 0) return trim(buf, n);
        }
        n = 0;
        for (int c = 0; c < 52; c++) {
            if ((hand & (1L << c)) != 0L) buf[n++] = c;
        }
        return trim(buf, n);
    }

    private static int[] trim(int[] buf, int n) {
        int[] out = new int[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    private static boolean naiveBeats(int card, int winCard, int leadSuit, int trump) {
        if (winCard < 0) return true;
        boolean tc = trump <= 3 && card / 13 == trump;
        boolean tw = trump <= 3 && winCard / 13 == trump;
        if (tc != tw) return tc;
        if (tc) return (card % 13) > (winCard % 13);
        if (card / 13 != leadSuit) return false;
        if (winCard / 13 != leadSuit) return true;
        return (card % 13) > (winCard % 13);
    }
}

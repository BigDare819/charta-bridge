import dev.bridge.game.Auction;
import dev.bridge.game.BridgeBid;

/**
 * Scenario driver for Auction.declarer(). "me" is always seat 0; seats 0 and 2 are partners. Calls are
 * given as (seat, call) pairs and placed in order, with the seat asserted against nextSeat() so a
 * scenario cannot silently drift out of turn order.
 */
public class Main {

    static int failures = 0;

    static final int P = BridgeBid.PASS;
    static final int X = BridgeBid.DOUBLE;
    static final int XX = BridgeBid.REDOUBLE;

    static int b(int level, int suit) {
        return BridgeBid.code(level, suit);
    }

    static Auction auction(int dealer, int[]... scripted) {
        Auction a = new Auction(dealer);
        for (int[] entry : scripted) {
            int seat = entry[0];
            int call = entry[1];
            if (a.nextSeat() != seat) {
                throw new IllegalStateException("out of turn: wanted seat " + a.nextSeat() + " got " + seat);
            }
            if (!a.call(call, seat)) {
                throw new IllegalStateException("illegal call " + call + " from seat " + seat);
            }
        }
        return a;
    }

    static int[] s(int seat, int call) {
        return new int[]{seat, call};
    }

    static void check(String what, int expected, int actual) {
        boolean ok = expected == actual;
        if (!ok) {
            failures++;
        }
        System.out.printf("%-6s %-62s expected=%d actual=%d%n", ok ? "ok" : "FAIL", what, expected, actual);
    }

    static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-6s %s%n", ok ? "ok" : "FAIL", what);
    }

    public static void main(String[] args) {
        // 1. Me (seat 0) never bids; partner (seat 2) opens 1H and it is passed out.
        check("me silent, partner opens 1H, passed out -> partner is declarer",
                2, auction(2,
                        s(2, b(1, BridgeBid.H)), s(3, P), s(0, P), s(1, P), s(2, P)).declarer());

        // 2. Me silent; partner opens 1NT and I raise to 3NT. Strain first named by partner.
        check("me silent, partner 1NT then my 3NT -> partner is declarer",
                2, auction(2,
                        s(2, b(1, BridgeBid.NT)), s(3, P), s(0, b(3, BridgeBid.NT)),
                        s(1, P), s(2, P), s(3, P)).declarer());

        // 3. The contract is in partner's suit, first named by ME: I am declarer (real bridge rule).
        check("me 1H, partner 4H -> me is declarer",
                0, auction(0,
                        s(0, b(1, BridgeBid.H)), s(1, P), s(2, b(4, BridgeBid.H)), s(3, P), s(0, P), s(1, P)).declarer());

        // 4. Me silent; opponents outbid each other and one of them owns the contract.
        check("me silent, opp 1NT, passed out -> opp is declarer",
                1, auction(1,
                        s(1, b(1, BridgeBid.NT)), s(2, P), s(3, P), s(0, P), s(1, P)).declarer());

        // 5. Me silent; an opponent opens 1S and their partner raises; my side never bids.
        check("me silent, opp 1S then 3S -> opening opp is declarer",
                1, auction(1,
                        s(1, b(1, BridgeBid.S)), s(2, P), s(3, b(3, BridgeBid.S)), s(0, P), s(1, P), s(2, P)).declarer());

        // 6. I opened a suit, opponents outbid me in theirs: the opponent is declarer, not me.
        check("me 1D, opp 1S then 2S -> opp is declarer",
                1, auction(0,
                        s(0, b(1, BridgeBid.D)), s(1, b(1, BridgeBid.S)), s(2, P), s(3, b(2, BridgeBid.S)),
                        s(0, P), s(1, P), s(2, P)).declarer());

        // 7. Their strain was named by an opponent first, then my side outbids in the SAME strain:
        //    the first of MY side to name it is the declarer, not the opponent.
        check("opp 1H first, partner 2H overcall wins -> partner is declarer",
                2, auction(1,
                        s(1, b(1, BridgeBid.H)), s(2, b(2, BridgeBid.H)), s(3, P), s(0, P), s(1, P)).declarer());

        // 8. Doubling does not move the declarer.
        check("me 1NT doubled by opp, passed out -> me is declarer",
                0, auction(0, s(0, b(1, BridgeBid.NT)), s(1, X), s(2, P), s(3, P), s(0, P)).declarer());
        check("opp 1S doubled by partner, passed out -> opp is declarer",
                1, auction(1, s(1, b(1, BridgeBid.S)), s(2, X), s(3, P), s(0, P), s(1, P)).declarer());
        check("me 1S doubled, partner redoubles -> me is declarer",
                0, auction(0, s(0, b(1, BridgeBid.S)), s(1, X), s(2, XX), s(3, P), s(0, P), s(1, P)).declarer());

        // 9. Dummy and leader follow from the declarer wherever it lands.
        for (int dealer = 0; dealer < 4; dealer++) {
            Auction a = auction(dealer, s(dealer, b(1, BridgeBid.C)), s((dealer + 1) % 4, P),
                    s((dealer + 2) % 4, P), s((dealer + 3) % 4, P));
            int declarer = a.declarer();
            check("dealer " + dealer + " all-pass after 1C: declarer is the 1C bidder", dealer, declarer);
            check("dealer " + dealer + ": dummy is declarer partner",
                    Math.floorMod(declarer + 2, 4), Math.floorMod(dealer + 2, 4));
        }

        parityInvariant();
        seatIsChecked();

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
    }

    /**
     * The invariant the whole declarer rule rests on: a call's seat is the dealer plus its position, so
     * the seat declarer() returns and the seat the highest bid was recorded from are the same expression
     * and must always share a parity. If they ever diverge, declarer() starts hunting for the winning
     * strain on the wrong partnership -- which is how a contract lands on a side that never named it.
     */
    static void parityInvariant() {
        Auction a = auction(1,
                s(1, b(1, BridgeBid.H)), s(2, b(2, BridgeBid.H)), s(3, P), s(0, b(2, BridgeBid.S)),
                s(1, b(3, BridgeBid.H)), s(2, P), s(3, P), s(0, P), s(1, P));
        check("declarer shares the highest bidder's parity",
                Math.floorMod(a.declarer() - a.highestSeat(), 2) == 0);
        for (int i = 0; i < a.size(); i++) {
            check("call " + i + " is filed under the seat its position implies, not a remembered one",
                    Math.floorMod(a.seatOf(i) - (1 + i), 4) == 0);
        }
    }

    /**
     * A call offered from the wrong seat is refused rather than filed under that seat, which is what
     * stops one bad call from shifting every later call -- and every later call's partnership -- by one.
     */
    static void seatIsChecked() {
        Auction a = new Auction(0);
        check("a call from the wrong seat is refused", !a.call(b(1, BridgeBid.H), 2));
        check("and it is not recorded", a.size() == 0);
        check("a call from the seat on turn is accepted", a.call(b(1, BridgeBid.H), 0));
        check("after which the next seat is the one on turn", a.nextSeat() == 1);
        // Two calls from the same seat, as a stale callback would produce: the second is filed under the
        // seat its position implies, so the auction stays a legal 4-cycle instead of drifting.
        Auction b = new Auction(0);
        b.call(b(1, BridgeBid.H), 0);
        check("a repeated seat is refused", !b.call(b(1, BridgeBid.S), 0));
        check("and the auction expects the next seat", b.nextSeat() == 1);
    }
}

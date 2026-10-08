import dev.bridge.game.BridgeDoubleDummy;

/**
 * Third opinion: a deliberately dumb minimax written in a different shape from both the solver
 * and Main's naive. Plays the trick out into an explicit array of four cards.
 */
public final class Brute {

    private static final boolean on = System.getenv("BTRACE") != null;

    private static String name(int c) {
        String[] s = {"C", "D", "H", "S"};
        String[] r = {"2", "3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K", "A"};
        return s[c / 13] + r[c % 13];
    }

    private static int[][] results;

    public static void main(String[] args) {
        String[] names = {"P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9", "P10", "P11"};
        long[][][] hands = {
                {Main.pos("C2 C3", "C4 C5", "D2 D3", "D4 D5"), null},
                {Main.pos("CA", "CK", "H2", "H3"), null},
                {Main.pos("H2 D4", "D2 C2", "S2 D5", "D3 D6"), null},
                {Main.pos("CA S2", "D2 D4", "S3 C2", "S4 C3"), null},
                {Main.pos("H2 H6", "D5 H3", "C2 S2", "S3 C3"), null},
                {Main.pos("D2 C4", "S2 C5", "D3 S3", "D4 C6"), null},
                {Main.pos("S2 CA D2", "SA D3 D4", "D6 C3 C5", "D5 C4 C6"), null},
                {Main.pos("S2 CA H2", "D2 S4 H3", "SK D4 D6", "SQ C2 C4"), null},
                {Main.pos("S2 C2 C3", "S3 C4 D4", "D2 S6 S7", "D6 S4 S5"), null},
                {Main.pos("S2 C2 C3", "S3 C4 C5", "D2 S6 S7", "D6 S4 S5"), null},
                {Main.pos("H2 H6 H7", "C2 S5 S6", "D6 D7 D8", "D2 D3 D4"), null},
        };
        int[] trumps = {0, 2, 0, 3, 0, 4, 4, 2, 2, 2, 4};
        int leader = 0;
        hands[10][0] = Main.pos("H2 H6 H7", "C2 S5 S6", "D6 D7 D8", "D2 D3 D4");
        int[] leaders = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2};

        String only = System.getenv("BONLY");
        for (int i = 0; i < names.length; i++) {
            if (only != null && i != Integer.parseInt(only)) continue;
            for (int side : new int[]{Main.side(0, 1), Main.side(2, 3)}) {
                long[] h = hands[i][0].clone();
                int trump = trumps[i];
                int lead = leaders[i];
                int brute = brute(h, trump, lead, side);
                int naive = Main.naive(hands[i][0].clone(), trump, lead, side);
                int solver = BridgeDoubleDummy.tricksFor(hands[i][0].clone(), trump, lead, side);
                System.out.println((brute == naive ? "  " : "!!") + names[i] + " side="
                        + Integer.toBinaryString(side) + " brute=" + brute + " naive=" + naive
                        + " solver=" + solver);
            }
        }
    }

    private static int brute(long[] hands, int trump, int leader, int sideMask) {
        return rec(hands, trump, leader, new int[4], 0, sideMask);
    }

    /** Continue from a half-played trick: {@code played[0..len-1]} already on the table. */
    static int mid(long[] hands, int trump, int seat, int[] played, int len, int sideMask) {
        return rec(hands, trump, seat, played, len, sideMask);
    }

    /**
     * @param played cards played to the current trick, index 0..3 in play order
     * @param len    how many of those are filled
     */
    private static int rec(long[] hands, int trump, int seat, int[] played, int len, int sideMask) {
        int alive = 0;
        for (int s = 0; s < 4; s++) if (hands[s] != 0L) alive++;
        if (alive == 0 && len == 0) return 0;
        if (len > 0 && (len == 4 || alive == 0)) {
            // Trick over: find the played card that beats every other, then hand the lead over.
            int winner = 0;
            for (int i = 1; i < len; i++) {
                if (beats(played[i], played[winner], played[0], trump)) winner = i;
            }
            int winSeat = (seat - len + winner + 4) & 3;
            if (on) System.out.println("   trick won by seat" + winSeat);
            int rest = rec(hands, trump, winSeat, new int[4], 0, sideMask);
            return ((sideMask >> winSeat) & 1) + rest;
        }
        long hand = hands[seat];
        if (hand == 0L) return rec(hands, trump, (seat + 1) & 3, played, len, sideMask);

        long legal = hand;
        if (len > 0) {
            long led = 0L;
            int suit = played[0] / 13;
            for (int c = suit * 13; c < suit * 13 + 13; c++) led |= 1L << c;
            long follow = hand & led;
            if (follow != 0L) legal = follow;
        }

        boolean max = ((sideMask >> seat) & 1) != 0;
        int best = max ? -1 : 999;
        for (int c = 0; c < 52; c++) {
            if ((legal & (1L << c)) == 0L) continue;
            hands[seat] &= ~(1L << c);
            played[len] = c;
            if (on) System.out.println("  ".repeat(len + 1) + "seat" + seat + " plays " + name(c)
                    + " (len " + len + ")");
            int v = rec(hands, trump, (seat + 1) & 3, played, len + 1, sideMask);
            if (on) System.out.println("  ".repeat(len + 1) + "  -> " + v);
            hands[seat] |= 1L << c;
            if (max) {
                if (v > best) best = v;
            } else {
                if (v < best) best = v;
            }
        }
        return best;
    }

    private static boolean beats(int card, int win, int led, int trump) {
        if (win < 0) return true;
        boolean ct = trump <= 3 && card / 13 == trump;
        boolean wt = trump <= 3 && win / 13 == trump;
        if (ct != wt) return ct;
        if (ct) return card % 13 > win % 13;
        if (card / 13 != led / 13) return false;
        if (win / 13 != led / 13) return true;
        return card % 13 > win % 13;
    }
}

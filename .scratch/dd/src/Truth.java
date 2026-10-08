import dev.bridge.game.BridgeDoubleDummy;

/** Prints solver vs independent naive minimax for every hand-verified small position. */
public final class Truth {
    public static void main(String[] args) {
        Object[][] small = {
                {"P1", Main.pos("C2 C3", "C4 C5", "D2 D3", "D4 D5"), BridgeDoubleDummy.CLUBS, 0},
                {"P2", Main.pos("CA", "CK", "H2", "H3"), BridgeDoubleDummy.HEARTS, 0},
                {"P3", Main.pos("H2 D4", "D2 C2", "S2 D5", "D3 D6"), BridgeDoubleDummy.CLUBS, 0},
                {"P4", Main.pos("CA S2", "D2 D4", "S3 C2", "S4 C3"), BridgeDoubleDummy.SPADES, 0},
                {"P5", Main.pos("H2 H6", "D5 H3", "C2 S2", "S3 C3"), BridgeDoubleDummy.CLUBS, 0},
                {"P6", Main.pos("D2 C4", "S2 C5", "D3 S3", "D4 C6"), BridgeDoubleDummy.NOTRUMP, 0},
                {"P7", Main.pos("S2 CA D2", "SA D3 D4", "D6 C3 C5", "D5 C4 C6"),
                        BridgeDoubleDummy.NOTRUMP, 0},
                {"P8", Main.pos("S2 CA H2", "D2 S4 H3", "SK D4 D6", "SQ C2 C4"),
                        BridgeDoubleDummy.HEARTS, 0},
                {"P9", Main.pos("S2 C2 C3", "S3 C4 D4", "D2 S6 S7", "D6 S4 S5"),
                        BridgeDoubleDummy.HEARTS, 0},
                {"P10", Main.pos("S2 C2 C3", "S3 C4 C5", "D2 S6 S7", "D6 S4 S5"),
                        BridgeDoubleDummy.HEARTS, 0},
                {"P11", Main.pos("H2 H6 H7", "C2 S5 S6", "D6 D7 D8", "D2 D3 D4"),
                        BridgeDoubleDummy.NOTRUMP, 2},
                {"S2", Main.pos("CA", "CK", "C2", "C3"), BridgeDoubleDummy.NOTRUMP, 0},
                {"S3", Main.pos("CA", "S2", "D2", "D3"), BridgeDoubleDummy.SPADES, 0},
        };
        for (Object[] s : small) {
            String name = (String) s[0];
            long[] h = (long[]) s[1];
            int t = (Integer) s[2];
            int leader = (Integer) s[3];
            int[] sides = {Main.side(0, 1), Main.side(2, 3), Main.side(1, 2)};
            for (int side : sides) {
                int got = BridgeDoubleDummy.tricksFor(h.clone(), t, leader, side);
                int want = Main.naive(h, t, leader, side);
                System.out.println((got == want ? "OK   " : "DIFF ") + name + " side="
                        + Integer.toBinaryString(side) + " solver=" + got + " naive=" + want);
            }
        }
    }
}

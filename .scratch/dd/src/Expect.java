/** Prints the independent naive value for exactly the positions the harness asserts on. */
public final class Expect {
    public static void main(String[] args) {
        Object[][] cases = {
                {"P1-0-1", Main.pos("C2 C3", "C4 C5", "D2 D3", "D4 D5"), 0, 0, 0, 1},
                {"P1-2-3", Main.pos("C2 C3", "C4 C5", "D2 D3", "D4 D5"), 0, 0, 2, 3},
                {"P2-0-1", Main.pos("CA", "CK", "H2", "H3"), 2, 0, 0, 1},
                {"P2-2-3", Main.pos("CA", "CK", "H2", "H3"), 2, 0, 2, 3},
                {"P3-0-1", Main.pos("H2 D4", "D2 C2", "S2 D5", "D3 D6"), 0, 0, 0, 1},
                {"P3-2-3", Main.pos("H2 D4", "D2 C2", "S2 D5", "D3 D6"), 0, 0, 2, 3},
                {"P4-0-1", Main.pos("CA S2", "D2 D4", "S3 C2", "S4 C3"), 3, 0, 0, 1},
                {"P4-2-3", Main.pos("CA S2", "D2 D4", "S3 C2", "S4 C3"), 3, 0, 2, 3},
                {"P5-0-1", Main.pos("H2 H6", "D5 H3", "C2 S2", "S3 C3"), 0, 0, 0, 1},
                {"P5-2-3", Main.pos("H2 H6", "D5 H3", "C2 S2", "S3 C3"), 0, 0, 2, 3},
                {"P6-0-1", Main.pos("D2 C4", "S2 C5", "D3 S3", "D4 C6"), 4, 0, 0, 1},
                {"P6-2-3", Main.pos("D2 C4", "S2 C5", "D3 S3", "D4 C6"), 4, 0, 2, 3},
                {"P7-0-1", Main.pos("S2 CA D2", "SA D3 D4", "D6 C3 C5", "D5 C4 C6"), 4, 0, 0, 1},
                {"P8-0-1", Main.pos("S2 CA H2", "D2 S4 H3", "SK D4 D6", "SQ C2 C4"), 2, 0, 0, 1},
                {"P8-2-3", Main.pos("S2 CA H2", "D2 S4 H3", "SK D4 D6", "SQ C2 C4"), 2, 0, 2, 3},
                {"P9-0-1", Main.pos("S2 C2 C3", "S3 C4 D4", "D2 S6 S7", "D6 S4 S5"), 2, 0, 0, 1},
                {"P10-0-1", Main.pos("S2 C2 C3", "S3 C4 C5", "D2 S6 S7", "D6 S4 S5"), 2, 0, 0, 1},
                {"P10-2-3", Main.pos("S2 C2 C3", "S3 C4 C5", "D2 S6 S7", "D6 S4 S5"), 2, 0, 2, 3},
                {"P11-2-3", Main.pos("H2 H6 H7", "C2 S5 S6", "D6 D7 D8", "D2 D3 D4"), 4, 2, 2, 3},
                {"P11-0-1", Main.pos("H2 H6 H7", "C2 S5 S6", "D6 D7 D8", "D2 D3 D4"), 4, 2, 0, 1},
                {"S2-a", Main.pos("C2", "C3", "C4", "C5"), 4, 0, 0, 1},
                {"S2-b", Main.pos("C2", "C3", "C4", "C5"), 4, 0, 2, 3},
                {"S3-a", Main.pos("CA", "S2", "D2", "D3"), 3, 0, 0, 1},
                {"S3-b", Main.pos("CA", "S2", "D2", "D3"), 3, 0, 1, 2},
                {"S4", Main.pos("C2 C3 D2 D3", "C4 C5 D4 D5",
                        "H2 H3 H4 H5", "H6 H7 H8 H9"), 4, 0, 0, 1},
        };
        for (Object[] c : cases) {
            String name = (String) c[0];
            long[] h = ((long[]) c[1]).clone();
            int trump = (Integer) c[2];
            int leader = (Integer) c[3];
            int side = Main.side((Integer) c[4], (Integer) c[5]);
            int want = Main.naive(h, trump, leader, side);
            int got = dev.bridge.game.BridgeDoubleDummy.tricksFor(h, trump, leader, side);
            System.out.println((got == want ? "OK   " : "DIFF ") + name + " naive=" + want
                    + " solver=" + got);
        }
    }
}

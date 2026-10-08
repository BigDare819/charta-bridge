import dev.bridge.game.BridgeDoubleDummy;

/** Focused single-position trace for the double-dummy solver. */
public final class Debug {

    private static int card(int suit, int rank) {
        return suit * 13 + (rank - 2);
    }

    private static int tok(String s) {
        int suit = switch (Character.toUpperCase(s.charAt(0))) {
            case 'C' -> 0;
            case 'D' -> 1;
            case 'H' -> 2;
            case 'S' -> 3;
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

    private static long[] pos(String s0, String s1, String s2, String s3) {
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

    private static int side(int a, int b) {
        return (1 << a) | (1 << b);
    }

    public static void main(String[] args) {
        long[] p4 = pos("CA S2", "D2 D4", "S3 C2", "S4 C3");
        BridgeDoubleDummy.trace(true);
        int got = BridgeDoubleDummy.tricksFor(p4, BridgeDoubleDummy.SPADES, 0, side(0, 1));
        System.err.println("side(0,1) = " + got + " want 1");
    }
}

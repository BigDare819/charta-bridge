import dev.bridge.game.BridgeDoubleDummy;

public class Debug {
    public static void main(String[] args) {
        long[] h = new long[4];
        h[0] = 1L << 0;   // C2
        h[1] = 1L << 1;   // C3
        h[2] = 1L << 2;   // C4
        h[3] = 1L << 3;   // C5
        try {
            int r = BridgeDoubleDummy.tricksFor(h, BridgeDoubleDummy.NOTRUMP, 0, 1 | 2);
            System.out.println("result=" + r + " nodes=" + BridgeDoubleDummy.lastNodes());
        } catch (Throwable t) {
            t.printStackTrace();
        }
        int[] w = BridgeDoubleDummy.windowFor(h, BridgeDoubleDummy.NOTRUMP, 0, 1 | 2);
        System.out.println("window max(0|1)=[" + w[0] + "," + w[1] + "]");
        int[] w2 = BridgeDoubleDummy.windowFor(h, BridgeDoubleDummy.NOTRUMP, 0, 4 | 8);
        System.out.println("window max(2|3)=[" + w2[0] + "," + w2[1] + "]");
        // two-card positions exercise the trick rollover
        long[] h2 = new long[4];
        h2[0] = (1L << 0) | (1L << 1);
        h2[1] = (1L << 2) | (1L << 3);
        h2[2] = (1L << 13) | (1L << 14);
        h2[3] = (1L << 15) | (1L << 16);
        System.out.println("two-trick=" + BridgeDoubleDummy.tricksFor(h2, BridgeDoubleDummy.NOTRUMP, 0, 1 | 2));
        System.out.println("nodes=" + BridgeDoubleDummy.lastNodes());

        // P1 from the harness: clubs led, four clubs per pair.
        long[] p1 = new long[4];
        p1[0] = (1L << 0) | (1L << 1);
        p1[1] = (1L << 2) | (1L << 3);
        p1[2] = (1L << 13) | (1L << 14);
        p1[3] = (1L << 15) | (1L << 16);
        BridgeDoubleDummy.trace(true);
        System.out.println("P1 0|1 = " + BridgeDoubleDummy.tricksFor(p1, 0, 0, 1 | 2)
                + " nodes=" + BridgeDoubleDummy.lastNodes());
        BridgeDoubleDummy.trace(false);
        System.out.println("P1 2|3 = " + BridgeDoubleDummy.tricksFor(p1, 0, 0, 4 | 8)
                + " nodes=" + BridgeDoubleDummy.lastNodes());
    }
}

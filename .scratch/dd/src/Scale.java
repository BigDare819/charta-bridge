import dev.bridge.game.BridgeDoubleDummy;
import java.util.Random;

/** Node counts for reduced deals, to expose how the search scales. */
public final class Scale {
    public static void main(String[] args) {
        for (int n = Integer.parseInt(System.getProperty("n0","3")); n <= Integer.parseInt(System.getProperty("n1","8")); n++) {
            Random rnd = new Random(1234 + n);
            int total = 4 * n;
            int[] deck = new int[52];
            for (int i = 0; i < 52; i++) deck[i] = i;
            for (int i = 51; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = deck[i];
                deck[i] = deck[j];
                deck[j] = t;
            }
            long[] hands = new long[4];
            for (int i = 0; i < total; i++) hands[i % 4] |= 1L << deck[i];
            long t0 = System.nanoTime();
            int a = BridgeDoubleDummy.tricksFor(hands.clone(), 4, 0, 0b0011);
            long nodesA = BridgeDoubleDummy.lastNodes();
            int b = BridgeDoubleDummy.tricksFor(hands.clone(), 4, 0, 0b1100);
            long nodesB = BridgeDoubleDummy.lastNodes();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("n=" + n + " tricks=" + n
                    + " a=" + a + " b=" + b + (a + b == n ? " ok" : " BAD")
                    + " nodes=" + (nodesA + nodesB) + " hits=" + (BridgeDoubleDummy.lastHits() + BridgeDoubleDummy.lastMisses() == 0 ? 0 : 0) + " " + ms + "ms");
        }
    }
}

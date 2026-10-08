import dev.bridge.game.BridgeDoubleDummy;
import java.util.Random;

/** Times full-deal solves so tuning can be measured instead of guessed. */
public final class Bench {
    public static void main(String[] args) {
        int deals = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        long seed = 0x5EED5EEDL;
        Random rnd = new Random(seed);
        int[] deck = new int[52];
        for (int i = 0; i < 52; i++) deck[i] = i;
        long worst = 0;
        long totalNodes = 0;
        for (int k = 0; k < deals; k++) {
            for (int i = 51; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = deck[i];
                deck[i] = deck[j];
                deck[j] = t;
            }
            long[] hands = new long[4];
            for (int i = 0; i < 52; i++) hands[i % 4] |= 1L << deck[i];
            int trump = k % 5;
            long start = System.nanoTime();
            int a = BridgeDoubleDummy.tricksFor(hands.clone(), trump, 0, 0b0011);
            boolean capA = BridgeDoubleDummy.lastWasCapped();
            int b = BridgeDoubleDummy.tricksFor(hands.clone(), trump, 0, 0b1100);
            boolean capB = BridgeDoubleDummy.lastWasCapped();
            long ms = (System.nanoTime() - start) / 1_000_000;
            long nodes = BridgeDoubleDummy.lastNodes();
            totalNodes += nodes;
            worst = Math.max(worst, ms);
            System.out.println("deal" + k + " trump=" + trump + " a=" + a + " b=" + b
                    + " sum=" + (a + b) + (a + b == 13 ? " ok" : " BAD")
                    + (capA || capB ? " CAPPED(" + capA + "," + capB + ")" : "")
                    + " " + ms + "ms hits=" + BridgeDoubleDummy.lastHits() + " miss=" + BridgeDoubleDummy.lastMisses());
        }
        System.out.println("worst=" + worst + "ms totalNodes=" + totalNodes);
    }
}

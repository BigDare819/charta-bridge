import dev.bridge.game.BridgeDoubleDummy;
import java.util.Random;

/** Solves exactly the one position Hard says is worst, with per-iteration node accounting. */
public final class One {
    public static void main(String[] args) {
        int deal = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int trump = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int leader = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        long cap = args.length > 3 ? Long.parseLong(args[3]) : 500_000_000L;
        System.setProperty("dd.cap", Long.toString(cap));
        Random rnd = new Random(0xBADF00DL);
        int[] deck = new int[52];
        long[] hands = null;
        for (int k = 0; k <= deal; k++) {
            for (int i = 0; i < 52; i++) deck[i] = i;
            for (int i = 51; i > 0; i--) { int j = rnd.nextInt(i + 1); int t = deck[i]; deck[i] = deck[j]; deck[j] = t; }
            hands = new long[4];
            for (int i = 0; i < 52; i++) hands[i % 4] |= 1L << deck[i];
        }
        long t0 = System.nanoTime();
        int a = BridgeDoubleDummy.tricksFor(hands.clone(), trump, leader, 0b0011);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("deal" + deal + " trump=" + trump + " leader=" + leader
                + " -> " + a + " nodes=" + BridgeDoubleDummy.lastNodes()
                + " capped=" + BridgeDoubleDummy.lastWasCapped() + " " + ms + "ms");
    }
}

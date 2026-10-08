import dev.bridge.game.BridgeDoubleDummy;
import java.util.Random;

/** Times the positions that actually hurt: the same deal/trump/leader set the validator walks. */
public final class Hard {
    public static void main(String[] args) {
        int deals = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Random rnd = new Random(0xBADF00DL);
        int[] deck = new int[52];
        long worst = 0;
        long total = 0;
        for (int k = 0; k < deals; k++) {
            for (int i = 0; i < 52; i++) deck[i] = i;
            for (int i = 51; i > 0; i--) { int j = rnd.nextInt(i + 1); int t = deck[i]; deck[i] = deck[j]; deck[j] = t; }
            long[] hands = new long[4];
            for (int i = 0; i < 52; i++) hands[i % 4] |= 1L << deck[i];
            StringBuilder sb = new StringBuilder("deal" + k + ": ");
            for (int trump = 0; trump <= 4; trump++) {
                for (int leader = 0; leader < 4; leader++) {
                    long t0 = System.nanoTime();
                    int a = BridgeDoubleDummy.tricksFor(hands.clone(), trump, leader, 0b0011);
                    boolean cap = BridgeDoubleDummy.lastWasCapped();
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    long nodes = BridgeDoubleDummy.lastNodes();
                    total += nodes;
                    worst = Math.max(worst, ms);
                    sb.append("t").append(trump).append("L").append(leader).append("=").append(a)
                      .append("/").append(nodes).append("n").append(cap ? "C" : "").append(" ").append(ms).append("ms | ");
                }
            }
            System.out.println(sb);
        }
        System.out.println("worst=" + worst + "ms totalNodes=" + total);
    }
}

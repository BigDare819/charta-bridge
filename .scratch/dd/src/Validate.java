import dev.bridge.game.BridgeDoubleDummy;
import java.util.Random;

/**
 * The one invariant a double-dummy solver cannot break: from the same position the two sides' optimal
 * totals add up to the number of tricks left. Checked over real deals, every strain, both directions,
 * with the node cap lifted so a capped (and therefore wrong) answer cannot pass silently.
 *
 * <p>This is the check that guards the move list: anything that quietly prunes a move the optimal line
 * needs shows up here as a sum that is not thirteen.
 */
public final class Validate {

    public static void main(String[] args) {
        int deals = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        Random rnd = new Random(0xBADF00DL);
        int[] deck = new int[52];
        int bad = 0;
        int checked = 0;
        long worst = 0;
        for (int k = 0; k < deals; k++) {
            for (int i = 0; i < 52; i++) deck[i] = i;
            for (int i = 51; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = deck[i];
                deck[i] = deck[j];
                deck[j] = t;
            }
            long[] hands = new long[4];
            for (int i = 0; i < 52; i++) hands[i % 4] |= 1L << deck[i];
            for (int trump = 0; trump <= 4; trump++) {
                for (int leader = 0; leader < 4; leader++) {
                    long start = System.nanoTime();
                    int a = BridgeDoubleDummy.tricksFor(hands.clone(), trump, leader, 0b0011);
                    boolean capA = BridgeDoubleDummy.lastWasCapped();
                    int b = BridgeDoubleDummy.tricksFor(hands.clone(), trump, leader, 0b1100);
                    boolean capB = BridgeDoubleDummy.lastWasCapped();
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    worst = Math.max(worst, ms);
                    checked++;
                    boolean ok = a + b == 13 && !capA && !capB;
                    if (!ok) {
                        bad++;
                        System.out.println("BAD deal" + k + " trump=" + trump + " leader=" + leader
                                + " a=" + a + " b=" + b + " sum=" + (a + b)
                                + " capped=" + capA + "/" + capB);
                    }
                }
            }
            System.out.println("deal" + k + " done");
        }
        System.out.println("checked=" + checked + " bad=" + bad + " worst=" + worst + "ms");
        if (bad > 0) System.exit(1);
        System.out.println("RESULT: zero-sum holds everywhere");
    }
}

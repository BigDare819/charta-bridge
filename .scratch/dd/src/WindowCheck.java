import dev.bridge.game.BridgeDoubleDummy;
import java.util.Random;

/**
 * Checks the proved window against an independent brute force on many small positions.
 *
 * <p>A window that is wrong in either direction silently corrupts every answer the solver gives, and
 * unlike a wrong move it never shows up as an obviously silly play. So it is tested directly: for each
 * position the lower bound must not exceed the true value and the upper bound must not fall below it.
 */
public final class WindowCheck {

    private static boolean beats(int card, int win, int lead, int trump) {
        if (card / 13 == trump) {
            if (win / 13 == trump) return (card % 13) > (win % 13);
            return true;
        }
        if (win / 13 == trump) return false;
        if (card / 13 != lead) return false;
        if (win / 13 != lead) return true;
        return (card % 13) > (win % 13);
    }

    private static int trumpG;
    private static int sideG;
    private static int bad;
    private static int checked;

    private static int rec(long[] hands, int trump, int seat, int lead, int win, int winSeat,
                           int banked, int side, int tricks, int ply) {
        if ((hands[0] | hands[1] | hands[2] | hands[3]) == 0L) return banked;
        // Every position the search can reach gets its window checked, not just the opening one.
        int[] w = BridgeDoubleDummy.windowAt(hands.clone(), trumpG, sideG, tricks, ply, seat, lead, win,
                winSeat, banked);
        long mine = hands[seat];
        int best = ((side >> seat) & 1) != 0 ? -1 : tricks + 1;
        for (int c = 0; c < 52; c++) {
            if ((mine & (1L << c)) == 0L) continue;
            if (lead >= 0 && (mine & (0x1FFFL << (13 * lead))) != 0L && c / 13 != lead) continue;
            int nLead = lead, nWin = win, nWinSeat = winSeat;
            if (nLead < 0) {
                nLead = c / 13;
                nWin = c;
                nWinSeat = seat;
            } else if (beats(c, nWin, nLead, trump)) {
                nWin = c;
                nWinSeat = seat;
            }
            hands[seat] &= ~(1L << c);
            int v;
            if (ply % 4 == 3) {
                int nb = banked + (((side >> nWinSeat) & 1) != 0 ? 1 : 0);
                v = rec(hands, trump, nWinSeat, -1, -1, -1, nb, side, tricks, ply + 1);
            } else {
                v = rec(hands, trump, (seat + 1) & 3, nLead, nWin, nWinSeat, banked, side, tricks,
                        ply + 1);
            }
            hands[seat] |= 1L << c;
            if (((side >> seat) & 1) != 0) {
                if (v > best) best = v;
            } else {
                if (v < best) best = v;
            }
        }
        checked++;
        if (best >= 0 && (w[0] > best || w[1] < best)) {
            bad++;
            if (bad <= 10) {
                StringBuilder sb = new StringBuilder();
                for (int s = 0; s < 4; s++) {
                    sb.append("h").append(s).append('=');
                    for (int c = 0; c < 52; c++) {
                        if ((hands[s] & (1L << c)) != 0L) {
                            sb.append("CDHS".charAt(c / 13)).append("23456789TJQKA".charAt(c % 13));
                        }
                    }
                    sb.append(' ');
                }
                System.out.println("BAD trump=" + trumpG + " side=" + Integer.toBinaryString(sideG)
                        + " ply=" + ply + " seat=" + seat + " lead=" + lead + " win=" + win
                        + " winSeat=" + winSeat + " banked=" + banked
                        + " window=[" + w[0] + "," + w[1] + "] truth=" + best + "  " + sb);
            }
        }
        return best;
    }

    public static void main(String[] args) {
        int[] sides = {0b0011, 0b1100, 0b0101, 0b1010, 0b1001, 0b0110};
        Random rnd = new Random(99);
        for (int n = 1; n <= 4; n++) {
            for (int deal = 0; deal < 25; deal++) {
                int[] deck = new int[52];
                for (int i = 0; i < 52; i++) deck[i] = i;
                for (int i = 51; i > 0; i--) {
                    int j = rnd.nextInt(i + 1);
                    int t = deck[i];
                    deck[i] = deck[j];
                    deck[j] = t;
                }
                long[] hands = new long[4];
                for (int i = 0; i < 4 * n; i++) hands[i % 4] |= 1L << deck[i];
                for (int trump = 0; trump <= 4; trump++) {
                    for (int side : sides) {
                        trumpG = trump;
                        sideG = side;
                        rec(hands.clone(), trump, 0, -1, -1, -1, 0, side, n, 0);
                    }
                }
            }
        }
        System.out.println("checked=" + checked + " bad=" + bad);
    }
}

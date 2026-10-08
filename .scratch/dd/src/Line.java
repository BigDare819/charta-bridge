/** Evaluate one forced line in P3 to find where the brute and the hand analysis diverge. */
public final class Line {
    public static void main(String[] args) {
        long[] h = Main.pos("H2 D4", "D2 C2", "S2 D5", "D3 D6");
        int side = Main.side(0, 1);
        // Trick 1: seat0 leads H2, seat1 discards D2 (keeping the C2 ruff for trick 2).
        h[0] &= ~(1L << 26);
        h[1] &= ~(1L << 13);
        int[] played = new int[4];
        played[0] = 26;
        played[1] = 13;
        int v = Brute.mid(h, 0, 2, played, 2, side);
        System.out.println("after H2,D2 remainder for side 0|1 = " + v);
        System.out.println("trick 1 will be won by seat0 unless someone beats it; total would be "
                + (v + 1) + " if seat0 wins it, " + v + " otherwise");
    }
}

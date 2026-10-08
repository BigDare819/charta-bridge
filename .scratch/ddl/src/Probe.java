public final class Probe {
    public static void main(String[] args) {
        long opp = (1L << 13) | (1L << 14) | (1L << 15) | (1L << 16); // D2 D3 D4 D5
        int base = 0;
        long oppSuit = opp & (0x1FFFL << base);
        System.out.println("oppSuit=" + Long.toBinaryString(oppSuit));
        int oppTop = oppSuit == 0L ? -1
                : Long.numberOfTrailingZeros(Long.highestOneBit(oppSuit)) - base;
        System.out.println("oppTop=" + oppTop);
    }
}

public final class Probe2 {
    public static void main(String[] args) {
        long suit = (0x1FFFL << (13 * 0));
        long theirs = 15L << 13;
        System.out.println("suit=" + Long.toBinaryString(suit));
        System.out.println("theirs=" + Long.toBinaryString(theirs));
        System.out.println("and=" + Long.toBinaryString(theirs & suit));
        long mine = 0b11L;
        System.out.println("mine&suit=" + Long.toBinaryString(mine & suit));
    }
}

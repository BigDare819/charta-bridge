public final class Probe3 {
    public static void main(String[] args) {
        for (int t = 0; t < 5; t++) {
            long suit = (t >= 0 && t <= 3) ? (0x1FFFL << (13 * t)) : 0L;
            System.out.println("t=" + t + " suit=" + suit + " bin=" + Long.toBinaryString(suit));
        }
    }
}

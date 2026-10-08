package dev.bridge.game;

/**
 * Standalone copy of the parts of the real BridgeBid that the auction needs, so the auction's seat
 * identity can be exercised with a plain javac and no Minecraft on the classpath. The encoding is
 * duplicated on purpose: if the real one ever changes, these tests have to be re-checked anyway.
 */
public final class BridgeBid {

    public static final int PASS = 0;
    public static final int DOUBLE = 1;
    public static final int REDOUBLE = 2;
    public static final int FIRST_CONTRACT = 3;
    public static final int STRAINS = 5;
    public static final int NOTRUMP = 4;
    public static final int MAX_LEVEL = 7;
    public static final int MAX_CODE = FIRST_CONTRACT + MAX_LEVEL * STRAINS - 1;

    /** Strain shortcuts, so scenarios read as bridge rather than as arithmetic. */
    public static final int C = 0;
    public static final int D = 1;
    public static final int H = 2;
    public static final int S = 3;
    public static final int NT = NOTRUMP;

    private BridgeBid() {
    }

    public static int code(int level, int strain) {
        return FIRST_CONTRACT + (level - 1) * STRAINS + strain;
    }

    public static int level(int code) {
        return code < FIRST_CONTRACT ? 0 : (code - FIRST_CONTRACT) / STRAINS + 1;
    }

    public static int strain(int code) {
        return code < FIRST_CONTRACT ? NOTRUMP : (code - FIRST_CONTRACT) % STRAINS;
    }

    public static boolean isContract(int code) {
        return code >= FIRST_CONTRACT && code <= MAX_CODE;
    }
}

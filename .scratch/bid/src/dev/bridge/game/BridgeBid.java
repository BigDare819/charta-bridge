package dev.bridge.game;

import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.card.Suit;

/**
 * Standalone copy of the real encoding, with the Minecraft {@code Component} bits dropped. The encoding
 * is duplicated on purpose: if the real one changes, these tests have to be re-checked anyway.
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

    public static Suit suit(int code) {
        return switch (strain(code)) {
            case 0 -> Suits.CLUBS;
            case 1 -> Suits.DIAMONDS;
            case 2 -> Suits.HEARTS;
            case 3 -> Suits.SPADES;
            default -> null;
        };
    }

    public static int strainOf(Suit suit) {
        if (suit == null) {
            return NOTRUMP;
        }
        return switch (Suits.getLocation(suit).getPath()) {
            case "clubs" -> 0;
            case "diamonds" -> 1;
            case "hearts" -> 2;
            case "spades" -> 3;
            default -> NOTRUMP;
        };
    }

    public static String label(int code) {
        if (code == PASS) {
            return "Pass";
        }
        if (code == DOUBLE) {
            return "X";
        }
        if (code == REDOUBLE) {
            return "XX";
        }
        return level(code) + STRAIN_LABELS[strain(code)];
    }

    private static final String[] STRAIN_LABELS = {"C", "D", "H", "S", "NT"};
}

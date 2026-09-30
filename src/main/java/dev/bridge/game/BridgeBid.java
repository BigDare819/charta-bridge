package dev.bridge.game;

import dev.bridge.BridgeMod;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The wire and storage format for one call in the auction.
 *
 * <p>A call is a single small integer so the whole auction can ride Charta's existing container-data
 * sync instead of needing a packet per call:
 *
 * <pre>
 *   0                      pass
 *   1                      double
 *   2                      redouble
 *   3 + (level - 1) * 5 + strain     a contract, level 1..7
 * </pre>
 *
 * <p>{@code strain} runs {@code clubs, diamonds, hearts, spades, notrump} — i.e. <b>ascending</b> rank
 * order, so "is this contract higher than that one" is the plain integer comparison
 * {@code a &gt; b} and nothing else. Notrump is {@link #NOTRUMP}, and {@link #suit(int)} maps a strain
 * back to a registered suit for everything that needs a real suit (or {@code null} for notrump).
 */
public final class BridgeBid {

    public static final int PASS = 0;
    public static final int DOUBLE = 1;
    public static final int REDOUBLE = 2;

    /** First contract code, i.e. one club. */
    public static final int FIRST_CONTRACT = 3;

    /** Levels 1..7 times five strains, from clubs up to notrump. */
    public static final int STRAINS = 5;
    public static final int NOTRUMP = 4;
    public static final int MAX_LEVEL = 7;

    /** Seven notrump: the highest call that exists. */
    public static final int MAX_CODE = FIRST_CONTRACT + MAX_LEVEL * STRAINS - 1;

    private BridgeBid() {
    }

    public static int code(int level, int strain) {
        return FIRST_CONTRACT + (level - 1) * STRAINS + strain;
    }

    /**
     * Level of a contract code.
     *
     * <p>The {@code code < FIRST_CONTRACT} guard is not decoration: {@code PASS}, {@code DOUBLE} and
     * {@code REDOUBLE} are 0/1/2, so the raw arithmetic would give {@code (0 - 3) % 5 == -3} for a pass.
     * A negative strain then falls through {@link #glyph} to notrump and quietly looks right, which is
     * exactly the kind of thing that hides until a caller does real arithmetic on it.
     *
     * @return the level, or {@code 0} when {@code code} is not a contract
     */
    public static int level(int code) {
        return code < FIRST_CONTRACT ? 0 : (code - FIRST_CONTRACT) / STRAINS + 1;
    }

    /** Strain of a contract code, or {@link #NOTRUMP} when {@code code} is not a contract. */
    public static int strain(int code) {
        return code < FIRST_CONTRACT ? NOTRUMP : (code - FIRST_CONTRACT) % STRAINS;
    }

    public static boolean isContract(int code) {
        return code >= FIRST_CONTRACT && code <= MAX_CODE;
    }

    /** The strain's suit, or {@code null} when the contract is in notrump. */
    @Nullable
    public static Suit suit(int code) {
        return switch (strain(code)) {
            case 0 -> Suits.CLUBS;
            case 1 -> Suits.DIAMONDS;
            case 2 -> Suits.HEARTS;
            case 3 -> Suits.SPADES;
            default -> null;
        };
    }

    /**
     * A call as text: {@code Pass}, {@code X}, {@code XX}, or a level followed by the suit symbol.
     *
     * <p>The level digit comes from the vanilla font that {@code bridge:suit} falls through to and the
     * symbol from {@code bridge:suit} itself, so the two share a baseline. The symbol is left untinted:
     * unlike the played-card messages, which colour a card's <em>name</em> after the deck, the suit
     * sprites carry their own colour (see {@code tools/make-suit-font.ps1}) and a tint on top of it
     * would just muddy them.
     */
    public static Component name(int code) {
        if (code == PASS) {
            return Component.translatable("bid.bridge.pass");
        }
        if (code == DOUBLE) {
            return Component.translatable("bid.bridge.double");
        }
        if (code == REDOUBLE) {
            return Component.translatable("bid.bridge.redouble");
        }
        return Component.literal(String.valueOf(level(code))).withStyle(BridgeMod.SUITS).append(symbol(code));
    }

    /**
     * Just the strain part, e.g. {@code ♥} or {@code NT}.
     *
     * <p>The sprite carries its own colour, so nothing here is tinted after the deck; that is why this
     * takes only a code.
     */
    public static Component symbol(int code) {
        return Component.literal(glyph(strain(code))).withStyle(BridgeMod.SUITS);
    }

    /** The glyph for a strain, in the {@code bridge:suit} font. */
    public static String glyph(int strain) {
        return switch (strain) {
            case 0 -> "\u2663";   // clubs
            case 1 -> "\u2666";   // diamonds
            case 2 -> "\u2665";   // hearts
            case 3 -> "\u2660";   // spades
            default -> "\uE000";  // notrump
        };
    }

    /** The strain of a suit, for when a real suit has to be turned into a bidding strain. */
    public static int strainOf(@Nullable Suit suit) {
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

}

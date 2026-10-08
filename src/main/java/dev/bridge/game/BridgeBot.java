package dev.bridge.game;

import dev.lucaargolo.charta.common.game.impl.AutoPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.Nullable;

/**
 * The AI that fills the empty chairs of a {@link BridgeGame}.
 *
 * <p>It is a plain {@link AutoPlayer} — Charta already knows how to drive one: {@code AutoPlayer.tick}
 * waits a randomised beat whenever it is that player's turn and then asks the game for
 * {@code getBestPlay}, which is where {@link BridgeBidding} and {@link BridgePlay} do the thinking. All
 * this class adds is a readable identity, the strength it was built at, and the {@link #equals} quirk
 * explained below.
 *
 * <h2>Strength</h2>
 *
 * <p>Held on the bot rather than read from the game option: the option is on the game and the game asks
 * the <em>bot</em> to decide, so keeping the two apart would make every decision walk back to a mutable
 * setting, and it would not survive the client rebuilding its roster from the option bytes. A bot is
 * built from a strength and keeps it for the board.
 *
 * <h2>Why {@code equals} is deliberately lopsided</h2>
 *
 * <p>{@code CardTableBlockEntity.serverTick} keeps a game alive with
 * {@code if (!seatedPlayers.containsAll(game.getPlayers())) game.endGame();}. Seated players come
 * from {@code getOrderedPlayers()} and therefore never contain a bot, so with a naive identity
 * {@code equals} every game that pads a chair would be killed on the first tick.
 *
 * <p>{@code contains(o)} is implemented as {@code indexOf(o)} and compares {@code o.equals(element)},
 * so it is the <em>argument's</em> equals that runs. Returning {@code true} for every non-bot makes
 * {@code contains(bot)} succeed against the first real player and the table stays alive.
 *
 * <p>The one thing this breaks is {@code playerList.indexOf(bot)}, which then also answers with the
 * first real player's seat. {@link BridgeGame} therefore never looks a bot up by {@code indexOf} —
 * see {@link BridgeGame#getSeat}.
 */
public class BridgeBot extends AutoPlayer {

    private final int number;
    private final DyeColor color;
    private final BridgeStrength strength;

    public BridgeBot(int number) {
        this(number, BridgeStrength.NORMAL);
    }

    public BridgeBot(int number, BridgeStrength strength) {
        // Charta's intelligence only drives the thinking delay: 0 is the slow end of its lerp and 1 the
        // fast one. A stronger bot answers sooner, which reads as confidence and keeps a HARD table
        // moving; the delay was never the difficulty.
        super(strength == BridgeStrength.HARD ? 0.85f : strength == BridgeStrength.NORMAL ? 0.6f : 0.35f);
        this.number = number;
        this.strength = strength;
        this.color = switch (number % 3) {
            case 1 -> DyeColor.ORANGE;
            case 2 -> DyeColor.LIGHT_BLUE;
            default -> DyeColor.LIME;
        };
    }

    /** 1-based, only used to tell the bots apart in chat and on the table labels. */
    public int getNumber() {
        return number;
    }

    /** How hard this bot thinks; see {@link BridgeStrength}. */
    public BridgeStrength getStrength() {
        return strength;
    }

    @Override
    public Component getName() {
        return Component.translatable("player.bridge.bot", number);
    }

    @Override
    public DyeColor getColor() {
        return color;
    }

    @Override
    public int getId() {
        // Negative ids are the wire format for "not a real entity"; the client turns them back into
        // a bot in GameType.getGameForMenu, and BridgeGame rebuilds it as a BridgeBot.
        return -1 - number;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        return !(other instanceof BridgeBot);
    }

    @Override
    public int hashCode() {
        // Identity hash: two bots in the same roster must not collapse into one map key.
        return System.identityHashCode(this);
    }

}

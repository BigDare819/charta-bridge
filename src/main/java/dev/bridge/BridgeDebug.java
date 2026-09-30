package dev.bridge;

import dev.bridge.game.BridgeGame;
import dev.bridge.game.BridgeMenu;
import dev.bridge.game.BridgeScreen;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Rarity;
import org.jetbrains.annotations.Nullable;

/**
 * Dev-only shortcut that drops a self-playing Contract Bridge table onto the screen as soon as the
 * client is in a world. It exists because reaching the real thing by hand needs a deck, a cloth, a
 * multi-block table and four occupied chairs before the table screen even opens.
 *
 * <p>Enabled with the {@code BRIDGE_DEBUG_SCREEN=1} environment variable:
 *
 * <pre>cd D:\deepseekharness\charta-bridge
 * $env:BRIDGE_DEBUG_SCREEN=1; .\gradlew.bat runClient</pre>
 *
 * <p>The menu is built from entity ids alone ({@code -2/-3/-4} stand for bots), which is exactly the
 * path the real client takes, so the layout, the seat rotation, the bot seats, the whole auction and
 * the whole trick loop all get exercised. The client-side game is ticked by hand — nothing else ticks
 * it — and because the local card player reports {@code shouldCompute() == true} on the client, all
 * four hands bid and play themselves and the screen is fully populated.
 *
 * <p>It is the only way to see the auction, the dummy and the scoring run end to end without four
 * people.
 */
public final class BridgeDebug {

    private static final String[] BOT_IDS = {"-2", "-3", "-4"};

    /**
     * Game ticks per client tick.
     *
     * <p>A whole board is about 2,500 ticks (104 of dealing, a few hundred of auction, thirteen tricks
     * of four cards) and a dev client window that loses focus pauses long before that. Running at 20x
     * keeps a board inside a few seconds of real time; every bot delay is counted in game ticks, so
     * nothing about the flow changes.
     */
    private static final int SPEED = 20;

    @Nullable
    private static BridgeScreen screen;
    @Nullable
    private static BridgeGame game;
    private static boolean live;
    private static boolean finished;
    private static int ticks;

    private BridgeDebug() {
    }

    public static void install() {
        if (!"1".equals(System.getenv("BRIDGE_DEBUG_SCREEN"))) {
            return;
        }
        BridgeMod.LOGGER.info("Debug table enabled (BRIDGE_DEBUG_SCREEN=1)");
        ClientTickEvents.END_CLIENT_TICK.register(BridgeDebug::tick);
    }

    private static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (live) {
            // Keep stepping the local copy so the bots bid and play and the screen fills up.
            if (game != null && minecraft.screen == screen) {
                for (int i = 0; i < SPEED; i++) {
                    game.tick();
                }
                heartbeat();
                report();
            }
            return;
        }
        if (minecraft.screen != null) {
            return;
        }
        open(minecraft);
    }

    /** One line when the board is scored, so a run can be checked without reading a chat log. */
    private static void report() {
        if (finished || game == null || !game.isGameOver()) {
            return;
        }
        finished = true;
        StringBuilder tricks = new StringBuilder();
        for (int i = 0; i < game.getPlayers().size(); i++) {
            if (i > 0) {
                tricks.append(',');
            }
            tricks.append(game.getTricksTaken(game.playerAt(i)));
        }
        BridgeMod.LOGGER.info("debug: BOARD DONE contract={} declarer={} doubling={} tricks={} required={} result={} score={}",
                game.getContract(), game.getDeclarerSeat(), game.getAuction().doubling(), tricks,
                game.getRequiredTricks(), game.getResultMade(), game.getResultScore());
    }

    /** Periodic proof that the deal, the auction, the turns and the tricks are actually running. */
    private static void heartbeat() {
        if (game == null || ++ticks % 10 != 0) {
            return;
        }
        if (game.getPhase() == BridgeGame.Phase.DEALING) {
            BridgeMod.LOGGER.info("debug: dealing, hands={}", handSizes());
            return;
        }
        BridgeMod.LOGGER.info("debug: phase={} calls={} contract={} declarer={} trick={}/{} current={} seat={} hands={}",
                game.getPhase(), game.getAuction().size(),
                game.getAuction().hasContract() ? game.getContract() : "-",
                game.getDeclarerSeat(), game.getTrickNumber(), BridgeGame.TRICKS,
                game.getCurrentPlayer() == null ? "-" : game.getCurrentPlayer().getName().getString(),
                game.getSeatOfCurrentPlayer(), handSizes());
    }

    private static String handSizes() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < game.getPlayers().size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(game.getPlayerHand(game.playerAt(i)).size());
        }
        return builder.toString();
    }

    private static void open(Minecraft minecraft) {
        Deck deck = Deck.simple(Rarity.COMMON, true, ChartaMod.id("standard"), ChartaMod.id("standard/black"));

        int[] ids = new int[1 + BOT_IDS.length];
        ids[0] = minecraft.player.getId();
        for (int i = 0; i < BOT_IDS.length; i++) {
            ids[i + 1] = Integer.parseInt(BOT_IDS[i]);
        }

        // options: [show trick count, fill empty seats with bots, declaring side vulnerable]
        AbstractCardMenu.Definition definition = new AbstractCardMenu.Definition(
                minecraft.player.blockPosition(), deck, ids, new byte[]{1, 1, 0});

        BridgeMenu menu = new BridgeMenu(0, minecraft.player.getInventory(), definition);
        screen = new BridgeScreen(menu, minecraft.player.getInventory(), Component.empty());
        game = menu.getGame();
        live = true;

        minecraft.setScreen(screen);
        game.startGame();
    }

}

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
 * <p>The world itself is opened too ({@link #WORLD}, joined from the title screen), so the harness runs
 * unattended from a cold start: {@code --quickPlaySingleplayer} only fires when the level already
 * exists, and a run that quietly stops on the title screen looks exactly like a run that crashed.
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

    /**
     * Boards to play before the harness stops.
     *
     * <p>More than one because a single deal can be lucky: a fast solve never exercises the "answer is
     * not ready when the board ends" path, and one board never exercises the re-deal token.
     */
    private static final int BOARDS = 8;

    /** Client ticks to sit on the finished board before dealing the next one. */
    private static final int RESTART_DELAY = 40;

    /**
     * Dev world the harness joins, as a folder under {@code runs/client/saves}.
     *
     * <p>Kept in step with the {@code --quickPlaySingleplayer} argument in {@code build.gradle}: the
     * world is opened from here as well because that argument only takes effect when the level exists,
     * and a harness that sits on the title screen doing nothing is indistinguishable from a broken one.
     */
    private static final String WORLD = "New";

    /** Client ticks to wait for the boot reload to finish before opening {@link #WORLD}. */
    private static final int JOIN_DELAY = 100;

    @Nullable
    private static BridgeScreen screen;
    @Nullable
    private static BridgeGame game;
    private static boolean live;
    private static boolean finished;
    private static boolean joined;
    private static int idleTicks;
    private static int boards;
    private static int restartIn;
    private static int ticks;

    private BridgeDebug() {
    }

    public static void install() {
        if (!"1".equals(System.getenv("BRIDGE_DEBUG_SCREEN"))) {
            return;
        }
        BridgeMod.LOGGER.info("Debug table enabled (BRIDGE_DEBUG_SCREEN=1)");
        // Nobody is connected on the dev screen, so the client-side mirror has to do its own solving
        // for the double-dummy path to run at all; see BridgeGame.solveOnClient.
        BridgeGame.solveOnClient = true;
        ClientTickEvents.END_CLIENT_TICK.register(BridgeDebug::tick);
    }

    private static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            joinWorld(minecraft);
            return;
        }
        if (live) {
            // Keep stepping the local copy so the bots bid and play and the screen fills up.
            if (game != null && minecraft.screen == screen) {
                if (restartIn > 0 && --restartIn == 0 && !finished) {
                    // Deal the next board on the same instance, which is the same call the pass-out
                    // path takes and the same one the table takes on a fresh game.
                    game.startGame();
                }
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

    /**
     * Opens {@link #WORLD} once the game has settled on a menu with no world in it, so the harness runs
     * unattended.
     *
     * <p>Waits a few seconds and for the loading overlay to clear rather than testing for a title screen
     * by class: the client spends its first seconds behind a resource-reload overlay with no world and
     * no screen of its own, and opening from under that forks the load. The wait also means an
     * already-running {@code --quickPlaySingleplayer} wins the race instead of being doubled up. On
     * failure the flag clears so the next tick retries, rather than the harness stopping silently on a
     * menu that looks exactly like a crash.
     */
    private static void joinWorld(Minecraft minecraft) {
        if (joined) {
            return;
        }
        if (++idleTicks % 100 == 0) {
            BridgeMod.LOGGER.info("debug: waiting for a world (screen={}, overlay={})",
                    minecraft.screen, minecraft.getOverlay());
        }
        if (idleTicks < JOIN_DELAY || minecraft.getOverlay() != null) {
            return;
        }
        joined = true;
        BridgeMod.LOGGER.info("debug: opening world {}", WORLD);
        minecraft.createWorldOpenFlows().openWorld(WORLD, () -> joined = false);
    }

    /** One line when the board is scored, so a run can be checked without reading a chat log. */
    private static void report() {
        if (finished || game == null || !game.isGameOver() || restartIn > 0) {
            return;
        }
        boards++;
        StringBuilder tricks = new StringBuilder();
        for (int i = 0; i < game.getPlayers().size(); i++) {
            if (i > 0) {
                tricks.append(',');
            }
            tricks.append(game.getTricksTaken(game.playerAt(i)));
        }
        BridgeMod.LOGGER.info("debug: BOARD DONE {}/{} contract={} declarer={} doubling={} tricks={} required={} result={} score={}",
                boards, BOARDS, game.getContract(), game.getDeclarerSeat(), game.getAuction().doubling(), tricks,
                game.getRequiredTricks(), game.getResultMade(), game.getResultScore());
        if (boards >= BOARDS) {
            finished = true;
            return;
        }
        restartIn = RESTART_DELAY;
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

        // options: [show trick count, fill empty seats with bots, declaring side vulnerable, bot strength
        // (0 easy / 1 normal / 2 hard), double-dummy post-mortem]. Positional, so the newest option goes
        // last; a short array is still valid -- setRawOptions just leaves the missing tail at its default
        // -- but then the debug table silently runs whatever that default is. Double dummy is on here so
        // the dev screen covers it; it is off by default in the real game.
        AbstractCardMenu.Definition definition = new AbstractCardMenu.Definition(
                minecraft.player.blockPosition(), deck, ids, new byte[]{1, 1, 0, 1, 1});

        BridgeMenu menu = new BridgeMenu(0, minecraft.player.getInventory(), definition);
        screen = new BridgeScreen(menu, minecraft.player.getInventory(), Component.empty());
        game = menu.getGame();
        live = true;

        minecraft.setScreen(screen);
        game.startGame();
    }

}

package dev.bridge;

import dev.bridge.game.BridgeGame;
import dev.bridge.game.BridgeMenu;
import dev.bridge.network.BridgeCallPayload;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.FabricChartaMod;
import dev.lucaargolo.charta.common.game.Games;
import dev.lucaargolo.charta.common.game.api.game.GameType;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Charta addon entrypoint.
 *
 * <p>A playable game needs two registrations: a {@link GameType} in Charta's {@code charta:game_type}
 * registry, and a {@link MenuType} in {@code minecraft:menu}. Charta's table screen enumerates
 * {@code Games.getRegistry()} when it opens, so nothing else is required to make the game show up.
 *
 * <h2>Both registrations have to happen right here, and that needs Charta already up</h2>
 *
 * Fabric freezes every registry — {@code minecraft:menu} <b>and</b> dynamically registered ones
 * such as {@code charta:game_type} — as soon as the {@code main} entrypoint stage ends. So neither
 * registration can be postponed to a lifecycle event; they must run inside {@link #onInitialize()}.
 *
 * <p>But Charta's classes cannot be touched until Charta's own {@code main} entrypoint has run:
 * everything funnels through {@code ChartaMod.loadPlatformClass}, which dereferences
 * {@code ChartaMod.instance}, a field only assigned in Charta's {@code ModInitializer} constructor.
 * Two ordinary-looking references reach it:
 *
 * <pre>
 *   Games.getRegistry()                      -&gt; Games.&lt;clinit&gt;                      -&gt; ChartaMod.registry
 *   AbstractCardMenu.Definition.STREAM_CODEC -&gt; Deck.&lt;clinit&gt; -&gt; Suits.&lt;clinit&gt; -&gt; ChartaMod.registry
 * </pre>
 *
 * <p>Fabric Loader does <b>not</b> guarantee that a dependant mod's entrypoint runs after its
 * dependency's, and the order is not even stable between runs: newer loaders derive it from an
 * identity-hashed map, so {@code charta} loads before {@code bridge} on some launches and after it
 * on others. The unlucky launch dies with {@code NullPointerException: ChartaMod.instance is null}
 * and, because a failed static initializer poisons the class permanently
 * ({@code NoClassDefFoundError: Could not initialize class ...}), catching and retrying does not help.
 *
 * <p>{@link #ensureChartaInitialised()} removes the ordering requirement entirely: if Charta has not
 * been constructed yet, construct it. That only sets {@code ChartaMod.instance} and creates its
 * packet manager — {@code init()} itself is left to Charta's own entrypoint, so nothing is registered
 * twice. By the time the real entrypoint runs, {@code instance} already points at it.
 */
public class BridgeMod implements ModInitializer {

    public static final String MOD_ID = "bridge";
    public static final String MOD_NAME = "Contract Bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    /**
     * Registry id of the game, used for both {@code charta:game_type} and {@code minecraft:menu}.
     *
     * <p>The path matters beyond bookkeeping: Charta derives the table button label from
     * {@code gameId.toLanguageKey()} ({@code bridge.contract_bridge}), the button texture from
     * {@code bridge:textures/gui/game/contract_bridge.png} and the how-to-play page from
     * {@code bridge.how_to_play_contract_bridge}.
     */
    public static final ResourceLocation GAME_ID = id("contract_bridge");

    /**
     * Font holding the four suit symbols and {@code NT}, used for everything the auction and the
     * contract are written with. The glyphs are white and get tinted with the deck's own suit colour,
     * so a bid reads exactly like the played-card messages do.
     */
    public static final Style SUITS = Style.EMPTY.withFont(id("suit"));

    /** Registered into {@code charta:game_type}. */
    public static GameType<BridgeGame, BridgeMenu> BRIDGE;
    /** Registered into {@code minecraft:menu}. */
    public static MenuType<BridgeMenu> BRIDGE_MENU;

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ensureChartaInitialised();

        BRIDGE_MENU = Registry.register(
                BuiltInRegistries.MENU,
                GAME_ID,
                new ExtendedScreenHandlerType<BridgeMenu, AbstractCardMenu.Definition>(
                        BridgeMenu::new,
                        AbstractCardMenu.Definition.STREAM_CODEC
                )
        );

        BRIDGE = Registry.register(Games.getRegistry(), GAME_ID, BridgeGame::new);

        PayloadTypeRegistry.playC2S().register(BridgeCallPayload.TYPE, BridgeCallPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BridgeCallPayload.TYPE, (payload, context) ->
                BridgeCallPayload.handleServer(payload, context.player(), context.server()));

        LOGGER.info("Registered {} as a playable charta game", GAME_ID);
    }

    /**
     * Makes {@code ChartaMod.instance} non-null so Charta's static initializers work, regardless of
     * whether Fabric decided to run Charta's entrypoint before ours.
     *
     * <p>Constructing a second {@code FabricChartaMod} is safe and deliberate: the constructor does
     * nothing but assign {@code instance} and create a packet manager, while all actual registration
     * lives in {@code onInitialize()} -> {@code ChartaMod.init()}, which only Fabric's own instance
     * receives. Calling {@code init()} ourselves as well would double-register every Charta entry.
     */
    private static void ensureChartaInitialised() {
        if (ChartaMod.getInstance() != null) {
            return;
        }
        LOGGER.info("Charta's entrypoint has not run yet; constructing it early to unlock its registries");
        new FabricChartaMod();
    }

}

package dev.bridge;

import dev.bridge.game.BridgeScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;

/**
 * Client side. {@code BridgeScreen} extends Charta's {@code GameScreen}, so every card widget,
 * hover animation, drag/drop interaction and the table history panel come from Charta for free.
 *
 * <p>This is a {@code client} entrypoint, which Fabric runs after every {@code main} entrypoint,
 * so Charta is guaranteed to be fully initialised by the time this runs — no deferral needed.
 */
@Environment(EnvType.CLIENT)
public class BridgeClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MenuScreens.register(BridgeMod.BRIDGE_MENU, BridgeScreen::new);
        BridgeDebug.install();
    }

    /**
     * One line into the local chat bar.
     *
     * <p>Only for the side that has no server to speak of: {@code CardPlayer.sendMessage} is
     * {@code displayClientMessage}, which needs a {@code ServerPlayer}, and the dev screen's four
     * players are the local {@code LocalPlayer} plus bots. On a real table the server does this and
     * this is not reached.
     */
    public static void chat(Component message) {
        Minecraft.getInstance().gui.getChat().addMessage(message);
    }

}

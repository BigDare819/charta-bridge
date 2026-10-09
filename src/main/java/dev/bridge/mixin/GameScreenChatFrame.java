package dev.bridge.mixin;

import dev.bridge.client.BridgeFrame;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Takes the chat out of {@code GameScreen.render} so {@code BridgeScreen} can draw it <em>after</em>
 * the design frame is popped.
 *
 * <h2>Why the chat cannot stay inside the frame</h2>
 *
 * <p>It is the one thing on this screen that vanilla also draws on its own: {@code Gui}'s chat layer
 * runs before any screen and has no idea a screen exists, so it paints a copy at
 * {@code guiHeight() - 40} in plain window coordinates. {@code GameScreen} paints a second copy 25 px
 * higher, which is what the player actually reads. Both line up today only because "window
 * coordinates" and "screen coordinates" are the same thing.
 *
 * <p>Inside a scaled frame they are not. The frame copy would be laid out at
 * {@code guiHeight() - 40} of <em>frame</em> space -- {@code ChatComponent.render} asks
 * {@code GuiGraphics.guiHeight()}, which reads the window straight out of {@code Minecraft} and knows
 * nothing about a {@code PoseStack} -- and would land at a different height from the vanilla copy. On
 * a 4:3 window that is two chats visibly apart.
 *
 * <h2>The seam</h2>
 *
 * <p>Redirecting the single {@code ChatComponent.render} call to nothing is enough: {@code BridgeScreen}
 * then repeats exactly the same call after popping the pose, with window coordinates and the untouched
 * mouse, so the result is byte-for-byte what it was before this class existed. The wrapping
 * {@code translate(0, -25)} and the chat-width squeeze still run where they always did; only the draw
 * moves.
 *
 * <h2>Why the guard is a frame check and not an {@code instanceof BridgeScreen}</h2>
 *
 * <p>A sibling addon redirects this very instruction, and Mixin keeps the higher priority copy and skips
 * the other — so this copy may be the one running for the other addon's screen too. {@link
 * BridgeFrame#isFramedScreen} therefore recognises every framed screen on the table, and each framed
 * screen paints its own chat after popping the pose. A plain {@code instanceof} would leave the sibling
 * screen with both its in-frame chat and its own copy, drawn at different scales.
 *
 * <p>The handler is an instance method purely so the guard can keep Charta's own games -- which share
 * {@code GameScreen.render} and have no frame -- rendering their chat here.
 */
@Mixin(GameScreen.class)
public abstract class GameScreenChatFrame {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V"))
    private void bridge$chatOutsideFrame(ChatComponent chat, GuiGraphics guiGraphics,
                                         int ticks, int mouseX, int mouseY, boolean focused) {
        if (!BridgeFrame.isFramedScreen(this)) {
            chat.render(guiGraphics, ticks, mouseX, mouseY, focused);
        }
    }
}

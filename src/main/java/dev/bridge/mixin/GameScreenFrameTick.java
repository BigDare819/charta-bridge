package dev.bridge.mixin;

import dev.bridge.client.BridgeFrame;
import dev.bridge.game.BridgeScreen;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hands {@code GameScreen.containerTick}'s card tilt a mouse in design-frame coordinates.
 *
 * <h2>The problem</h2>
 *
 * <p>{@code BridgeScreen} paints inside a scaled, centred frame (see {@link BridgeFrame}) and therefore
 * reports its widgets and its mouse in frame coordinates. Everything that goes through the render pass
 * is converted along the way, but {@code containerTick} sidesteps it: it reads
 * {@code MouseHandler.xpos()} straight off the window and multiplies it into a GUI coordinate itself.
 * With a frame scale of 0.67 that hands every widget a mouse about 1.5x too far from it, and
 * {@code AbstractCardWidget.tick} turns that difference into a hover tilt without clamping anything --
 * so a hovered card leans by hundreds of degrees instead of tens.
 *
 * <h2>Why the redirect is on {@code xpos} and not on the arithmetic</h2>
 *
 * <p>That method's expression is {@code xpos() * guiScaledWidth / screenWidth}; substituting a
 * different {@code xpos()} is the only seam that needs no access to the method body. The value handed
 * back is pre-solved so the existing arithmetic lands on the frame coordinate anyway -- see
 * {@link BridgeFrame#tickX}.
 *
 * <h2>Why the handlers are instance methods with a guard</h2>
 *
 * <p>{@code GameScreen} is the base class of Charta's own games as well, and those paint in plain
 * window coordinates. The pose lives in {@code BridgeScreen}, so the conversion has to be conditional
 * or every other card table would get a mis-scaled hover tilt. A {@code @Redirect} handler may be
 * non-static, in which case it keeps the mixin's {@code this} -- and that is the object the guard
 * needs. The cast through {@code Object} exists because the mixin class and {@code BridgeScreen} are
 * unrelated types to the compiler.
 */
@Mixin(GameScreen.class)
public abstract class GameScreenFrameTick {

    @Redirect(
            method = "containerTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;xpos()D"))
    private double bridge$frameMouseX(MouseHandler mouseHandler) {
        double raw = mouseHandler.xpos();
        return ((Object) this) instanceof BridgeScreen ? BridgeFrame.tickX(raw) : raw;
    }

    @Redirect(
            method = "containerTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;ypos()D"))
    private double bridge$frameMouseY(MouseHandler mouseHandler) {
        double raw = mouseHandler.ypos();
        return ((Object) this) instanceof BridgeScreen ? BridgeFrame.tickY(raw) : raw;
    }
}

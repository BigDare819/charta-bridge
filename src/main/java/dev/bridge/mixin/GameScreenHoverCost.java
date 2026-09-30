package dev.bridge.mixin;

import dev.lucaargolo.charta.client.render.screen.GameScreen;
import dev.lucaargolo.charta.common.utils.HoverableRenderable;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops the hovered widget from being painted once per <em>other</em> widget.
 *
 * <h2>What Charta does</h2>
 *
 * <p>{@code GameScreen.render} walks {@code this.renderables} and, before drawing each one, re-draws
 * {@code this.hoverable} first:
 *
 * <pre>{@code
 * for (Renderable renderable : this.renderables) {
 *     if (renderable != this.hoverable) {
 *         if (this.hoverable != null) this.hoverable.render(...);   // <-- this one
 *         renderable.render(...);
 *     }
 * }
 * ...
 * if (this.hoverable != null) this.hoverable.render(...);           // <-- and once at the end
 * }</pre>
 *
 * <p>The comment says it keeps cards from "flashing weirdly". What it actually costs is that with a
 * card on hover, the hovered widget is drawn {@code renderables.size()} times instead of once. The
 * widget here is a whole <em>hand</em>: {@code CardSlotWidget} renders its entire fan, and it has the
 * same loop inside it (see {@link CardSlotWidgetHoverCost}), so one hovered card out of a four-handed
 * table turns into roughly
 *
 * <pre>  56 outer renderables x (13 cards + 12 inner repeats)  ~=  1 400 full-card draws per frame</pre>
 *
 * <p>and every one of those draws is three card blits plus a bind of the glow render target and back.
 * 120 fps to 35 fps, on the one frame where the pointer is over a hand.
 *
 * <h2>Why dropping them changes nothing</h2>
 *
 * <p>The trailing draw already happens last, so the hovered widget ends up on top either way. Every
 * intermediate draw uses identical geometry, the same shader colour and the same blend function, and
 * the draw that lands on top is the trailing one in both orderings -- so the interleaved ones are
 * repainted away in full. This is a pure deletion, not a reordering.
 *
 * <h2>Why the selector is what it is</h2>
 *
 * <p>{@code render} overrides a vanilla method and is therefore published in Charta's release jar as
 * {@code method_25394}, so the method name has to stay remappable. {@code ordinal = 0} picks the
 * in-loop call; the trailing one is the second match in the method and must survive.
 * {@code HoverableRenderable} inherits {@code render} from vanilla's {@code Renderable}, so the target
 * owner is the interface but the name and the {@code GuiGraphics} descriptor are both remapped.
 * The handler is static because the receiver of the call is the field ({@code hoverable}), not
 * {@code this} screen.
 */
@Mixin(GameScreen.class)
public abstract class GameScreenHoverCost {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Ldev/lucaargolo/charta/common/utils/HoverableRenderable;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
    private static void bridge$skipInterleavedHoverable(HoverableRenderable hoverable,
                                                        GuiGraphics guiGraphics,
                                                        int mouseX, int mouseY, float partialTick) {
        // Deliberately empty: the trailing draw after the loop already covers this.
    }
}

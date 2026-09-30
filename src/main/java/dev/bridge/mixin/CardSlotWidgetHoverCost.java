package dev.bridge.mixin;

import dev.lucaargolo.charta.client.render.screen.widgets.CardSlotWidget;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The same de-duplication as {@link GameScreenHoverCost}, one level down.
 *
 * <p>An extended slot (a hand) draws one child widget per card, and its render loop has the identical
 * "render the hovered one before every other one" shape. Here it is worse than at screen level,
 * because this widget is itself drawn repeatedly by {@code GameScreen}: the outer repetition
 * multiplies this one. Thirteen cards in a fan means twelve redundant redraws of the card under the
 * pointer per pass of the outer loop.
 *
 * <p>Only the {@code HORIZONTAL}/{@code DEFAULT} branch is affected. It is already guarded by
 * {@code type != VERTICAL}, so a west/east column -- which never ran the interleaved draw and instead
 * draws every card once in fan order -- behaves exactly as before.
 *
 * <p>This method overrides vanilla's {@code AbstractWidget.renderWidget}, so the name stays remappable
 * ({@code method_48579}). The call target is Charta's own {@code CardSlotWidget.render}, inherited from
 * vanilla {@code Renderable}, so the name is remapped while the owner is not.
 */
@Mixin(CardSlotWidget.class)
public abstract class CardSlotWidgetHoverCost {

    @Redirect(
            method = "renderWidget",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Ldev/lucaargolo/charta/client/render/screen/widgets/CardSlotWidget;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
    private static void bridge$skipInterleavedHoverable(CardSlotWidget<?, ?> hoverable,
                                                        GuiGraphics guiGraphics,
                                                        int mouseX, int mouseY, float partialTick) {
        // Deliberately empty: the trailing draw after the loop already covers this.
    }
}

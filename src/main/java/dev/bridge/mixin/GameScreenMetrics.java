package dev.bridge.mixin;

import dev.bridge.client.CardSlotAccess;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import dev.lucaargolo.charta.common.menu.CardSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the click hit-tests on the same numbers the cards are painted with.
 *
 * <p>{@code isHoveringPrecise} builds its box from the static {@code CardSlot.getWidth/getHeight}
 * overloads, while {@link CardSlotWidgetMetrics} makes the <em>painter</em> use the per-slot declared
 * box. Redirecting only one of the two would slide a hand's clickable area off the cards — silently, and
 * only by however much the box was resized.
 *
 * <p>Scoped to that single method on purpose: {@code GameScreen.render} also compares a slot's width
 * against {@code CardImage.WIDTH * 1.5f} to decide whether to blit the framed drop-target background, and
 * that comparison should stay on the vanilla type sizes.
 *
 * <h2>Why everything here is {@code remap = false} and carries a full descriptor</h2>
 *
 * <p>Neither {@code isHoveringPrecise} (private, Charta's own) nor {@code CardSlot.getWidth} (Charta's own
 * static) overrides a vanilla member, so Charta's release jar carries both under their source names — the
 * literal form is what actually exists on disk. The descriptor is written out in full so the selector
 * cannot catch the sibling {@code isHoveringPrecise(FFFFDD)} overload, which has no call site of its own;
 * {@code require = 0} is the belt to that pair of braces.
 *
 * <p>Note the contrast with {@link CardSlotWidgetMetrics}, where the method name <em>must</em> stay
 * remappable because it overrides a vanilla method and is published as {@code method_48579}.
 */
@Mixin(GameScreen.class)
public abstract class GameScreenMetrics {

    @Redirect(
            method = "isHoveringPrecise(Ldev/lucaargolo/charta/common/menu/CardSlot;FF)Z",
            require = 0,
            remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Ldev/lucaargolo/charta/common/menu/CardSlot;getWidth(Ldev/lucaargolo/charta/common/menu/CardSlot;)F"))
    private static float bridge$declaredWidth(CardSlot<?, ?> slot) {
        return ((CardSlotAccess) slot).bridge$width();
    }

    @Redirect(
            method = "isHoveringPrecise(Ldev/lucaargolo/charta/common/menu/CardSlot;FF)Z",
            require = 0,
            remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Ldev/lucaargolo/charta/common/menu/CardSlot;getHeight(Ldev/lucaargolo/charta/common/menu/CardSlot;)F"))
    private static float bridge$declaredHeight(CardSlot<?, ?> slot) {
        return ((CardSlotAccess) slot).bridge$height();
    }
}

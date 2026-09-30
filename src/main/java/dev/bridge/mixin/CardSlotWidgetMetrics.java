package dev.bridge.mixin;

import dev.bridge.client.CardSlotAccess;
import dev.lucaargolo.charta.client.render.screen.widgets.CardSlotWidget;
import dev.lucaargolo.charta.common.menu.CardSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes the card fan honour the slot's declared box.
 *
 * <p>{@code CardSlotWidget} reads the box through {@code CardSlot.getWidth(slot)} /
 * {@code getHeight(slot)} — both <em>static</em>, both switching on {@link CardSlot.Type}. So a slot could
 * be moved but never resized: {@code bridge$setSize} wrote a field nothing read, and the fan was pinned to
 * 150px for every {@code HORIZONTAL} slot and 41px for every {@code PREVIEW} one. The declared box is the
 * only lever on how far a hand spreads, so without this the layout's hand sizes are decorative.
 *
 * <p>Only the two {@code (CardSlot)} overloads are redirected. The {@code (CardSlot.Type)} ones read
 * fixed sizes for the child cards themselves and must stay untouched.
 *
 * <h2>Why the {@code remap} flags are asymmetric</h2>
 *
 * <p>A mod class has two kinds of members and they are published differently:
 * <ul>
 *   <li>{@code renderWidget} <b>overrides a vanilla method</b>, so Charta's release jar carries it under
 *       its intermediary name, {@code method_48579}. The selector therefore has to stay remappable — the
 *       {@code remap = false} that was correct everywhere else made the whole mixin fail to apply with
 *       {@code Critical injection failure: @Redirect ... could not find any targets matching
 *       'renderWidget'}, which aborted {@code GameScreen.init} half-way and left its widget list empty.</li>
 *   <li>{@code CardSlot.getWidth} is Charta's own, never remapped, so the {@code @At} target is literal
 *       and is flagged {@code remap = false}. Leaving it on also only produced an annotation-processor
 *       warning, but the literal form is what the jar actually contains.</li>
 * </ul>
 */
@Mixin(CardSlotWidget.class)
public abstract class CardSlotWidgetMetrics {

    @Redirect(
            method = "renderWidget",
            at = @At(value = "INVOKE", remap = false,
                    target = "Ldev/lucaargolo/charta/common/menu/CardSlot;getWidth(Ldev/lucaargolo/charta/common/menu/CardSlot;)F"))
    private static float bridge$declaredWidth(CardSlot<?, ?> slot) {
        return ((CardSlotAccess) slot).bridge$width();
    }

    @Redirect(
            method = "renderWidget",
            at = @At(value = "INVOKE", remap = false,
                    target = "Ldev/lucaargolo/charta/common/menu/CardSlot;getHeight(Ldev/lucaargolo/charta/common/menu/CardSlot;)F"))
    private static float bridge$declaredHeight(CardSlot<?, ?> slot) {
        return ((CardSlotAccess) slot).bridge$height();
    }
}

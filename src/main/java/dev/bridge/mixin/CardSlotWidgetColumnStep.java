package dev.bridge.mixin;

import dev.lucaargolo.charta.client.render.screen.widgets.CardSlotWidget;
import dev.lucaargolo.charta.common.menu.CardSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Lets a vertical hand spread as far as its declared box, instead of stopping after 10px a card.
 *
 * <h2>Why the box height alone is not enough</h2>
 *
 * <p>A north/south fan spreads to fill its box: {@code CardSlotWidget} solves the per-card step from the
 * declared width, so widening {@code SOUTH_HAND} widens the fan one-for-one. East and west do not work
 * that way. Their step starts at a <em>hard-coded 10</em> and is only reduced when the hand would
 * overflow:
 *
 * <pre>
 * topOffset = 10f;
 * if (topOffset * (size - 1) + cardHeight &gt; getPreciseHeight()) {
 *     topOffset = (getPreciseHeight() - cardHeight) / (size - 1);
 * }
 * </pre>
 *
 * <p>So a column's per-card step is {@code min(10, (height - 52.5) / (size - 1))}: a 13-card hand in a
 * 160px box already sits on the second branch at 8.96px, and a box of anything up to
 * {@code 52.5 + 10*12 = 172.5} yields the same 10px ceiling. Growing the box past that changes nothing
 * at all, and the cards stay 10px apart however tall the column is.
 *
 * <p>This injector swaps that 10 for the height of a card, which makes the cap <em>one whole card</em>:
 * up to a hand of two the step is the fill formula, and below that the cards may touch but never
 * separate. A hand of thirteen in the 260px column therefore steps 17px instead of 9.
 *
 * <h2>Which {@code 10.0f}</h2>
 *
 * <p>{@code renderWidget} holds two identical {@code ldc 10.0f} constants; the other one is the
 * {@code childWidth / 10f} slack that sets {@code maxLeftOffset} for the horizontal fan, and must keep
 * its value. They are 30 instructions apart in the bytecode with the fan's one first -- offsets 119 and
 * 267 in the release jar -- so {@code ordinal = 1} is the column step. Verified by disassembling
 * {@code CardSlotWidget.renderWidget} out of {@code charta-fabric-1.21.1-1.2.5.jar}.
 *
 * <p>The injector is not gated on this mod, so it also applies to Charta's own vertical hands. Those are
 * 112.5px tall, where the old cap was already inactive for a full hand and only ever shortened the step
 * for hands of six cards or fewer -- so a vanilla column gets slightly roomier, never tighter.
 */
@Mixin(CardSlotWidget.class)
public abstract class CardSlotWidgetColumnStep {

    /**
     * {@code CardSlot.getHeight(Type.DEFAULT)}, i.e. {@code CardImage.HEIGHT * 1.5f}. Read through
     * {@code CardSlot} rather than written as 52.5 so a Charta that resizes its cards takes this with it.
     */
    private static float bridge$fullCardStep() {
        return CardSlot.getHeight(CardSlot.Type.DEFAULT);
    }

    @ModifyConstant(
            method = "renderWidget",
            constant = @Constant(floatValue = 10f, ordinal = 1))
    private static float bridge$columnStep(float original) {
        return bridge$fullCardStep();
    }
}

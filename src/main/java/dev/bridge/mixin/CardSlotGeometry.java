package dev.bridge.mixin;

import dev.bridge.client.CardSlotAccess;
import dev.lucaargolo.charta.common.menu.CardSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Injects a mutable view of {@link CardSlot}'s geometry into Charta's own slot class.
 *
 * <p>{@code x} and {@code y} are {@code public final}, and width/height are not stored at all — the
 * widget derives them from {@link CardSlot.Type} through two <em>static</em> methods, and uses the
 * declared width as the box the cards fan inside. That makes the declared width the only lever that
 * spreads a hand out, so the F9 layout editor needs a per-slot size. The static lookups cannot be
 * overridden, so this records the size on the instance and {@link CardSlotAccess} reads it back.
 *
 * <p>Only ever set from the client layout; a slot that was never sized falls straight through to the
 * vanilla value. See {@link CardSlotAccess} for why the interface lives outside the mixin package.
 *
 * <p>The three fields are deliberately <em>not</em> {@code @Unique}: Mixin merges a non-unique field
 * into an identically named one already on the target, which is how a sibling Charta addon with its own
 * layout editor ends up reading and writing the same slot size. Without the merge each addon would keep
 * a private copy and the addon whose {@code @Redirect} on the metric call lost the priority tie would
 * see the other addon's sizes as zero.
 */
@Mixin(CardSlot.class)
public abstract class CardSlotGeometry implements CardSlotAccess {

    private float bridge$width;

    private float bridge$height;

    /** Marks the field as set, so a width of 0 can stay legal. */
    private boolean bridge$sized;

    @Override
    public void bridge$setSize(float width, float height) {
        this.bridge$width = width;
        this.bridge$height = height;
        this.bridge$sized = true;
    }

    @Override
    public float bridge$width() {
        return this.bridge$sized ? this.bridge$width : CardSlot.getWidth(bridge$type());
    }

    @Override
    public float bridge$height() {
        return this.bridge$sized ? this.bridge$height : CardSlot.getHeight(bridge$type());
    }

    /**
     * {@code @Mutable} is required, not decorative: {@code x} and {@code y} are {@code final}, and a
     * plain accessor setter fails at runtime with
     * {@code IllegalAccessError: Update to non-static final field ... attempted from a different method}
     * because the generated setter is not the constructor.
     */
    @Mutable
    @Accessor("x")
    abstract void bridge$setFieldX(float x);

    @Mutable
    @Accessor("y")
    abstract void bridge$setFieldY(float y);

    @Accessor("type")
    abstract CardSlot.Type bridge$type();

    @Override
    public void bridge$setX(float x) {
        this.bridge$setFieldX(x);
    }

    @Override
    public void bridge$setY(float y) {
        this.bridge$setFieldY(y);
    }
}

package dev.bridge.client;

import dev.lucaargolo.charta.common.menu.CardSlot;

/**
 * A mutable view of Charta's {@link CardSlot} geometry, implemented by a mixin on {@code CardSlot}.
 *
 * <p>Why it exists: {@code x} and {@code y} are {@code public final} and a slot has no width or height
 * field — the widget derives both from {@link CardSlot.Type} through static methods. Without the
 * setters there is no way to move or rescale a slot once the menu has been built, which is exactly what
 * the F9 layout editor does.
 *
 * <h2>Why it is a plain interface in a normal package</h2>
 *
 * <p>Two rules collide if the mixin declares the accessors itself. A class inside a declared mixin
 * package is <em>owned</em> by that config and cannot be referenced from mod code —
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... cannot be referenced directly}.
 * And the Mixin annotation processor rejects an interface mixin whose target is a class:
 * {@code Targetted type ... is not an interface}. A plain interface in a normal package sidesteps both:
 * the mixin implements it and the {@code @Accessor} declarations still work.
 *
 * <p>{@link #bridge$width()} / {@link #bridge$height()} mirror {@code CardSlot.getWidth/getHeight}, so
 * layout code can treat every slot uniformly.
 */
public interface CardSlotAccess {

    void bridge$setX(float x);

    void bridge$setY(float y);

    /** Overrides the declared slot box, which is what the card fan is clamped into. */
    void bridge$setSize(float width, float height);

    /** Declared width of this slot, whether or not it was overridden. */
    float bridge$width();

    /** Declared height of this slot, whether or not it was overridden. */
    float bridge$height();
}

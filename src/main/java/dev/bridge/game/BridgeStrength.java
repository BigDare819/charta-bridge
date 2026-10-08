package dev.bridge.game;

import dev.lucaargolo.charta.common.game.api.game.GameOption;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * How hard the bots try: the value of the "bot strength" game option, and the widget it is edited with.
 *
 * <h2>Why a plain {@code GameOption.Number} was not enough</h2>
 *
 * <p>Charta only ships {@code Bool} and {@code Number}. The number variant renders as a slider whose
 * label is the raw integer, so the setting read "Bot strength: 1" and nothing said whether 1 was the
 * strong end or the weak one. {@link Option} below is a third kind: a button that cycles through the
 * levels and prints each one's name.
 *
 * <h2>Why the option lives here and not in {@code BridgeGame}</h2>
 *
 * <p>{@code getOptions()} must hand the option back on every call and in a stable order -- the raw
 * option bytes are positional, and {@code Game.setRawOptions} writes them back by index. Keeping the
 * option as a field on this enum means the list an instance builds and the list a fresh template game
 * builds agree, and that editing it cannot reorder anything.
 *
 * <p>The strength only ever changes what a {@link BridgeBot} decides; it never changes the rules, the
 * deal or the scoring, so a client with a stale value can only mis-animate a bot, never desync a board.
 */
public enum BridgeStrength {

    /** Follow suit and bid by high card points: the behaviour the mod shipped with. */
    EASY("easy"),
    /** Seat-aware card play -- third hand high, cheapest winner, lead through strength -- and a bidding system. */
    NORMAL("normal"),
    /** Everything NORMAL does, plus a rollout of the rest of the trick and slam hunting in the auction. */
    HARD("hard");

    private final String key;

    BridgeStrength(String key) {
        this.key = key;
    }

    /** Language key of this level's name, e.g. {@code strength.bridge.normal}. */
    public Component label() {
        return Component.translatable("strength.bridge." + key);
    }

    /** Language key of this level's one-line explanation, for the option's tooltip. */
    public Component description() {
        return Component.translatable("strength.bridge." + key + ".description");
    }

    /** The level stored in one raw option byte, clamped so a stale or hand-written byte still lands. */
    public static BridgeStrength of(int value) {
        BridgeStrength[] levels = values();
        return levels[Math.max(0, Math.min(levels.length - 1, value))];
    }

    /**
     * A cycling button as a {@link GameOption}, for a small set of named levels.
     *
     * <p>The stored value is the ordinal, which is what has to stay stable: {@link #EASY} must remain
     * {@code 0} so that every board saved before this option existed -- and every
     * {@code AbstractCardMenu.Definition} with a short options array -- reads as EASY rather than
     * silently becoming HARD.
     */
    public static final class Option extends GameOption<BridgeStrength> {

        private final BridgeStrength[] levels;

        public Option(BridgeStrength value, BridgeStrength[] levels, Component title, Component description) {
            super(value, title, description);
            this.levels = levels;
        }

        @Override
        protected byte toByte(BridgeStrength value) {
            return (byte) value.ordinal();
        }

        @Override
        protected BridgeStrength fromByte(byte value) {
            return BridgeStrength.of(value);
        }

        /** The next level, wrapping; what one press of the button does. */
        private BridgeStrength next() {
            BridgeStrength[] all = levels.length == 0 ? values() : levels;
            return all[(get().ordinal() + 1) % all.length];
        }

        @Override
        public Widget getWidget(Consumer<BridgeStrength> consumer, Font font, int width, int height, boolean showcase) {
            // Built through the same builder the vanilla options use, so the button matches the
            // checkboxes above and below it without this class having to know their metrics.
            //
            // The caption is refreshed by setting the option and letting the consumer the base class
            // just installed run: GameOption.consumer is package-private to Charta, so a subclass that
            // cannot see it has to go through setValue rather than write the field.
            Button button = Button.builder(message(this.get()), b -> {
                        this.set(next());
                        consumer.accept(this.get());
                    })
                    .tooltip(Tooltip.create(this.getDescription()))
                    .bounds(0, 0, width, height)
                    .build();
            button.active = !showcase;
            // An outside change (the options screen's reset button) has to move the caption too.
            return new Widget(button) {
                @Override
                public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int index, int top, int left,
                                   int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering,
                                   float partialTick) {
                    button.setMessage(message(Option.this.get()));
                    super.render(guiGraphics, index, top, left, rowWidth, rowHeight, mouseX, mouseY, hovering, partialTick);
                }
            };
        }

        /** {@code Bot strength: Normal} -- title and value on one line, like the number slider's label. */
        private Component message(BridgeStrength value) {
            return this.getTitle().copy().append(": ").append(value.label());
        }

    }

    /**
     * Debug-only fourth option: shows the local player's own high card points as a slider.
     *
     * <p>Lives here, next to {@link Option}, rather than in its own file: it is five lines of setting and
     * exists purely so a hand's strength can be read off the options screen while tuning the bots.
     *
     * <p>It has to be in this package to touch {@code GameOption.consumer}, which is package-private;
     * the value it stores is not part of the raw option bytes, because nothing else ever reads it.
     */
    public static final class Points extends GameOption<Integer> {

        public Points(Component title) {
            super(0, title, title);
        }

        @Override
        protected byte toByte(Integer value) {
            return (byte) (int) value;
        }

        @Override
        protected Integer fromByte(byte value) {
            return (int) value;
        }

        @Override
        public Widget getWidget(Consumer<Integer> consumer, Font font, int width, int height, boolean showcase) {
            Button button = Button.builder(message(this.get()), b -> consumer.accept(this.get()))
                    .bounds(0, 0, width, height)
                    .build();
            button.active = !showcase;
            return new Widget(button) {
                @Override
                public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int index, int top, int left,
                                   int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering,
                                   float partialTick) {
                    button.setMessage(message(Points.this.get()));
                    super.render(guiGraphics, index, top, left, rowWidth, rowHeight, mouseX, mouseY, hovering, partialTick);
                }
            };
        }

        private Component message(int value) {
            return this.getTitle().copy().append(": " + value);
        }

    }

}

package dev.bridge.client;

import dev.bridge.game.BridgeMenu;
import dev.lucaargolo.charta.common.menu.CardSlot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where everything on the Contract Bridge screen sits, and the live handles the F9 editor moves.
 *
 * <h2>One coordinate space: the screen</h2>
 *
 * <p>{@link #bounds} returns the <em>on-screen</em> rectangle of every element, including the local
 * hand — no caller has to know that {@code CardSlot.y} means something different per
 * {@link CardSlot.Type}. The translation into each of those three private conventions happens in exactly
 * one place, {@link #setSlot}, so the F9 outline, the painted decorations and the click hit-tests are
 * all reading the same numbers.
 *
 * <h2>The table shape</h2>
 *
 * <p>The two long hands are one absolute box each and the other two are mirrors of them through the
 * screen centre: north mirrors south through the horizontal centre line, east mirrors west through the
 * vertical one. Nothing about a hand is written as a bare corner coordinate. Each anchor is derived from
 * the frame in a form that stays symmetric under that mirror — the fan's centre on the vertical axis, the
 * column's equal margin off the frame edge ({@link #columnLeft}) — so lengthening a hand moves both of a
 * pair apart evenly instead of one of them off into a corner, and no constant has to be re-derived by
 * hand the next time a length changes.
 *
 * <h2>A hand's box is its spread</h2>
 *
 * <p>The declared box is not a bounding hint — {@code CardSlotWidget} fans the cards <em>through</em> it,
 * so its long edge is the only lever on how much of each card stays visible. For a 13-card hand the step
 * between two cards is {@code (declaredLength - cardSize) / 12}, which is why these boxes are as long as
 * the frame allows: at 160px a card showed a 10px sliver, which reads as a solid block rather than a fan.
 * East and west need something more than a longer box — their step is capped at 10px inside Charta — and
 * {@code CardSlotWidgetColumnStep} is what raises that cap.
 *
 * <p>North and south are {@code HORIZONTAL} fans and west/east are {@code VERTICAL} columns; all four are
 * full size. {@code PREVIEW} — Charta's third-scale hand — is not used, because a 13-card fan clamps into
 * the declared box and a 41px preview box leaves about 20px of visible card, i.e. one lump.
 *
 * <h2>Applying a layout</h2>
 *
 * <p>{@link CardSlot#x} and {@link CardSlot#y} are final, so moving a slot needs a reflective write. The
 * declared size needs one too, because the widget reads it through <em>static</em>
 * {@code CardSlot.getWidth/getHeight}. Both are safe on the client: the server never serialises slot
 * geometry, and {@code GameScreen} re-reads it every frame.
 */
public final class BridgeLayout {

    /** A movable piece of the screen. Sizes are the <em>drawn</em> extent, not a bounding hint. */
    public enum Element {
        /**
         * The frosted panel that carries the auction table and the bidding box.
         *
         * <p>192 tall, not 170: the table grew a header row of call ordinals above its four seat rows,
         * and the pass / double / redouble row is the last thing in the box at y=171..183 -- so at 170
         * the panel's bottom edge cut straight through it and the three buttons hung off the sheet.
         * 183 plus the same 9 px margin the title has at the top is 192.
         *
         * <p>168 wide, not 140: the table is a grid now and its call columns need the room. Must stay
         * equal to {@code BridgeMenu.PANEL_WIDTH/PANEL_HEIGHT}, which the popup's contents are written
         * in -- the two drifting apart slides everything out of the sheet. Neither is persisted (only
         * dx/dy/scale are), so this takes effect without touching {@code layout.version}.
         */
        POPUP("popup", 168, 192),
        /** The south hand: a horizontal fan, 300 long, centred, its top edge 300px down the screen. */
        SOUTH_HAND("south", 300, 53),
        /** North is that same fan mirrored through the screen's horizontal centre line. */
        NORTH_HAND("north", 300, 53),
        /**
         * West is an upright column, 224 tall, hanging from the north fan's lower edge (y=60) down to
         * y=284, parked at the frame's left side -- x=66, centred in the strip the fan leaves (see
         * {@link BridgeLayout#columnLeft}).
         *
         * <p>224 is not a round number, it is the gap that is left: the fans own the top and bottom 60px
         * of the frame, and the left-dragged progress line starts at 284. Nothing else is in the way, so
         * that is as long as a column can be -- and its step becomes 14.3px a card, up from 9.
         */
        WEST_HAND("west", 38, 224),
        /** East is that column mirrored through the screen's vertical centre line. */
        EAST_HAND("east", 38, 224),
        /**
         * The trick pile: a three-by-three block whose four edge cells hold north, east, south and
         * west, so the whole block is what the drop hit-test covers. The centre cell is left empty.
         *
         * <p>Ships 27px below its anchor, under the lifted contract line -- see {@link #defaults}.
         */
        PILE("pile", 113, 158),
        /** Name plates: their box is exactly where the plate is painted, below/above the hand. */
        NORTH_PLATE("north_plate", 60, 12),
        WEST_PLATE("west_plate", 60, 12),
        EAST_PLATE("east_plate", 60, 12),
        /**
         * The south seat's plate, above its hand.
         *
         * <p>The other three plates sit under or over a hand that is already somewhere on the screen,
         * but south's hand is the bottom fan and there used to be no plate element for it -- the local
         * player's label was tucked above their hand by a special case in the renderer. That worked
         * while the local player was always south; with the compass nailed to the seats it does not,
         * because whoever sits south needs a plate like everyone else.
         */
        SOUTH_PLATE("south_plate", 60, 12),
        /**
         * The four centre-screen play status lines, one element each so a single line can be nudged
         * without dragging the other three.
         *
         * <p>Their box is a wide, short <em>handle</em> centred on the screen, not the text's own
         * extent: the line stays centred inside it, so a horizontal drag is an offset from centre
         * rather than an absolute left edge the text length would keep re-defining. The box is also
         * what the F9 outline draws and what the pointer hit-tests, so handle and target coincide.
         *
         * <p>All four ship displaced from the centre stack -- contract up, the other three spread along
         * the bottom -- rather than stacked in the middle; see {@link #defaults}.
         */
        STATUS_CONTRACT("status_contract", 150, 10),
        STATUS_TURN("status_turn", 150, 10),
        STATUS_PROGRESS("status_progress", 150, 10),
        STATUS_TRICKS("status_tricks", 150, 10);

        /** Config key, and the suffix of this element's {@code editor.bridge.*} editor label. */
        public final String key;
        public final int baseWidth;
        public final int baseHeight;

        Element(String key, int baseWidth, int baseHeight) {
            this.key = key;
            this.baseWidth = baseWidth;
            this.baseHeight = baseHeight;
        }

        /** Translated name, for the F9 overlay. */
        public String label() {
            return Component.translatable("editor.bridge." + key).getString();
        }
    }

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bridge-layout.properties");

    /**
     * Layout schema, written into the saved file.
     *
     * <p>Bumping it discards a saved file, which matters when an element's default box moves far enough
     * that an offset dragged against the old default lands somewhere meaningless.
     *
     * <p>5 is the bump that moved the author's own tuning into {@link #defaults}: the file those values
     * came from holds the thirds and the {@code 19.999} that this round rounded off, so it has to be
     * dropped for the new defaults to be the thing on screen rather than the near-identical old file.
     * Levels 4 and below predate it and are simply ignored.
     */
    private static final String VERSION = "5";

    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.5f;
    /** How much one scroll notch changes an element's scale. */
    public static final float SCALE_STEP = 0.05f;

    /**
     * Absolute anchors of the two hand pairs, and the frame's fixed edges they hang off.
     *
     * <p>{@code SOUTH_Y} is the screen edge the fan pair keeps away from: the south fan stays 300px down,
     * and the north mirror and the pile derive from it. The columns' crossways coordinate is likewise
     * derived, not a constant -- see {@link #columnLeft} -- and every lengthwise coordinate comes out of
     * {@link #anchor}.
     */
    private static final int SOUTH_Y = 300;
    /** Gap between a hand and its name plate, and the plate's own height. */
    private static final int PLATE_GAP = 3;
    private static final int PLATE_H = 12;
    /** Gap between the top of the pile block and the bottom of the north hand. */
    private static final int PILE_TOP_GAP = 8;
    /**
     * Play status lines, measured <em>up</em> from the screen's bottom edge.
     *
     * <p>Was an absolute 238/252/264/276, which only lands on the bottom of a 360px-tall screen; on a
     * shorter window the whole stack used to slide off the bottom. Bottom-anchored, they hold their
     * place under the pile at any GUI scale.
     */
    private static final int STATUS_CONTRACT_UP = 122;
    private static final int STATUS_TURN_UP = 108;
    private static final int STATUS_PROGRESS_UP = 96;
    private static final int STATUS_TRICKS_UP = 84;

    /** {@code {dx, dy, scale}} per {@link Element}, indexed by {@link Element#ordinal()}. */
    private final float[][] values = new float[Element.values().length][3];

    public BridgeLayout() {
        resetAll();
        load();
    }

    // ------------------------------------------------------------------ values ---

    /**
     * The shipped arrangement: the offset every element starts at, and what {@link #reset} restores.
     *
     * <p>Five elements are not at their anchor. They used to live only in one player's
     * {@code bridge-layout.properties}, dragged there in F9 and re-derived from scratch on every fresh
     * install; they are the author's tuning, so they are the default now and the file is only an
     * override. In screen terms: the contract line is lifted to y=82 with the pile pushed down under it
     * (y=95), and the progress and trick lines are spread out to the two sides at y=284/285, clear of
     * everything. The turn line drops to y=269 to sit with them.
     *
     * <p>Whole numbers because {@link #bounds} truncates {@code dx}/{@code dy} with an {@code (int)}
     * cast -- a stored {@code 19.999} would render as 19. The file these came from held thirds and
     * {@code 19.999979}, all of which rounded away.
     */
    private static float[] defaults(Element element) {
        return switch (element) {
            case PILE -> new float[]{0f, 27f, 0f};
            case STATUS_CONTRACT -> new float[]{0f, -156f, 0f};
            case STATUS_TURN -> new float[]{0f, 17f, 0f};
            case STATUS_PROGRESS -> new float[]{-158f, 20f, 0f};
            case STATUS_TRICKS -> new float[]{177f, 9f, 0f};
            default -> new float[]{0f, 0f, 0f};
        };
    }

    /** The mutable {@code {dx, dy, scale}} triple of one element. */
    private float[] entry(Element element) {
        return values[element.ordinal()];
    }

    public float dx(Element element) {
        return entry(element)[0];
    }

    public float dy(Element element) {
        return entry(element)[1];
    }

    public float scale(Element element) {
        return entry(element)[2] == 0f ? 1f : entry(element)[2];
    }

    public void move(Element element, float ddx, float ddy) {
        float[] value = entry(element);
        value[0] += ddx;
        value[1] += ddy;
    }

    public void scaleBy(Element element, float amount) {
        float[] value = entry(element);
        value[2] = Mth.clamp(scale(element) + amount, MIN_SCALE, MAX_SCALE);
    }

    /** Back to the shipped arrangement, not to the bare anchors -- see {@link #defaults}. */
    public void reset(Element element) {
        values[element.ordinal()] = defaults(element);
    }

    public void resetAll() {
        for (Element element : Element.values()) {
            reset(element);
        }
    }

    // ------------------------------------------------------------------ screen geometry ---

    /** Top-left corner of the popup, in absolute screen coordinates. */
    public int popupLeft(int screenWidth, int screenHeight) {
        return anchor(Element.POPUP, screenWidth, screenHeight)[0] + (int) dx(Element.POPUP);
    }

    public int popupTop(int screenWidth, int screenHeight) {
        return anchor(Element.POPUP, screenWidth, screenHeight)[1] + (int) dy(Element.POPUP);
    }

    public int popupWidth() {
        return w(Element.POPUP);
    }

    public int popupHeight() {
        return h(Element.POPUP);
    }

    /** Turns a popup-relative coordinate into a screen coordinate, honouring the popup's offset. */
    public int inPopupX(float baseX, int screenWidth, int screenHeight) {
        return popupLeft(screenWidth, screenHeight) + Math.round(baseX * scale(Element.POPUP));
    }

    public int inPopupY(float baseY, int screenWidth, int screenHeight) {
        return popupTop(screenWidth, screenHeight) + Math.round(baseY * scale(Element.POPUP));
    }

    /** On-screen rectangle of an element: {@code x, y, width, height}. */
    public int[] bounds(Element element, int screenWidth, int screenHeight) {
        int[] anchor = anchor(element, screenWidth, screenHeight);
        return new int[]{
                anchor[0] + (int) dx(element),
                anchor[1] + (int) dy(element),
                w(element),
                h(element)
        };
    }

    // ------------------------------------------------------------------ the pile ring ---

    /** Cells around the pile block: one per compass direction, the centre left empty. */
    public static final int PILE_CELLS = 4;

    /**
     * Compass letters, in compass order.
     *
     * <p>{@code 0} north, {@code 1} east, {@code 2} south, {@code 3} west -- clockwise on screen, and
     * also the direction turns run in. A <em>seat</em> is not a compass: Charta rotates the roster
     * around the table at random, so the compass of a seat is read back from the chair its player is
     * actually sitting on ({@code BridgeGame#compass}). The screen is not rotated per viewer, so every
     * player at the table reads the same compass and can say "the north hand" and mean one person.
     */
    public static final String[] COMPASS_LETTERS = {"N", "E", "S", "W"};

    /** The hand element of a compass direction. */
    public static Element hand(int compass) {
        return switch (Math.floorMod(compass, PILE_CELLS)) {
            case 0 -> Element.NORTH_HAND;
            case 1 -> Element.EAST_HAND;
            case 2 -> Element.SOUTH_HAND;
            default -> Element.WEST_HAND;
        };
    }

    /** The name plate of a compass direction. */
    public static Element plate(int compass) {
        return switch (Math.floorMod(compass, PILE_CELLS)) {
            case 0 -> Element.NORTH_PLATE;
            case 1 -> Element.EAST_PLATE;
            case 2 -> Element.SOUTH_PLATE;
            default -> Element.WEST_PLATE;
        };
    }

    /**
     * On-screen box of one pile cell, {@code x, y, width, height}.
     *
     * <p>The pile element's box is the whole three-by-three block, because that is what the drop
     * hit-test covers; the individual cells are cut out of it here so a card can be placed in the one
     * belonging to the seat that played it.
     *
     * @param offset compass direction: 0 north, 1 east, 2 south, 3 west
     */
    public int[] pileCell(int offset, int screenWidth, int screenHeight) {
        int[] block = bounds(Element.PILE, screenWidth, screenHeight);
        int cellWidth = block[2] / 3;
        int cellHeight = block[3] / 3;
        int column = switch (Math.floorMod(offset, PILE_CELLS)) {
            case 1 -> 2;
            case 3 -> 0;
            default -> 1;
        };
        int row = switch (Math.floorMod(offset, PILE_CELLS)) {
            case 0 -> 0;
            case 2 -> 2;
            default -> 1;
        };
        return new int[]{block[0] + column * cellWidth, block[1] + row * cellHeight, cellWidth, cellHeight};
    }

    private int w(Element element) {
        return Math.round(element.baseWidth * scale(element));
    }

    private int h(Element element) {
        return Math.round(element.baseHeight * scale(element));
    }

    /**
     * Default position of an element, before the player's offset.
     *
     * <p>Nothing here hangs off the popup: dragging the panel over the table must not drag the arms with
     * it. Each element is instead anchored to the frame's own edges, so a resize moves them coherently,
     * and a hand's crossways axis comes from a helper that keeps the pair symmetric -- {@link #handAnchor}
     * and {@link #columnAnchor} -- rather than from a corner coordinate. A hand therefore grows away from
     * the table's middle on both sides at once, and its name plate stays with it.
     */
    private int[] anchor(Element element, int screenWidth, int screenHeight) {
        int width = w(element);
        int height = h(element);
        return switch (element) {
            case POPUP -> new int[]{(screenWidth - width) / 2, (screenHeight - height) / 2};
            // Centred on the screen's vertical axis; the pair's mirror puts east symmetrically right.
            case SOUTH_HAND, NORTH_HAND -> handAnchor(element, screenWidth, screenHeight);
            // Hung off the north fan's lower edge, so the ring closes at the top; their length then
            // decides where the bottom lands.
            case WEST_HAND, EAST_HAND -> columnAnchor(element, screenWidth, screenHeight);
            // Below the north hand: above it would be y<0 and off the screen.
            case NORTH_PLATE -> new int[]{(screenWidth - width) / 2, screenHeight - SOUTH_Y + PLATE_GAP};
            case WEST_PLATE -> new int[]{
                    columnLeft(screenWidth) + (w(Element.WEST_HAND) - width) / 2,
                    columnTop(screenHeight) - height - PLATE_GAP};
            case EAST_PLATE -> new int[]{
                    eastHandCentreX(screenWidth) - width / 2,
                    columnTop(screenHeight) - height - PLATE_GAP};
            // Above the south hand, the way the west and east plates are above their columns; below it
            // would run into the bottom bar. The three status lines that share this height are nudged
            // left and right in the saved layout, so the plate gets the middle to itself.
            case SOUTH_PLATE -> new int[]{(screenWidth - width) / 2, SOUTH_Y - height - PLATE_GAP};
            // Table furniture, so it hangs off the north hand rather than off the panel: the panel is
            // hidden for the whole play phase and would otherwise drag the trick along with it. The
            // gap under it is where the play status lines go, so they clear the block entirely.
            case PILE -> new int[]{
                    (screenWidth - width) / 2,
                    screenHeight - SOUTH_Y + PILE_TOP_GAP};
            // Centred on the screen like the pile, and stacked up from the bottom edge so the four
            // lines stay put when the window changes height.
            case STATUS_CONTRACT -> statusAnchor(screenWidth, screenHeight, width, STATUS_CONTRACT_UP);
            case STATUS_TURN -> statusAnchor(screenWidth, screenHeight, width, STATUS_TURN_UP);
            case STATUS_PROGRESS -> statusAnchor(screenWidth, screenHeight, width, STATUS_PROGRESS_UP);
            case STATUS_TRICKS -> statusAnchor(screenWidth, screenHeight, width, STATUS_TRICKS_UP);
        };
    }

    /** Top-left of a status line's handle: horizontally centred, {@code up} pixels above the bottom. */
    private int[] statusAnchor(int screenWidth, int screenHeight, int width, int up) {
        return new int[]{(screenWidth - width) / 2, screenHeight - up};
    }

    /**
     * Top-left of the south fan, or of its north mirror through the horizontal centre line.
     *
     * <p>Centred rather than pinned to a left edge: the fan is 300 of the frame's 640 pixels and sits
     * between the two 38px columns, so an absolute {@code SOUTH_X} would have to be re-derived by hand
     * every time the length changed -- and a saved {@code dx} would then mean something different.
     */
    private int[] handAnchor(Element element, int screenWidth, int screenHeight) {
        int x = (screenWidth - w(element)) / 2;
        return element == Element.SOUTH_HAND
                ? new int[]{x, SOUTH_Y}
                : new int[]{x, screenHeight - SOUTH_Y - h(element)};
    }

    /** Top-left of the west column, or of its east mirror through the vertical centre line. */
    private int[] columnAnchor(Element element, int screenWidth, int screenHeight) {
        int y = columnTop(screenHeight);
        int x = columnLeft(screenWidth);
        return element == Element.WEST_HAND
                ? new int[]{x, y}
                : new int[]{screenWidth - x - w(element), y};
    }

    /**
     * Left edge of the west column: the middle of the strip the fan leaves beside it.
     *
     * <p>The strip is 170px of the frame's 640 -- everything left of the 300px fan -- so the column sits
     * 66px in with an equal 66px between it and the fan. Equal, not "as far out as it goes": the four
     * hands then read as a ring with even gaps, and the arms stop looking like they belong to the panel.
     * Derived from the fan rather than written as 66, so widening the fan pushes the columns out with it
     * instead of leaving them to collide.
     */
    private int columnLeft(int screenWidth) {
        int strip = (screenWidth - w(Element.SOUTH_HAND)) / 2;
        return (strip - w(Element.WEST_HAND)) / 2;
    }

    /** The columns' top edge: the north fan's lower edge, i.e. the south fan's top edge mirrored. */
    private int columnTop(int screenHeight) {
        return screenHeight - SOUTH_Y;
    }

    private int eastHandCentreX(int screenWidth) {
        return screenWidth - columnLeft(screenWidth) - w(Element.EAST_HAND) / 2;
    }

    // ------------------------------------------------------------------ applying ---

    /**
     * Writes the current layout into the menu's card slots, so what the player sees and what the
     * click handling hit-tests agree.
     *
     * <p>Slot order is positional and set by {@link BridgeMenu}: pile, then the seats in
     * {@link BridgeMenu#HAND_ORDER}. Which <em>element</em> a seat's slot gets is its compass, though,
     * not its index: the roster is rotated at random around the table, so seat 0 is only north by
     * accident (see {@code BridgeGame#compass}).
     */
    public void apply(BridgeMenu menu, int screenWidth, int screenHeight) {
        if (menu.cardSlots.size() < 1 + BridgeMenu.HAND_ORDER.length) {
            return;
        }
        setSlot(menu, 0, Element.PILE, screenWidth, screenHeight);
        for (int i = 0; i < BridgeMenu.HAND_ORDER.length; i++) {
            int seat = BridgeMenu.HAND_ORDER[i];
            setSlot(menu, menu.handSlotIndex(i), hand(menu.getGame().compass(seat)), screenWidth, screenHeight);
        }
    }

    /**
     * The one place that knows how {@code GameScreen} stores a slot per type.
     *
     * <p>Its three transforms are not the same space, so each one is undone to land the slot's
     * <em>drawn</em> top-left on {@code box[0], box[1]}:
     * <ul>
     *   <li>{@code PREVIEW} is read as an absolute screen y and drawn at {@code y}.</li>
     *   <li>{@code HORIZONTAL} is a bottom offset, drawn at {@code y + screenHeight - declaredHeight}.
     *       Both the draw and the hover box use the declared height, so subtracting the same expression
     *       keeps the two coincident — a click lands exactly on the card that was painted.</li>
     *   <li>everything else is relative to the centred panel and drawn at {@code y + topPos}.</li>
     * </ul>
     * {@code x} never preview-styles: all three are drawn at {@code leftPos + x}.
     */
    private void setSlot(BridgeMenu menu, int index, Element element, int screenWidth, int screenHeight) {
        CardSlot<?, ?> slot = menu.cardSlots.get(index);
        CardSlotAccess access = (CardSlotAccess) slot;
        int[] box = bounds(element, screenWidth, screenHeight);

        access.bridge$setX(box[0] - menu.panelLeft(screenWidth));
        switch (slot.getType()) {
            case PREVIEW -> access.bridge$setY(box[1]);
            case HORIZONTAL -> access.bridge$setY(box[1] - screenHeight + box[3]);
            default -> access.bridge$setY(box[1] - menu.panelTop(screenHeight));
        }

        access.bridge$setSize(box[2], box[3]);
    }

    // ------------------------------------------------------------------ persistence ---

    private void load() {
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            properties.load(in);
        } catch (IOException ignored) {
            return;
        }
        if (!VERSION.equals(properties.getProperty("version"))) {
            // Written by an older table shape: its offsets measure from different defaults.
            return;
        }
        for (Element element : Element.values()) {
            float[] value = entry(element);
            value[0] = parse(properties, element.key + ".dx", 0f);
            value[1] = parse(properties, element.key + ".dy", 0f);
            value[2] = parse(properties, element.key + ".scale", 0f);
        }
    }

    public void save() {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        for (Element element : Element.values()) {
            float[] value = entry(element);
            properties.setProperty(element.key + ".dx", Float.toString(value[0]));
            properties.setProperty(element.key + ".dy", Float.toString(value[1]));
            properties.setProperty(element.key + ".scale", Float.toString(value[2]));
        }
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                properties.store(out, "Contract Bridge UI layout - edited in game with F9");
            }
        } catch (IOException ignored) {
            // A read-only instance still plays fine, it just forgets the layout.
        }
    }

    private static float parse(Properties properties, String key, float fallback) {
        try {
            return Float.parseFloat(properties.getProperty(key, Float.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

}

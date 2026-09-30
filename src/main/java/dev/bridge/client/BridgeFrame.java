package dev.bridge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * The single coordinate space the Contract Bridge screen is authored in, and how it is mapped onto a
 * window of any size.
 *
 * <h2>Why a fixed frame</h2>
 *
 * <p>Every number in {@link BridgeLayout} -- {@code SOUTH_Y}, {@code PILE_TOP_GAP}, the four status
 * lines' {@code *_UP} -- is an absolute pixel count tuned on a 640x360 screen, which is exactly what a
 * 1920x1080 display yields at GUI scale 3. Written straight onto the window those numbers only hold
 * there: shrink the window and the south hand at y=300 walks off a 240 px screen, and the pile's
 * {@code height - SOUTH_Y} anchor goes <em>negative</em> and leaves the top. "It fits" was a property
 * of one resolution, not of the layout.
 *
 * <p>So the screen paints into a 640x360 <em>design frame</em> and this class maps that frame onto the
 * window: one uniform scale (the smaller of the two axis ratios, so nothing can ever overflow either
 * axis) and a centring origin for the leftover space. The scale is read from the live window on every
 * lookup, so a resize -- or an F11, or a change of GUI scale -- is picked up on the very next frame.
 *
 * <h2>What it buys</h2>
 *
 * <ul>
 *   <li>At 1920x1080 / GUI scale 3 the scale is exactly {@code 1} and the origin {@code (0,0)}: the
 *       painted screen is pixel-for-pixel what it was before this class existed, so a layout dragged
 *       under those conditions stays put.</li>
 *   <li>The frame is uniform, so the composition keeps its shape -- a hand is as far from the pile on
 *       a 4:3 window as on a 16:9 one, just letterboxed instead of stretched.</li>
 *   <li>It is defined against the window, not against the GUI scale, so the table also stops resizing
 *       when the player changes GUI scale: scale 2 doubles the frame scale and the physical result is
 *       identical.</li>
 * </ul>
 *
 * <h2>How the screen uses it</h2>
 *
 * <p>{@code BridgeScreen} re-points its own {@code width}/{@code height} at the frame in
 * {@code init()} and wraps its whole {@code render} in {@link #push}/{@link #pop}, handing the vanilla
 * machinery window-to-frame converted mouse coordinates. Everything downstream then works in frame
 * space for free: {@code AbstractContainerScreen}'s {@code leftPos}/{@code topPos} centring, the card
 * slot widgets, the vanilla buttons, the tooltips. Two things do <em>not</em> follow a
 * {@code PoseStack} and are handled explicitly instead -- {@code GuiGraphics.enableScissor}, which
 * measures in window pixels, and the chat, which vanilla also draws itself at window coordinates.
 */
public final class BridgeFrame {

    /**
     * Design size. 640x360 is a full screen at 1920x1080 with GUI scale 3, i.e. the resolution the
     * whole layout was drawn on -- not a chosen "nice" number.
     */
    public static final int WIDTH = 640;
    public static final int HEIGHT = 360;

    /** Scale limits, so a 320x240 window or an 8K one cannot produce an absurd frame. */
    private static final float MIN_SCALE = 0.2f;
    private static final float MAX_SCALE = 4f;

    private final float scale;
    private final float originX;
    private final float originY;

    /** Frame for a null window, and the value a stale cache falls back to. */
    private static final BridgeFrame UNIT = new BridgeFrame(1f, 0f, 0f);

    /**
     * Last frame handed out, kept because {@link #current()} is called several times per frame and the
     * window essentially never changes between two of those calls.
     *
     * <p>Keyed on the window size rather than invalidated from a resize hook: the window is the only
     * input, every lookup already reads it, and a resize therefore reseeds on the very next call with
     * no listener to register and none to leak.
     */
    @Nullable
    private static BridgeFrame cached;
    private static int cachedWidth = -1;
    private static int cachedHeight = -1;

    private BridgeFrame(float scale, float originX, float originY) {
        this.scale = scale;
        this.originX = originX;
        this.originY = originY;
    }

    /** The frame for the live window, recomputed only when the window size has changed. */
    public static BridgeFrame current() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return UNIT;
        }
        int guiWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();
        if (cached == null || guiWidth != cachedWidth || guiHeight != cachedHeight) {
            cached = of(guiWidth, guiHeight);
            cachedWidth = guiWidth;
            cachedHeight = guiHeight;
        }
        return cached;
    }

    /** The frame that fits a GUI-scaled window of this size, centred. */
    public static BridgeFrame of(int guiWidth, int guiHeight) {
        // The smaller ratio, never the larger: fitting both axes is the whole point, and the cost of
        // the choice is empty margin on one axis instead of content pushed off the other.
        float scale = Mth.clamp(Math.min(guiWidth / (float) WIDTH, guiHeight / (float) HEIGHT),
                MIN_SCALE, MAX_SCALE);
        return new BridgeFrame(scale, (guiWidth - WIDTH * scale) / 2f, (guiHeight - HEIGHT * scale) / 2f);
    }

    // ------------------------------------------------------------------ frame <-> window ---

    /** Window coordinate to frame coordinate, for input the vanilla event system already scaled. */
    public double toFrameX(double guiX) {
        return (guiX - originX) / scale;
    }

    public double toFrameY(double guiY) {
        return (guiY - originY) / scale;
    }

    /** Frame coordinate back to window pixels, for the calls that ignore the pose. */
    public int toGuiX(float frameX) {
        return Math.round(originX + frameX * scale);
    }

    public int toGuiY(float frameY) {
        return Math.round(originY + frameY * scale);
    }

    /** Window length back to a frame length; for scale-invariant things like a drag delta. */
    public double toFrameLength(double guiLength) {
        return guiLength / scale;
    }

    /**
     * Rewrites a raw {@code MouseHandler} position into the value
     * {@code GameScreen.containerTick} needs.
     *
     * <p>That method computes its mouse as {@code xpos() * guiScaledWidth / screenWidth}, i.e. it
     * <em>assumes</em> the result is a window coordinate. The only way to make it produce a frame
     * coordinate without touching the method body is to hand it a different {@code xpos()}: solving
     * {@code X * guiW / screenW = frameX} gives the expression below. Vanilla uses the same number for
     * the vanilla widgets it ticks, so both ends of that method stay consistent with each other.
     */
    public static double tickX(double rawX) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return rawX;
        }
        return scaleInto(rawX, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getScreenWidth(), true);
    }

    /** See {@link #tickX}; the vertical twin. */
    public static double tickY(double rawY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return rawY;
        }
        return scaleInto(rawY, minecraft.getWindow().getGuiScaledHeight(), minecraft.getWindow().getScreenHeight(), false);
    }

    private static double scaleInto(double raw, int guiSize, int screenSize, boolean horizontal) {
        BridgeFrame frame = current();
        if (guiSize == 0 || screenSize == 0 || frame.scale == 0f) {
            return raw;
        }
        double framePosition = (raw * guiSize / screenSize - (horizontal ? frame.originX : frame.originY)) / frame.scale;
        return framePosition * screenSize / guiSize;
    }

    // ------------------------------------------------------------------ pose ---

    /** Enters frame space: everything drawn until {@link #pop} uses the design coordinates. */
    public void push(GuiGraphics guiGraphics) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(originX, originY, 0f);
        guiGraphics.pose().scale(scale, scale, 1f);
    }

    public void pop(GuiGraphics guiGraphics) {
        guiGraphics.pose().popPose();
    }

    // ------------------------------------------------------------------ window size ---

    /** Live GUI-scaled window size, which is <em>not</em> the frame size. */
    public static int windowWidth() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.getWindow() == null
                ? WIDTH : minecraft.getWindow().getGuiScaledWidth();
    }

    public static int windowHeight() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.getWindow() == null
                ? HEIGHT : minecraft.getWindow().getGuiScaledHeight();
    }
}

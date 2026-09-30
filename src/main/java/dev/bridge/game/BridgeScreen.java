package dev.bridge.game;

import dev.bridge.client.BridgeFrame;
import dev.bridge.client.BridgeLayout;
import dev.bridge.network.BridgeCallPayload;
import dev.lucaargolo.charta.client.ChartaModClient;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.utils.ChartaGuiGraphics;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.List;

/**
 * Contract Bridge screen. Extending {@link GameScreen} is what gives an addon the full Charta look and
 * feel: animated card widgets, drag/drop with hover lift, the history panel, the options panel, the
 * player bar and the blurred backdrop are all inherited.
 *
 * <h2>Two layouts in one screen</h2>
 *
 * <p>While the auction runs a modal panel covers the middle — a frosted plate with the four seats and
 * what they have called so far at the top, a 7x5 grid of level/strain buttons under it and pass,
 * double and redouble below. The card table itself keeps ticking behind it, so the auction reads as a
 * popup over the live table rather than a separate screen. When the auction ends the plate turns into a
 * plain backdrop for the trick view: the pile in the middle, the other three seats around it, and the
 * local hand along the bottom.
 *
 * <h2>Every position comes from {@link BridgeLayout}</h2>
 *
 * <p>No coordinate is written twice. The layout owns the popup and the four hands, the card slots are
 * driven from the same values, and F9 exposes the whole thing for dragging and scaling, so the painted
 * decorations and the clickable slots can never drift apart.
 *
 * <h2>One design frame, scaled onto the window</h2>
 *
 * <p>Every one of those coordinates is an absolute pixel count measured on a 640x360 screen -- which is
 * what a 1920x1080 display gives at GUI scale 3, the setup the whole table was drawn on. Rather than
 * sprinkle window-size arithmetic through the drawing, this screen <em>is</em> 640x360: {@link #init}
 * re-points {@code width}/{@code height} at the design frame and {@link #render} wraps the entire
 * vanilla pass in a uniform scale and centring translate from {@link BridgeFrame}. Shrinking the
 * window, switching to fullscreen or changing the GUI scale therefore only ever changes one number, and
 * the table can no longer walk off the edge of a short window.
 *
 * <p>Two consequences worth knowing when reading the rest of this file:
 * <ul>
 *   <li>{@code width} and {@code height} here are the <em>frame</em> size, i.e. always
 *       {@link BridgeFrame#WIDTH} x {@link BridgeFrame#HEIGHT}. Window measurements come from
 *       {@link BridgeFrame#windowWidth()}/{@link BridgeFrame#windowHeight()} instead.</li>
 *   <li>Input arrives in window coordinates and is converted on entry, so everything past the
 *       {@code mouse*} overrides is in frame space. The two things a {@code PoseStack} cannot carry --
 *       scissor rectangles and the chat -- are handled explicitly where they are used.</li>
 * </ul>
 *
 * The bidding box is drawn by hand rather than made of {@code Button} widgets. Thirty-eight buttons of
 * two glyphs each would be a lot of widgets for a grid that is entirely uniform, and hand drawing also
 * makes it trivial to grey out the calls the auction does not accept: legality comes from
 * {@link BridgeMenu#mirrorAuction()}, i.e. from the same rules the server enforces, so the box cannot
 * offer a call that would be rejected.
 *
 * <p>The compass is <em>not</em> relative to the local player, and it is not the seat index either:
 * Charta hands the game its players rotated to a random start, so which chair a seat is in has to be
 * read back off the world. Everything here that names a side therefore goes through
 * {@code BridgeGame#compass}, so north really is whoever is sitting north -- and the screen itself is
 * never rotated, so everybody reads the same picture and can say "the east hand" and mean the same
 * person. Only the wording moves with the viewer: the local player's own plate and auction row read
 * "you".
 *
 * <p>That shared picture is the whole design, and it has one visible cost: a player sitting east holds
 * their own cards as an upright column on the right rather than a fan along the bottom.
 */
public class BridgeScreen extends GameScreen<BridgeGame, BridgeMenu> {

    private static final int PLATE_BG = 0x99000000;
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int DIM_COLOR = 0xFF8A8A8A;
    private static final int ACTIVE = 0xFFFFE97F;

    private static final int ROW_BG = 0x66000000;
    private static final int ROW_ACTIVE = 0x88FFE97F;
    private static final int CELL_BG = 0x50000000;
    private static final int CELL_HOVER = 0x60FFE97F;
    private static final int CELL_ILLEGAL = 0x28000000;
    private static final int OUTLINE = 0x60FFFFFF;

    /**
     * The compass letters in the trick cells.
     *
     * <p>Near black rather than {@link #DIM_COLOR}: the cells only lay a thin scrim over the table, so
     * the letters sit on light felt and a pale grey washes out. Drawn without a drop shadow too — a
     * shadow is a darkened copy of the letter, which under a dark letter on a light background is
     * invisible and only blurs the strokes.
     */
    private static final int PILE_LETTER = 0xFF262626;

    // Collecting a finished trick. The server holds it on the table for 25 ticks (1.25s); the whole
    // sweep is timed to land just inside that, whatever the frame rate, so the last frame is already
    // the shrunken stack at the winner instead of a pop when the pile is cleared.
    /** Gap between one card starting to gather and the next, so they visibly follow one another. */
    private static final float SWEEP_STAGGER_MS = 55f;
    /** One card's travel from its cell to the middle of the block. */
    private static final float SWEEP_GATHER_MS = 280f;
    /** When the gathered stack leaves the middle: after the last card has arrived. */
    private static final float SWEEP_FLY_AT_MS = SWEEP_STAGGER_MS * 3f + SWEEP_GATHER_MS;
    /** The stack's travel from the middle to the winner's hand. */
    private static final float SWEEP_FLY_MS = 600f;
    /** How small the stack is when it reaches the hand. */
    private static final float SWEEP_END_SCALE = 0.55f;

    /**
     * Vertical extent a bid's text is centred against, which is <em>not</em> how tall a suit glyph is.
     *
     * <p>The atlas is pasted from the source art at 1:1 -- {@code tools/make-suit-font.ps1} copies it
     * pixel for pixel, so {@code "height": 12} in the provider equals its 12 px cell and the spade really
     * is 12 px tall. But a suit's glyph box is {@code [ascent - height, ascent]} = {@code [-3, 9]} and a
     * 12 px spade starts at its very top, so its ink spans line-box rows -3..8. A vanilla digit's ink
     * spans -1..6, i.e. the <em>same</em> centre, 2.5. Handing {@code drawCallButton} the digit's 8 px
     * therefore centres a suit and a {@code PASS} on the same baseline, with no fudge for the extra
     * height; the old 10 was a leftover from when the atlas was squashed to a 10 px band.
     */
    private static final int SUIT_GLYPH = 8;

    /** Wall-clock start of the running sweep, only meaningful while {@link #sweepWinner} is set. */
    private long sweepStart;
    /** Seat being collected towards, or {@code -1} when no trick is being swept. */
    private int sweepWinner = -1;

    /**
     * Full-screen scrim laid over the blurred world before anything else is painted. This is the whole
     * trick behind the frost: the blur pass itself writes back the same bright frame, so raw blur is
     * nearly invisible against a mostly dark table. A single translucent sheet across the <em>entire</em>
     * screen turns it into readable frosted glass, and it is deliberately light (about 42% black) so the
     * smeared cards behind still show through.
     */
    private static final int SCRIM = 0x6B101014;

    /** Second, lighter scrim, popup-shaped only, so the plate reads as a raised sheet over the frost. */
    private static final int BACKDROP = 0x59101014;

    /**
     * Direction words, indexed by compass: 0 north, 1 east, 2 south, 3 west.
     *
     * <p>Not to be confused with {@link BridgeLayout#COMPASS_LETTERS}, which is the same table in its
     * one-letter form for the pile cells; both are index-by-compass and neither is index-by-seat.
     */
    private static final String[] COMPASS = {"north", "east", "south", "west"};

    /**
     * Call text and its drawn width, memoised per call code.
     *
     * <p>The bidding box is 35 cells and every one of them is redrawn every frame, so without this the
     * screen builds and shapes roughly forty components per frame just to write "3&#9829;" over and over.
     * {@link BridgeBid#MAX_CODE} is 37, so a flat array indexed by the code is enough and the lookup is
     * an array read.
     *
     * <p>Widths are cached alongside the text because measuring is the expensive half -- {@code
     * Font.width} splits and shapes the component. {@code PASS} / {@code X} / {@code XX} are the only
     * codes whose width depends on the language, which is why the cache is dropped whenever the
     * {@link Language} instance changes; see {@link #bidLabel}.
     */
    private final Component[] bidLabels = new Component[BridgeBid.MAX_CODE + 1];
    private final int[] bidWidths = new int[bidLabels.length];
    /** Language the cache was built under, so a reload cannot leave a stale measured width behind. */
    private Language labelLanguage;

    /**
     * The text of {@code call}, memoised.
     *
     * <p>A code outside the bidding range falls through to a fresh component: the auction mirror uses
     * those for "nothing here yet", and they are read a handful of times a frame at most.
     */
    private Component bidLabel(int call) {
        if (call < 0 || call >= bidLabels.length) {
            return BridgeBid.name(call);
        }
        Language language = Language.getInstance();
        if (language != labelLanguage) {
            labelLanguage = language;
            Arrays.fill(bidLabels, null);
        }
        Component cached = bidLabels[call];
        if (cached == null) {
            cached = BridgeBid.name(call);
            bidLabels[call] = cached;
            bidWidths[call] = font.width(cached);
        }
        return cached;
    }

    /** Drawn width of {@link #bidLabel}, measured once. */
    private int bidWidth(int call) {
        if (call < 0 || call >= bidWidths.length) {
            return font.width(BridgeBid.name(call));
        }
        bidLabel(call);
        return bidWidths[call];
    }

    // Auction table geometry, relative to the popup's own top-left corner. The table is a grid: a
    // header row of call ordinals over four seat rows, so "which call number is this" and "who made
    // it" line up as a row and a column instead of having to be read out of a running sentence.
    private static final int TABLE_HEAD_TOP = 18;
    private static final int TABLE_HEAD_H = 10;
    private static final int TABLE_TOP = TABLE_HEAD_TOP + TABLE_HEAD_H;
    private static final int TABLE_ROW_H = 12;
    /** Left edge of the first round column; the compass label owns everything to the left of it. */
    private static final int CALL_X = 26;
    /** Round pitch: one column, wide enough for the longest thing a cell can hold, {@code pass}. */
    private static final int CALL_COL_W = 27;
    /** Margin between the last round column and the panel's inner edge. */
    private static final int CALL_RIGHT = 6;

    // Bidding box geometry, below the table. BOX_X is 24 so the 5 x 24 px grid is centred in the panel.
    private static final int BOX_X = 24;
    private static final int CELL_W = 24;
    private static final int CELL_H = 13;
    private static final int STRAIN_COLUMNS = BridgeBid.STRAINS;
    private static final int LEVEL_ROWS = BridgeBid.MAX_LEVEL;
    private static final int BOX_Y = TABLE_TOP + BridgeGame.PLAYERS * TABLE_ROW_H + 2;
    private static final int PASS_Y = BOX_Y + LEVEL_ROWS * CELL_H + 2;
    private static final int PASS_H = 12;
    private static final int PASS_W = 38;

    private static final int FRAME = 2;

    /** Drawn height of one status line, used to centre it in its layout handle. */
    private static final int STATUS_TEXT_H = 9;

    /**
     * How long the finished auction's result stays up before the plate goes away and the table returns.
     *
     * <p>A call takes a beat to read -- "4 spades doubled, by North, vulnerable" is four facts arriving
     * at once -- and the plate used to vanish on the exact frame the last pass landed, taking the whole
     * auction record with it.
     */
    private static final long AUCTION_RESULT_MS = 2000L;

    /** The whole screen layout, shared by every screen instance and editable with F9. */
    private static final BridgeLayout LAYOUT = new BridgeLayout();

    private boolean editing;
    private BridgeLayout.Element selected;
    private BridgeLayout.Element hovered;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    /** Wall-clock the auction ended at, or {@code 0} while none has, or a new one is running. */
    private long auctionEndedAt;
    /** Phase seen on the previous tick, so the moment the auction ends can be spotted. */
    private BridgeGame.Phase lastPhase;

    public BridgeScreen(BridgeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = BridgeMenu.PANEL_WIDTH;
        this.imageHeight = BridgeMenu.PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        // width/height arrive here as the real GUI-scaled window size; from this point on this screen
        // paints inside the 640x360 design frame, so they are re-pointed at it -- and done *before*
        // super.init(), because AbstractContainerScreen derives leftPos/topPos from them, GameScreen
        // places its corner buttons off them, and the chat screen is initialised with them. Re-pointing
        // everything at once is what lets every existing formula in this file keep reading width/height.
        this.width = BridgeFrame.WIDTH;
        this.height = BridgeFrame.HEIGHT;
        // GameScreen rebuilds its slot widgets in init(), and a widget caches the slot position it was
        // built with, so publish the layout first. It only needs the window size, not leftPos/topPos,
        // which super.init() is the thing that computes.
        applyLayout();
        super.init();
    }

    /** Pushes the layout into the menu's card slots. Cheap, so it runs on every resize and edit. */
    private void applyLayout() {
        LAYOUT.apply(menu, width, height);
    }

    /**
     * Spots the instant the auction ends, so its result can be held on screen.
     *
     * <p>Nothing in the synced state says "the auction just finished"; {@code phase} simply becomes
     * {@code PLAY}, and the plate used to vanish on that very frame. {@code DEALING} counts as an ending
     * too, because a board where everybody passed is re-dealt straight away rather than played.
     */
    @Override
    public void containerTick() {
        super.containerTick();
        BridgeGame.Phase phase = menu.getPhase();
        if (phase == lastPhase) {
            return;
        }
        if (lastPhase == BridgeGame.Phase.AUCTION
                && (phase == BridgeGame.Phase.PLAY || phase == BridgeGame.Phase.DEALING)) {
            auctionEndedAt = Util.getMillis();
        } else if (phase == BridgeGame.Phase.AUCTION) {
            // A fresh auction; the previous result's linger is over.
            auctionEndedAt = 0L;
        }
        lastPhase = phase;
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    /**
     * Whole-screen blur, then the auction plate on top of it.
     *
     * <h2>Why the blur is run twice</h2>
     *
     * <p>{@code GameScreen.render} already runs the vanilla blur pass, but that pass has a hard guard in
     * {@code GameRenderer.processBlurEffect}: it returns immediately unless
     * {@code options.getMenuBackgroundBlurriness() >= 1}, and even at the default value the kernel is a
     * modest five pixels, which the dark table swallows. Re-running the pass smears the already smeared
     * frame, roughly doubling the radius, and costs one fullscreen quad. It has to happen before
     * {@code super.render}, i.e. before the top bar, the bottom bar and the cards are painted, so those
     * stay crisp on top of the frost — which is exactly the "blur the screen, then open the popup"
     * ordering.
     *
     * <h2>The frame</h2>
     *
     * <p>The whole vanilla pass runs inside {@link BridgeFrame#push}, with the mouse converted on the
     * way in: from there down, "the screen" is 640x360 and the window is an implementation detail. The
     * frost is the one thing painted outside it, because it has to cover the letterbox margins too --
     * the glass belongs to the window, the table to the frame. For the same reason the chat is painted
     * after the pose is popped: vanilla draws its own copy in window coordinates and the two must agree.
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        BridgeFrame frame = BridgeFrame.current();

        if (isAuctionPlate()) {
            renderBlurredBackground(partialTick);
            guiGraphics.fill(0, 0, BridgeFrame.windowWidth(), BridgeFrame.windowHeight(), SCRIM);
        }

        frame.push(guiGraphics);
        super.render(guiGraphics,
                (int) Math.floor(frame.toFrameX(mouseX)),
                (int) Math.floor(frame.toFrameY(mouseY)),
                partialTick);
        frame.pop(guiGraphics);

        renderChat(guiGraphics, mouseX, mouseY);
    }

    /**
     * The chat, in window coordinates, exactly where {@code GameScreen.render} used to put it.
     *
     * <p>{@code GameScreenChatFrame} suppresses the inherited draw so this can happen outside the frame
     * pose; see that class for why the frame is the wrong place for it. The width squeeze is copied
     * verbatim from there, only measured against the window rather than the (now frame-sized)
     * {@code width}.
     */
    private void renderChat(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (minecraft == null || minecraft.gui.getChat().isChatFocused()) {
            // When the chat is focused the input box repaints the history itself, from the other branch
            // of GameScreen.render, and that one stays inside the frame.
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0f, -25f, 0f);
        double chatWidth = minecraft.options.chatWidth().get();
        double newChatWidth = Math.min(chatWidth * 280.0,
                (BridgeFrame.windowWidth() / 2.0) - (imageWidth / 2.0) - 50.0) / 280.0;
        if (newChatWidth > 0.0 && newChatWidth < 1) {
            minecraft.options.chatWidth().set(newChatWidth);
        }
        minecraft.gui.getChat().render(guiGraphics, minecraft.gui.getGuiTicks(), mouseX, mouseY + 25, false);
        minecraft.options.chatWidth().set(chatWidth);
        guiGraphics.pose().popPose();
    }

    private boolean isAuction() {
        return menu.getPhase() == BridgeGame.Phase.AUCTION
                && menu.getGame().getPlayers().size() == BridgeGame.PLAYERS;
    }

    /** Whether the auction plate is on screen: for the auction itself, and for a beat after it ends. */
    private boolean isAuctionPlate() {
        return isAuction() || auctionResultShowing();
    }

    /** Whether the auction's outcome is the thing on the plate right now; see {@link #AUCTION_RESULT_MS}. */
    private boolean auctionResultShowing() {
        return auctionEndedAt > 0L && Util.getMillis() - auctionEndedAt < AUCTION_RESULT_MS;
    }

    /**
     * The top bar is dropped entirely.
     *
     * <p>Its three bands and four wool-framed heads occupy the full width of the top 28px, which is
     * exactly where the mirrored north hand now sits; and the seat name plates already carry the colour
     * that the wool frames were showing.
     */
    @Override
    public void renderTopBar(@NotNull GuiGraphics guiGraphics) {
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        boolean auction = isAuctionPlate();
        boolean result = auctionResultShowing();

        if (auction) {
            renderPopup(guiGraphics, mouseX, mouseY, result);
        }
        // Only while a trick is actually live: during the deal there is no trick yet and the status
        // lines sit in the middle, at the result the summary panel lands on top of the block, and behind
        // the auction plate the block is still empty.
        if (menu.getPhase() == BridgeGame.Phase.PLAY && !auction) {
            renderPile(guiGraphics);
        }
        renderSeats(guiGraphics);
        if (!auction) {
            renderPhaseText(guiGraphics);
        }
        if (editing) {
            renderEditor(guiGraphics);
        }
    }

    // ------------------------------------------------------------------- popup ---

    /**
     * The modal auction plate: a full-screen frost first, then the auction table and the bidding box on
     * top of it.
     *
     * <h2>Two frosted layers, in this order</h2>
     *
     * <p>1. A {@link #SCRIM} fill over the <em>whole window</em>, painted by {@link #render} after the
     * blur passes have run and before the frame pose goes on. This is what the player actually perceives
     * as blur — without it the smeared world just looks like a slightly soft version of itself. It is
     * deliberately outside the frame so the letterbox margins are frosted too.
     *
     * <p>2. A {@link #BACKDROP} fill clipped to the plate, so the plate sits one notch darker than the
     * frost around it. Both are light on purpose: stacking a 30-45% black panel texture on top of an 78%
     * backdrop used to leave the world at roughly 4% and the frost had nothing to show through.
     */
    private void renderPopup(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean result) {
        int x = LAYOUT.popupLeft(width, height);
        int y = LAYOUT.popupTop(width, height);
        int right = x + LAYOUT.popupWidth();
        int bottom = y + LAYOUT.popupHeight();

        // Scissor is the one GuiGraphics call a PoseStack does not carry: GuiGraphics.applyScissor
        // multiplies the rectangle by the window's own GUI scale and hands it to RenderSystem, so it
        // has to be given window pixels even though everything around it is in frame space.
        BridgeFrame frame = BridgeFrame.current();
        guiGraphics.enableScissor(frame.toGuiX(x), frame.toGuiY(y), frame.toGuiX(right), frame.toGuiY(bottom));
        guiGraphics.fill(x, y, right, bottom, BACKDROP);
        guiGraphics.disableScissor();

        guiGraphics.fill(x, y, right, y + FRAME, OUTLINE);
        guiGraphics.fill(x, bottom - FRAME, right, bottom, OUTLINE);
        guiGraphics.fill(x, y, x + FRAME, bottom, OUTLINE);
        guiGraphics.fill(right - FRAME, y, right, bottom, OUTLINE);

        Auction auction = menu.mirrorAuction();

        Component title = result
                ? Component.translatable("message.bridge.auction_over").withStyle(ChatFormatting.GOLD)
                : menu.canCallNow()
                ? Component.translatable("message.bridge.your_call").withStyle(ChatFormatting.GOLD)
                : Component.translatable("message.bridge.call_in_progress",
                        name(menu.getCurrentSeat(), "seat.bridge."));
        drawInPopup(guiGraphics, title, (BridgeMenu.PANEL_WIDTH - font.width(title)) / 2, 8);

        renderAuctionTable(guiGraphics, auction);
        if (result) {
            renderAuctionResult(guiGraphics, auction);
        } else {
            renderBiddingBox(guiGraphics, auction, mouseX, mouseY);
        }
    }

    /**
     * The auction's outcome, in the plate's lower half where the bidding box was.
     *
     * <p>Up for {@link #AUCTION_RESULT_MS} after the last call, under the frozen auction table, so the
     * contract gets read as a result instead of the plate blinking out of existence the instant the
     * third pass lands. It is the same three facts the contract line carries for the rest of the board --
     * strain, declarer, vulnerability -- only big enough to be the focus rather than a footnote.
     */
    private void renderAuctionResult(GuiGraphics guiGraphics, Auction auction) {
        int centre = BridgeMenu.PANEL_WIDTH / 2;
        int top = BOX_Y;
        int bottom = PASS_Y + PASS_H;
        int middle = (top + bottom) / 2;

        guiGraphics.fill(inPopupX(3), inPopupY(top - 5), inPopupX(BridgeMenu.PANEL_WIDTH - 3),
                inPopupY(top - 5) + 1, OUTLINE);

        if (!auction.hasContract()) {
            drawInPopupCentred(guiGraphics, Component.translatable("message.bridge.passed_out_short"),
                    centre, middle, DIM_COLOR, 1f);
            return;
        }

        Component headline = bidLabel(menu.getContract());
        // A fresh root rather than headline.copy(): the copy would carry the suit font down to the
        // doubling, and "加倍" is not a suit.
        headline = Component.empty().append(headline).append(doubling(auction.doubling()));
        drawInPopupCentred(guiGraphics, headline, centre, middle - 20, ACTIVE, 2f);

        drawInPopupCentred(guiGraphics,
                Component.translatable("message.bridge.declared_by", name(menu.getDeclarerSeat(), "seat.bridge.")),
                centre, middle + 4, 0xFFFFFFFF, 1f);

        boolean vulnerable = menu.getGame().isVulnerable();
        drawInPopupCentred(guiGraphics, vulnerability(), centre, middle + 20,
                vulnerable ? 0xFFFF7070 : DIM_COLOR, 1f);
    }

    /** "有局" or "无局": how the declaring side is scored this board. */
    private Component vulnerability() {
        return Component.translatable(menu.getGame().isVulnerable()
                ? "message.bridge.vulnerable" : "message.bridge.not_vulnerable");
    }

    /** "加倍" / "再加倍", or nothing when the contract stands. */
    private static Component doubling(int doubling) {
        return switch (doubling) {
            case 1 -> Component.translatable("bid.bridge.double");
            case 2 -> Component.translatable("bid.bridge.redouble");
            default -> Component.empty();
        };
    }

    /** {@code text} centred on the popup's {@code popupCentreX}, optionally blown up by {@code scale}. */
    private void drawInPopupCentred(GuiGraphics guiGraphics, Component text, int popupCentreX, int popupY,
                                    int color, float scale) {
        int x = inPopupX(popupCentreX) - Math.round(font.width(text) * scale) / 2;
        int y = inPopupY(popupY);
        if (scale == 1f) {
            guiGraphics.drawString(font, text, x, y, color, true);
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0d);
        guiGraphics.pose().scale(scale, scale, 1f);
        guiGraphics.drawString(font, text, 0, 0, color, true);
        guiGraphics.pose().popPose();
    }

    /**
     * One header row of round numbers over one row per seat, each call centred in the cell its seat
     * and its round cross at.
     *
     * <h2>Why a column is a whole round</h2>
     *
     * <p>A call is not a useful unit to number: four seats call before the auction comes back round to
     * anyone, so "call number 7" is a count nobody keeps. A column here is one <em>round</em> of four
     * calls. That also makes the grid exactly one call per (seat, round) cell -- every seat calls once
     * per round -- so no cell can ever have to hold two things side by side.
     *
     * <h2>Why the window is pinned to the last round</h2>
     *
     * <p>An auction can run to a dozen rounds and no fixed-width panel fits them all, so what is shown
     * is the tail. Anchoring at the end rather than the start means the number in the header is the
     * real round number, the newest call is always on screen, and nothing already read shifts sideways
     * when the next call lands.
     *
     * <p>The four rows are in compass order -- north, east, south, west -- and not rotated per viewer,
     * so every player reads the same grid in the same order with their own row marked "you".
     */
    private void renderAuctionTable(GuiGraphics guiGraphics, Auction auction) {
        int width = BridgeMenu.PANEL_WIDTH;
        int columns = callColumns();
        int rounds = (auction.size() + BridgeGame.PLAYERS - 1) / BridgeGame.PLAYERS;
        int firstRound = Math.max(0, rounds - columns);
        int columnW = Math.round(CALL_COL_W * popupScale());

        for (int column = 0; column < columns; column++) {
            int round = firstRound + column;
            if (round >= rounds) {
                break;
            }
            Component ordinal = Component.literal(Integer.toString(round + 1));
            // The current round is picked out, since with a windowed grid it is the one thing that says
            // where in the auction you are.
            guiGraphics.drawString(font, ordinal, callColumnX(column) + (columnW - font.width(ordinal)) / 2,
                    inPopupY(TABLE_HEAD_TOP),
                    round == rounds - 1 ? ACTIVE : DIM_COLOR, true);
        }
        guiGraphics.fill(inPopupX(3), inPopupY(TABLE_TOP - 1), inPopupX(width - 3),
                inPopupY(TABLE_TOP - 1) + 1, OUTLINE);

        for (int row = 0; row < BridgeGame.PLAYERS; row++) {
            // Rows are compasses, so the grid reads north to west down the panel no matter which seat
            // the roster happens to have put on the north chair.
            int seat = menu.getGame().seatAtCompass(row);
            int y = inPopupY(TABLE_TOP + row * TABLE_ROW_H);
            boolean active = menu.canCallNow() && seat == menu.getLocalSeat();
            guiGraphics.fill(inPopupX(3), y, inPopupX(width - 3), y + TABLE_ROW_H - 1,
                    active ? ROW_ACTIVE : ROW_BG);

            Component label = Component.translatable(seat == menu.getLocalSeat()
                    ? "seat.bridge.here" : "seat.bridge." + COMPASS[row]);
            guiGraphics.drawString(font, label, inPopupX(6), y + 2, active ? 0xFF202020 : LABEL_COLOR, true);
        }

        // One pass over the visible tail. A call's round is its index divided by the table size, and the
        // four calls of a round always belong to four different seats, so nothing can collide.
        for (int index = firstRound * BridgeGame.PLAYERS; index < auction.size(); index++) {
            int column = index / BridgeGame.PLAYERS - firstRound;
            if (column >= columns) {
                break;
            }
            int seat = Math.floorMod(auction.seatOf(index), BridgeGame.PLAYERS);
            int y = inPopupY(TABLE_TOP + compass(seat) * TABLE_ROW_H);
            Component call = bidLabel(auction.code(index));
            // The glyphs are pasted at native size and centred on the same line-box row as the 8 px
            // vanilla digits, so +2 gives the call line the identical baseline as the seat label.
            guiGraphics.drawString(font, call, callColumnX(column) + (columnW - bidWidth(auction.code(index))) / 2,
                    y + 2, LABEL_COLOR, true);
        }
    }

    /** How many round columns fit between the label gutter and the panel's inner edge. */
    private static int callColumns() {
        return Math.max(1, (BridgeMenu.PANEL_WIDTH - CALL_X - CALL_RIGHT) / CALL_COL_W);
    }

    /** Left edge of call column {@code column}, in absolute screen coordinates. */
    private int callColumnX(int column) {
        return inPopupX(CALL_X + column * CALL_COL_W);
    }

    /** The bidding box: seven levels down the side, five strains across, pass/double/redouble below. */
    private void renderBiddingBox(GuiGraphics guiGraphics, Auction auction, int mouseX, int mouseY) {
        boolean myTurn = menu.canCallNow();
        int seat = menu.getLocalSeat();

        for (int row = 0; row < LEVEL_ROWS; row++) {
            int level = BridgeBid.MAX_LEVEL - row;
            for (int column = 0; column < STRAIN_COLUMNS; column++) {
                int call = BridgeBid.code(level, column);
                drawCallButton(guiGraphics, BOX_X + column * CELL_W, BOX_Y + row * CELL_H,
                        CELL_W - 1, CELL_H - 1, call, auction, seat, myTurn, mouseX, mouseY);
            }
        }

        int passX = (BridgeMenu.PANEL_WIDTH - (PASS_W * 3 + 4)) / 2 + 1;
        int[] calls = {BridgeBid.PASS, BridgeBid.DOUBLE, BridgeBid.REDOUBLE};
        for (int i = 0; i < calls.length; i++) {
            drawCallButton(guiGraphics, passX + i * (PASS_W + 2), PASS_Y, PASS_W, PASS_H,
                    calls[i], auction, seat, myTurn, mouseX, mouseY);
        }

        if (!myTurn) {
            return;
        }
        // A hint about the call the pointer is over, so nothing has to be guessed. Up on the title line
        // rather than above the pass row: a suit glyph is 12px tall and would sit inside the last row of
        // the bidding box if it were drawn just above the buttons.
        int hovered = callAt(mouseX, mouseY);
        if (hovered >= 0 && auction.isLegal(hovered, seat)) {
            guiGraphics.drawString(font, bidLabel(hovered), inPopupX(4), inPopupY(8), ACTIVE, true);
        }
    }

    private void drawCallButton(GuiGraphics guiGraphics, int boxX, int boxY, int boxW, int boxH, int call,
                                Auction auction, int seat, boolean myTurn, int mouseX, int mouseY) {
        int x = inPopupX(boxX);
        int y = inPopupY(boxY);
        int w = Math.round(boxW * popupScale());
        int h = Math.round(boxH * popupScale());
        boolean legal = auction.isLegal(call, seat);
        boolean enabled = legal && myTurn;
        boolean isHovered = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;

        guiGraphics.fill(x, y, x + w, y + h, !legal ? CELL_ILLEGAL : isHovered ? CELL_HOVER : CELL_BG);
        if (isHovered || (!legal && myTurn)) {
            guiGraphics.fill(x, y, x + w, y + 1, OUTLINE);
        }

        Component text = bidLabel(call);
        // Pass, double and redouble are words in the vanilla font; only a contract carries a suit glyph.
        int glyph = call < BridgeBid.FIRST_CONTRACT ? 8 : SUIT_GLYPH;
        guiGraphics.drawString(font, text, x + (w - bidWidth(call)) / 2 + 1, y + (h - glyph) / 2 + 1,
                !legal ? DIM_COLOR : 0xFFFFFFFF, true);
    }

    // -------------------------------------------------------------------- pile ---

    /**
     * The trick, one cell per compass direction.
     *
     * <h2>Why this is painted rather than left to Charta</h2>
     *
     * <p>A card slot draws its contents at a single point, so the slot that carries the pile would stack
     * all four cards of a trick on the same spot. The pile slot therefore reports itself empty (see
     * {@code BridgeGame.PileSlot}), which keeps its widget out of the way, and the four cells are drawn
     * here instead. The slot keeps working as the drop target because the hit-test uses its declared box,
     * which is the whole three-by-three block: the ring is a picture, the block is the button.
     *
     * <h2>Which card goes where</h2>
     *
     * <p>Entry {@code i} of the pile was played by the seat {@code i} places clockwise from the trick's
     * leader, and each card is dropped in the cell of the <em>compass</em> that seat is sitting on, so
     * the trick reads from the same side of the table as the player who is on it. A card is only drawn
     * once its seat is known, hence the {@code -1} guard: an empty pile has no leader.
     *
     * <h2>Collecting the trick</h2>
     *
     * <p>Once the fourth card is down the server publishes the winning seat (see
     * {@link BridgeGame#SYNC_TRICK_WINNER}) and then leaves the finished trick on the table for
     * {@code TRICK_LINGER} ticks. That window is spent animating: the four cards are gathered into the
     * middle of the block, staggered so they visibly follow one another, and the stack is then thrown to
     * the winner's hand while it shrinks. The whole thing runs off {@link Util#getMillis} rather than
     * {@code GameScreen}'s tick, so it stays smooth at any frame rate and needs no state on the menu. It
     * finishes comfortably inside the linger, so the cards are already off the table by the time the
     * server clears the pile.
     */
    private void renderPile(GuiGraphics guiGraphics) {
        // The seat on turn gets its own cell lit, so "who are we waiting for" is readable off the table
        // itself and not only off their name plate. Once the fourth card is down the seat that opened
        // the trick is no longer who the pile belongs to, so the light goes out for the linger and the
        // sweep takes over as the cue -- which is exactly the window SYNC_TRICK_WINNER is published for.
        CardPlayer active = menu.getTrickWinnerSeat() < 0
                ? menu.getPlayerAtSeat(menu.getCurrentSeat()) : null;
        int activeCell = active == null ? -1 : compass(menu.getCurrentSeat());

        for (int compass = 0; compass < BridgeLayout.PILE_CELLS; compass++) {
            int[] cell = LAYOUT.pileCell(compass, width, height);
            boolean isActive = compass == activeCell;

            guiGraphics.fill(cell[0], cell[1], cell[0] + cell[2], cell[1] + cell[3], CELL_BG);
            if (isActive) {
                // Alpha only: the hue is the seat colour the name plates already use, so the two read
                // as the same person. Pulsed so it says "now" rather than looking like a fifth frame.
                int accent = rgb(active.getColor());
                guiGraphics.fill(cell[0], cell[1], cell[0] + cell[2], cell[1] + cell[3],
                        (pulseAlpha() << 24) | accent);
                frame(guiGraphics, cell[0], cell[1], cell[2], cell[3], 0xFF000000 | accent);
                frame(guiGraphics, cell[0] + 1, cell[1] + 1, cell[2] - 2, cell[3] - 2, 0xFF000000 | accent);
            } else {
                frame(guiGraphics, cell[0], cell[1], cell[2], cell[3], OUTLINE);
            }

            Component letter = Component.literal(BridgeLayout.COMPASS_LETTERS[compass]);
            guiGraphics.drawString(font, letter,
                    cell[0] + (cell[2] - font.width(letter)) / 2,
                    cell[1] + (cell[3] - 8) / 2,
                    PILE_LETTER, false);
        }

        int leader = menu.getTrickLeaderSeat();
        if (leader < 0) {
            return;
        }

        // GameSlot only publishes its cards as an Iterable; the backing field is always a List, and the
        // sweep needs the size and an index, so read it back as one rather than copying every frame.
        @SuppressWarnings("unchecked")
        List<Card> cards = (List<Card>) menu.getPileCards().getCards();
        trackSweep(menu.getTrickWinnerSeat(), cards.size());

        Deck deck = menu.getGame().getDeck();
        int[] block = LAYOUT.bounds(BridgeLayout.Element.PILE, width, height);
        float centreX = block[0] + block[2] / 2f;
        float centreY = block[1] + block[3] / 2f;
        float[] target = sweepWinner < 0 ? null : winnerAnchor(sweepWinner);
        long elapsed = Util.getMillis() - sweepStart;

        int index = 0;
        for (Card card : cards) {
            int[] cell = LAYOUT.pileCell(compass(Math.floorMod(leader + index, BridgeGame.PLAYERS)), width, height);
            float cardX = cell[0] + cell[2] / 2f;
            float cardY = cell[1] + cell[3] / 2f;
            float size = 1f;

            if (target != null) {
                // Staggered so the cards arrive one after another instead of snapping together, which is
                // also what makes the gathering read as four cards rather than one.
                float gather = ease(elapsed, index * SWEEP_STAGGER_MS, SWEEP_GATHER_MS);
                cardX = Mth.lerp(gather, cardX, centreX);
                cardY = Mth.lerp(gather, cardY, centreY);

                float fly = ease(elapsed, SWEEP_FLY_AT_MS, SWEEP_FLY_MS);
                // A couple of pixels of spread while gathered, closed up again on the way out.
                float spread = gather * (1f - fly) * 2f;
                cardX = Mth.lerp(fly, cardX + spread * (index - 1.5f), target[0]);
                cardY = Mth.lerp(fly, cardY + spread * (index - 1.5f), target[1]);
                size = Mth.lerp(fly, 1f, SWEEP_END_SCALE);
            }

            int cardW = Math.round(cell[2] * size);
            int cardH = Math.round(cell[3] * size);
            drawCard(guiGraphics, card, deck,
                    new int[]{Math.round(cardX - cardW / 2f), Math.round(cardY - cardH / 2f), cardW, cardH});
            index++;
        }
    }

    /**
     * Starts and clears the collection animation.
     *
     * <p>A winner is a rising edge: the server reports {@link BridgeGame#SYNC_TRICK_WINNER} for exactly
     * as long as the finished trick is on the table, and the clock restarts whenever that seat differs
     * from the one already being animated. Clearing on the shrunken pile rather than on a timer means
     * the state can never outlive the trick it belongs to — including when the same seat wins two tricks
     * in a row, which is the case a "has it changed?" test alone would miss.
     */
    private void trackSweep(int winner, int pileSize) {
        if (winner < 0 || pileSize < BridgeGame.PLAYERS) {
            sweepWinner = -1;
            return;
        }
        if (sweepWinner != winner) {
            sweepWinner = winner;
            sweepStart = Util.getMillis();
        }
    }

    /** Centre of the winner's hand, in screen coordinates. */
    private float[] winnerAnchor(int winnerSeat) {
        int[] box = LAYOUT.bounds(BridgeLayout.hand(compass(winnerSeat)), width, height);
        return new float[]{box[0] + box[2] / 2f, box[1] + box[3] / 2f};
    }

    /** Eased 0..1 for one leg of the sweep, {@code delayMs} after the animation started. */
    private static float ease(long elapsed, float delayMs, float durationMs) {
        float t = Mth.clamp((elapsed - delayMs) / durationMs, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Alpha of the lit pile cell's tint, breathing between {@code 0x30} and {@code 0x80}. */
    private static int pulseAlpha() {
        return (int) (0x30 + 0x50 * (0.5 + 0.5 * Math.sin(Util.getMillis() / 420.0)));
    }

    /**
     * Paints one card into a box, the way an unhovered {@code AbstractCardWidget} would.
     *
     * <p>Charta's cards are not plain blits: a vertex shader fakes a camera perspective on the quad, so
     * the widget hands it a box a third larger than the card and drives it with four shader uniforms.
     * Reproducing those here rather than blitting the texture straight into the box is what makes a
     * played card the same size and shape as one still in a fan — and the uniforms have to be reset
     * first, because the last thing to touch them was a card being hovered, which lifts and tilts.
     */
    private void drawCard(GuiGraphics guiGraphics, Card card, Deck deck, int[] box) {
        float width = box[2];
        float height = box[3];
        float xOffset = (width * 1.333333f - width) / 2f;
        float yOffset = (height * 1.333333f - height) / 2f;

        ChartaModClient.getShaderManager().getCardInset().accept(0f);
        ChartaModClient.getShaderManager().getCardFov().accept(30f);
        ChartaModClient.getShaderManager().getCardXRot().accept(0f);
        ChartaModClient.getShaderManager().getCardYRot().accept(0f);

        ResourceLocation texture = card.flipped()
                ? deck.getTexture(false)
                : deck.getCardTexture(card, false);
        ChartaGuiGraphics.blitCard(guiGraphics, texture,
                box[0] - xOffset, box[1] - yOffset, width + xOffset * 2f, height + yOffset * 2f);
    }

    /**
     * Nothing at all behind the south hand.
     *
     * <p>{@code GameScreen} paints a full-width 63px black band with a ~47px tinted window in the middle
     * of it, the window meant to stand for your own fan. Neither half fits this table. The band is far
     * wider and taller than a 300x53 hand, so its two black ends hang beside the cards as two loose bars;
     * and once those are gone the tinted window left behind is a dark slab that only ever peeks out from
     * under the middle of the fan. The hands already carry their own name plates, so the row needs no
     * backdrop at all and the whole method is a no-op.
     *
     * <p>It also used to be the one spot that tinted the bottom of the screen with the <em>local</em>
     * player's colour, which stopped meaning anything once the compass was nailed to the seats.
     */
    @Override
    public void renderBottomBar(@NotNull GuiGraphics guiGraphics) {
    }

    // ------------------------------------------------------------------- seats ---

    /** Name plates pinned to the hands, plus the dummy highlight. */
    private void renderSeats(GuiGraphics guiGraphics) {
        for (int seat = 0; seat < BridgeGame.PLAYERS; seat++) {
            drawSeatLabel(guiGraphics, seat);
        }
        highlightDummy(guiGraphics, menu.getDummySeat(), menu.getCurrentSeat());
    }

    private void drawSeatLabel(GuiGraphics guiGraphics, int seat) {
        CardPlayer player = menu.getPlayerAtSeat(seat);
        if (player == null) {
            return;
        }
        int[] box = LAYOUT.bounds(BridgeLayout.plate(compass(seat)), width, height);
        int color = rgb(player.getColor());

        Component text = Component.translatable("seat.bridge." + COMPASS[compass(seat)]);
        if (seat == menu.getLocalSeat()) {
            text = text.copy().append(Component.translatable("seat.bridge.you"));
        }
        text = text.copy().append(" ").append(player.getName());
        if (seat == menu.getDummySeat()) {
            text = text.copy().append(Component.translatable("seat.bridge.dummy_tag"));
        }

        boolean active = seat == menu.getCurrentSeat();
        int plateWidth = font.width(text) + 10;
        int plateHeight = 12;
        int centreX = box[0] + box[2] / 2;
        // A plate element's box *is* the plate, so it is used as-is; every seat has one now, including
        // south, so there is no special case left here.
        int y = box[1];
        int left = Mth.clamp(centreX - plateWidth / 2, 2, this.width - plateWidth - 2);

        guiGraphics.fill(left, y, left + plateWidth, y + plateHeight, active ? 0xCC101010 : PLATE_BG);
        guiGraphics.fill(left, y, left + 2, y + plateHeight, 0xFF000000 | color);
        if (active) {
            guiGraphics.fill(left + 2, y, left + plateWidth, y + 1, 0xFF000000 | color);
            guiGraphics.fill(left + 2, y + plateHeight - 1, left + plateWidth, y + plateHeight, 0xFF000000 | color);
        }
        guiGraphics.drawString(font, text, left + 6, y + 2, active ? ACTIVE : LABEL_COLOR, true);
    }

    /** A yellow frame around the dummy's cards, brighter while it is the dummy's turn. */
    private void highlightDummy(GuiGraphics guiGraphics, int dummySeat, int currentSeat) {
        if (dummySeat < 0) {
            return;
        }
        BridgeLayout.Element element = BridgeLayout.hand(compass(dummySeat));
        int[] box = LAYOUT.bounds(element, width, height);
        int color = dummySeat == currentSeat ? 0xFFFFE97F : 0x66FFE97F;
        frame(guiGraphics, box[0] - 1, box[1] - 1, box[2] + 2, box[3] + 2, color);
    }

    // ------------------------------------------------------------------- phases ---

    private void renderPhaseText(GuiGraphics guiGraphics) {
        BlendPhase phase = renderSwitch();
        if (menu.getGame().getPlayers().size() < BridgeGame.PLAYERS) {
            return;
        }
        switch (phase) {
            case PLAY -> renderPlayStatus(guiGraphics);
            case RESULT -> renderResult(guiGraphics);
            default -> drawCentered(guiGraphics, Component.translatable("message.bridge.dealing")
                    .withStyle(ChatFormatting.GOLD), 122);
        }
    }

    private enum BlendPhase {DEALING, PLAY, RESULT}

    private BlendPhase renderSwitch() {
        return switch (menu.getPhase()) {
            case PLAY -> BlendPhase.PLAY;
            case RESULT -> BlendPhase.RESULT;
            default -> BlendPhase.DEALING;
        };
    }

    private void renderPlayStatus(@NotNull GuiGraphics guiGraphics) {
        CardPlayer self = menu.getCardPlayer();
        int currentSeat = menu.getCurrentSeat();
        int localSeat = menu.getLocalSeat();

        Component doubling = doubling(menu.getDoubling());
        drawStatusLine(guiGraphics, Component.translatable("message.bridge.contract_line",
                bidLabel(menu.getContract()), doubling,
                name(menu.getDeclarerSeat(), "seat.bridge."), vulnerability()),
                BridgeLayout.Element.STATUS_CONTRACT);

        if (currentSeat == localSeat) {
            boolean forDummy = menu.getDummySeat() == currentSeat;
            drawStatusLine(guiGraphics, Component.translatable(forDummy
                            ? "message.bridge.your_turn_dummy" : "message.bridge.your_turn")
                    .withStyle(style -> style.withColor(rgb(self.getColor()))), BridgeLayout.Element.STATUS_TURN);
        } else {
            drawStatusLine(guiGraphics, Component.translatable("message.charta.other_turn",
                    name(currentSeat, "seat.bridge.")).withStyle(ChatFormatting.GRAY),
                    BridgeLayout.Element.STATUS_TURN);
        }

        boolean declaring = Math.floorMod(localSeat - menu.getDeclarerSeat(), 2) == 0;
        int ours = declaring ? menu.getDeclarerTricks() : menu.getDefenderTricks();
        int theirs = declaring ? menu.getDefenderTricks() : menu.getDeclarerTricks();
        drawStatusLine(guiGraphics, Component.translatable("message.bridge.progress",
                ours, menu.getRequiredTricks(), theirs), BridgeLayout.Element.STATUS_PROGRESS);

        if (menu.getGame().isShowingTrickCount()) {
            drawStatusLine(guiGraphics, Component.translatable("message.bridge.tricks",
                    menu.getTrickNumber() + 1, BridgeGame.TRICKS), BridgeLayout.Element.STATUS_TRICKS);
        }
    }

    private void renderResult(@NotNull GuiGraphics guiGraphics) {
        int result = menu.getResult();
        Component headline;
        if (result > 0) {
            headline = Component.translatable("message.bridge.result_made").withStyle(ChatFormatting.GREEN);
        } else if (result < 0) {
            headline = Component.translatable("message.bridge.result_down").withStyle(ChatFormatting.RED);
        } else {
            headline = Component.translatable("message.charta.draw").withStyle(ChatFormatting.YELLOW);
        }

        // Lines are placed off the panel's own top edge, not off the screen centre: the old
        // `top - height / 2 + n` collapsed to y=6..40 while the panel sits at y~150, so the whole result
        // was being drawn up by the top bar. A 12px contract glyph needs the panel to be a little taller
        // than the four 12px lines it has to hold.
        int left = width / 2 - 66;
        int panelHeight = 58;
        int top = height / 2 - panelHeight / 2;
        guiGraphics.fill(left, top, left + 132, top + panelHeight, 0xE0101014);
        frame(guiGraphics, left, top, 132, panelHeight, OUTLINE);

        Component line = Component.translatable("message.bridge.contract_line",
                bidLabel(menu.getContract()), doubling(menu.getDoubling()),
                name(menu.getDeclarerSeat(), "seat.bridge."), vulnerability());
        drawCentered(guiGraphics, line, top + 6);
        drawCentered(guiGraphics, headline, top + 22);
        if (result != 0) {
            drawCentered(guiGraphics, Component.translatable("message.bridge.score_delta", menu.getScore()),
                    top + 36);
        }
        drawCentered(guiGraphics, Component.translatable("message.bridge.progress",
                menu.getDeclarerTricks(), menu.getRequiredTricks(), menu.getDefenderTricks()),
                top + 47);
    }

    // ------------------------------------------------------------------- editor ---

    /**
     * F9 overlay: a box per movable element, a drag handle on each, and a scroll-to-scale hint.
     *
     * <p>Boxes are drawn from {@link BridgeLayout#bounds}, i.e. the same values the slots are written
     * from, so what is highlighted here is exactly what a click will hit.
     */
    private void renderEditor(GuiGraphics guiGraphics) {
        for (BridgeLayout.Element element : BridgeLayout.Element.values()) {
            int[] box = LAYOUT.bounds(element, width, height);
            boolean isSelected = element == selected;
            int color = isSelected ? 0xFFFFE97F : element == hovered ? 0xFFFFFFFF : 0x8000E0FF;
            frame(guiGraphics, box[0] - 1, box[1] - 1, box[2] + 2, box[3] + 2, color);

            String label = element.label() + "  " + Math.round(LAYOUT.scale(element) * 100) + "%";
            guiGraphics.drawString(font, label, box[0], box[1] + box[3] + 2, color, true);
        }

        Component hint = Component.translatable("editor.bridge.hint");
        int y = height - 14;
        guiGraphics.fill(0, y - 3, width, height, 0xB0000000);
        guiGraphics.drawString(font, hint, 4, y, 0xFFFFFFFF, true);
        if (selected != null) {
            guiGraphics.drawString(font, selected.label() + "  (" + selected.key + ")", 4, y - 16, ACTIVE, true);
        }
        renderCursorReadout(guiGraphics, y);
    }

    /**
     * Live pointer position, bottom-right of the F9 overlay.
     *
     * <p>Three numbers, because all three are the ones a layout tweak actually needs: the raw screen
     * position, the same point mapped into popup-local space (i.e. the coordinates {@code BOX_X}/{@code
     * CELL_W} and friends are written in, so a button's corner can be read straight off the screen), and
     * what the pointer is over.
     *
     * <p>The position is polled from {@code MouseHandler} rather than taken from {@code
     * mouseMoved}, which does not fire while the pointer is held still.
     */
    private void renderCursorReadout(GuiGraphics guiGraphics, int hintY) {
        double mouseX = scaledMouseX();
        double mouseY = scaledMouseY();
        Component position = Component.literal("mouse " + (int) Math.round(mouseX) + ", " + (int) Math.round(mouseY)
                + "   popup " + Math.round((mouseX - inPopupX(0)) / popupScale())
                + ", " + Math.round((mouseY - inPopupY(0)) / popupScale()));

        BridgeLayout.Element under = elementAt(mouseX, mouseY);
        Component target = Component.literal(under == null ? "-" : under.label() + " " + under.key);
        int color = under == null ? 0xFF00E0FF : 0xFFFFE97F;

        int boxWidth = Math.max(font.width(position), font.width(target));
        int left = width - boxWidth - 8;
        guiGraphics.fill(left - 4, hintY - 16, width, hintY + 10, 0xB0000000);
        guiGraphics.drawString(font, position, left, hintY - 13, 0xFFFFFFFF, true);
        guiGraphics.drawString(font, target, left, hintY - 2, color, true);
    }

    /** GUI-scaled pointer X, for callers that need it outside an input callback. */
    private double scaledMouseX() {
        if (minecraft == null || minecraft.getWindow().getScreenWidth() == 0) {
            return lastMouseX;
        }
        return frameX(minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()
                / minecraft.getWindow().getScreenWidth());
    }

    private double scaledMouseY() {
        if (minecraft == null || minecraft.getWindow().getScreenHeight() == 0) {
            return lastMouseY;
        }
        return frameY(minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()
                / minecraft.getWindow().getScreenHeight());
    }

    /**
     * Window coordinate to design-frame coordinate.
     *
     * <p>Every {@code mouse*} override below opens with these two, so the rest of the class -- hit
     * tests, drag accumulation, the F9 editor -- only ever sees frame numbers and needs no knowledge of
     * the window at all. {@code dragX}/{@code dragY} are lengths rather than positions and are divided
     * by the scale where they are used.
     */
    private static double frameX(double guiX) {
        return BridgeFrame.current().toFrameX(guiX);
    }

    private static double frameY(double guiY) {
        return BridgeFrame.current().toFrameY(guiY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        double frameMouseX = frameX(mouseX);
        double frameMouseY = frameY(mouseY);
        lastMouseX = frameMouseX;
        lastMouseY = frameMouseY;
        if (editing) {
            hovered = elementAt(frameMouseX, frameMouseY);
        }
        super.mouseMoved(frameMouseX, frameMouseY);
    }

    private BridgeLayout.Element elementAt(double mouseX, double mouseY) {
        BridgeLayout.Element found = null;
        for (BridgeLayout.Element element : BridgeLayout.Element.values()) {
            int[] box = LAYOUT.bounds(element, width, height);
            if (mouseX >= box[0] && mouseX < box[0] + box[2] && mouseY >= box[1] && mouseY < box[1] + box[3]) {
                found = element;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------- input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double frameMouseX = frameX(mouseX);
        double frameMouseY = frameY(mouseY);
        lastMouseX = frameMouseX;
        lastMouseY = frameMouseY;
        if (editing && button == 0) {
            selected = elementAt(frameMouseX, frameMouseY);
            if (selected != null) {
                dragging = true;
                return true;
            }
            return false;
        }
        if (button == 0 && menu.canCallNow()) {
            int call = callAt(frameMouseX, frameMouseY);
            if (call >= 0 && menu.mirrorAuction().isLegal(call, menu.getLocalSeat())) {
                ChartaMod.getPacketManager().sendToServer(new BridgeCallPayload(menu.containerId, call));
                return true;
            }
        }
        return super.mouseClicked(frameMouseX, frameMouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        double frameMouseX = frameX(mouseX);
        double frameMouseY = frameY(mouseY);
        if (editing && dragging && selected != null) {
            // The deltas are window pixels like everything else the event system hands over, so they
            // shrink with the frame; without the division a drag would outrun the pointer on any
            // window smaller than the design size.
            BridgeFrame frame = BridgeFrame.current();
            LAYOUT.move(selected, (float) frame.toFrameLength(dragX), (float) frame.toFrameLength(dragY));
            applyLayout();
            return true;
        }
        return super.mouseDragged(frameMouseX, frameMouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        double frameMouseX = frameX(mouseX);
        double frameMouseY = frameY(mouseY);
        if (editing && dragging) {
            dragging = false;
            LAYOUT.save();
            return true;
        }
        return super.mouseReleased(frameMouseX, frameMouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double frameMouseX = frameX(mouseX);
        double frameMouseY = frameY(mouseY);
        if (editing) {
            BridgeLayout.Element element = selected != null ? selected : elementAt(frameMouseX, frameMouseY);
            if (element != null) {
                LAYOUT.scaleBy(element, (float) scrollY * BridgeLayout.SCALE_STEP);
                applyLayout();
                LAYOUT.save();
                return true;
            }
        }
        return super.mouseScrolled(frameMouseX, frameMouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F9) {
            editing = !editing;
            if (editing) {
                selected = null;
            } else {
                LAYOUT.save();
            }
            return true;
        }
        if (editing && keyCode == GLFW.GLFW_KEY_R) {
            if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                LAYOUT.resetAll();
                selected = null;
            } else if (selected != null) {
                LAYOUT.reset(selected);
            }
            applyLayout();
            LAYOUT.save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------- helpers ---

    private void drawCentered(GuiGraphics guiGraphics, Component text, int y) {
        guiGraphics.drawString(font, text, width / 2 - font.width(text) / 2, y, 0xFFFFFFFF, true);
    }

    /**
     * Draws one of the four play status lines, centred inside its own layout handle.
     *
     * <p>Centring happens inside the handle rather than on the screen, which is what makes a single
     * line draggable: the handle is what F9 outlines and what the pointer hit-tests, and the text keeps
     * the middle of it however far it is moved. Scroll scaling scales the text along with the handle,
     * so a scaled line cannot drift out of the box that is drawn around it.
     */
    private void drawStatusLine(GuiGraphics guiGraphics, Component text, BridgeLayout.Element element) {
        int[] box = LAYOUT.bounds(element, width, height);
        float scale = LAYOUT.scale(element);
        int x = box[0] + (box[2] - Math.round(font.width(text) * scale)) / 2;
        int y = box[1] + (box[3] - Math.round(STATUS_TEXT_H * scale)) / 2;
        if (scale == 1f) {
            guiGraphics.drawString(font, text, x, y, 0xFFFFFFFF, true);
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0d);
        guiGraphics.pose().scale(scale, scale, 1f);
        guiGraphics.drawString(font, text, 0, 0, 0xFFFFFFFF, true);
        guiGraphics.pose().popPose();
    }

    private void drawInPopup(GuiGraphics guiGraphics, Component text, int popupX, int popupY) {
        guiGraphics.drawString(font, text, inPopupX(popupX), inPopupY(popupY), 0xFFFFFFFF, true);
    }

    private void frame(GuiGraphics guiGraphics, int x, int y, int w, int h, int color) {
        guiGraphics.fill(x, y, x + w, y + 1, color);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, color);
        guiGraphics.fill(x, y, x + 1, y + h, color);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    private float popupScale() {
        return LAYOUT.scale(BridgeLayout.Element.POPUP);
    }

    private int inPopupX(float popupX) {
        return LAYOUT.inPopupX(popupX, width, height);
    }

    private int inPopupY(float popupY) {
        return LAYOUT.inPopupY(popupY, width, height);
    }

    /** {@code seat.bridge.north (1 电脑1)} style name for a seat, used in status text. */
    private Component name(int seat, String prefix) {
        CardPlayer player = menu.getPlayerAtSeat(seat);
        Component label = Component.translatable(prefix + labelSuffix(seat));
        return player == null ? label : label.copy().append(" ").append(player.getName());
    }

    private String labelSuffix(int seat) {
        if (seat < 0) {
            return "none";
        }
        if (seat == menu.getLocalSeat()) {
            return "here";
        }
        return COMPASS[compass(seat)];
    }

    /** Compass of a seat: where that player is sitting, not where the seat index falls. */
    private int compass(int seat) {
        return menu.getGame().compass(seat);
    }

    private static int rgb(DyeColor color) {
        return color.getTextureDiffuseColor() & 0xFFFFFF;
    }

    /**
     * Call under the pointer, or {@code -1}.
     *
     * <p>Geometry only: whether the call is legal is the caller's business, so the same helper serves
     * both the click and the hover hint.
     */
    private int callAt(double mouseX, double mouseY) {
        int x = (int) Math.round((mouseX - inPopupX(0)) / popupScale());
        int y = (int) Math.round((mouseY - inPopupY(0)) / popupScale());

        int column = (x - BOX_X) / CELL_W;
        int row = (y - BOX_Y) / CELL_H;
        if (column >= 0 && column < STRAIN_COLUMNS && row >= 0 && row < LEVEL_ROWS
                && x >= BOX_X && y >= BOX_Y) {
            return BridgeBid.code(BridgeBid.MAX_LEVEL - row, column);
        }

        int passX = (BridgeMenu.PANEL_WIDTH - (PASS_W * 3 + 4)) / 2 + 1;
        if (y >= PASS_Y && y < PASS_Y + PASS_H && x >= passX) {
            int index = (x - passX) / (PASS_W + 2);
            if (index >= 0 && index < 3 && x < passX + index * (PASS_W + 2) + PASS_W) {
                return switch (index) {
                    case 0 -> BridgeBid.PASS;
                    case 1 -> BridgeBid.DOUBLE;
                    default -> BridgeBid.REDOUBLE;
                };
            }
        }
        return -1;
    }

}

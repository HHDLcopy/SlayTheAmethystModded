package io.stamethyst.compatmod.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Interpolation;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.controller.CInputActionSet;
import com.megacrit.cardcrawl.helpers.controller.CInputHelper;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MenuButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.WeakHashMap;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

/**
 * Pages root main-menu entries once the vanilla vertical list no longer fits on screen.
 *
 * <p>The vanilla list is bottom-anchored: {@code MenuButton} index 0 sits at
 * {@code MenuButton.START_Y} and later indices stack upwards, so overflow always happens at the top
 * of the screen and the visually first entries are the ones with the highest indices. Paging is
 * therefore done in visual top-to-bottom order, and every geometry value is taken from the
 * positions the vanilla constructor actually produced instead of being recomputed here. That keeps
 * the mod in step with other patches that alter the menu metrics, such as the touch layout
 * compatibility patch rewriting {@code Settings.isTouchScreen} inside the constructor.</p>
 */
public final class MainMenuPaginationPatches {
    private static final WeakHashMap<MainMenuScreen, PaginationState> STATES =
        new WeakHashMap<MainMenuScreen, PaginationState>();

    private static final float OFFSCREEN_Y = -4000.0f;

    private MainMenuPaginationPatches() {
    }

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = "update"
    )
    public static class MainMenuScreenUpdatePatch {
        public static void Prefix(MainMenuScreen __instance) {
            PaginationState state = stateFor(__instance);
            state.updateLayout(__instance);
            state.updateInput(__instance);
        }
    }

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class MainMenuScreenRenderPatch {
        public static void Postfix(MainMenuScreen __instance, SpriteBatch sb) {
            stateFor(__instance).render(__instance, sb);
        }
    }

    /**
     * Off-page buttons are parked off-screen by the layout pass, so their hitboxes can never be
     * hovered. Skipping the draw call keeps them invisible without suppressing their update, which
     * would otherwise strand the horizontal slide-in offset applied by {@code MenuButton.hide()}.
     */
    @SpirePatch2(
        clz = MenuButton.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class MenuButtonRenderPatch {
        public static SpireReturn<Void> Prefix(MenuButton __instance) {
            return isHiddenByPagination(__instance)
                ? SpireReturn.Return(null)
                : SpireReturn.Continue();
        }

        /**
         * {@code MenuButton.render} staggers its slide-in animation by the vanilla list index. On a
         * paged menu that index no longer matches the row the button is drawn on, so it is remapped
         * to the row offset within the current page.
         */
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(FieldAccess access) throws CannotCompileException {
                    if (!access.isReader()) {
                        return;
                    }
                    if (!MenuButton.class.getName().equals(access.getClassName())) {
                        return;
                    }
                    if (!"index".equals(access.getFieldName())) {
                        return;
                    }
                    access.replace(
                        "{ $_ = " + MainMenuPaginationPatches.class.getName()
                            + ".resolveMenuButtonRenderIndex($0, $proceed()); }"
                    );
                }
            };
        }
    }

    /**
     * Vanilla controller navigation walks the whole button list and would move the cursor onto
     * entries parked off-screen by pagination, so it is replaced while paging is active.
     */
    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = "updateMenuButtonController"
    )
    public static class MainMenuControllerPatch {
        public static SpireReturn<Void> Prefix(MainMenuScreen __instance) {
            return stateFor(__instance).updateController(__instance)
                ? SpireReturn.Return(null)
                : SpireReturn.Continue();
        }
    }

    private static boolean isHiddenByPagination(MenuButton button) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null) {
            return false;
        }
        PaginationState state = STATES.get(menu);
        return state != null && state.isParked(button);
    }

    public static int resolveMenuButtonRenderIndex(MenuButton button, int originalIndex) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null) {
            return originalIndex;
        }
        PaginationState state = STATES.get(menu);
        if (state == null) {
            return originalIndex;
        }
        return state.resolveRenderIndex(button, originalIndex);
    }

    private static PaginationState stateFor(MainMenuScreen menu) {
        PaginationState state = STATES.get(menu);
        if (state == null) {
            state = new PaginationState();
            STATES.put(menu, state);
        }
        return state;
    }

    private static final class PaginationState {
        private static java.lang.reflect.Field saveSlotHitboxField;
        private static boolean saveSlotHitboxUnavailable;

        private static final Comparator<ButtonLayout> TOP_FIRST = new Comparator<ButtonLayout>() {
            @Override
            public int compare(ButtonLayout left, ButtonLayout right) {
                return Float.compare(right.centerY, left.centerY);
            }
        };

        private final IdentityHashMap<MenuButton, ButtonLayout> vanillaLayout =
            new IdentityHashMap<MenuButton, ButtonLayout>();
        private final IdentityHashMap<MenuButton, Integer> visibleRows =
            new IdentityHashMap<MenuButton, Integer>();
        private final ArrayList<ButtonLayout> topFirstOrder = new ArrayList<ButtonLayout>();
        private final ArrayList<MenuButton> pageBottomFirst = new ArrayList<MenuButton>();

        private final Hitbox leftArrow = new Hitbox(64.0f * Settings.scale, 64.0f * Settings.scale);
        private final Hitbox rightArrow = new Hitbox(64.0f * Settings.scale, 64.0f * Settings.scale);

        private boolean active;
        private int page;
        private int pageCount;
        private int rowsPerPage;
        private float controlsY;
        private float pageLabelX;

        /**
         * Recomputes the paged layout for the current frame. Runs before the vanilla update so that
         * every hitbox is already in its final position when the buttons update themselves.
         */
        private void updateLayout(MainMenuScreen menu) {
            ArrayList<MenuButton> buttons = menu.buttons;
            if (buttons == null || buttons.isEmpty()) {
                deactivate(menu);
                return;
            }

            syncVanillaLayout(buttons);
            collectTopFirstOrder(buttons);
            if (topFirstOrder.isEmpty()) {
                deactivate(menu);
                return;
            }

            float rowSpacing = resolveRowSpacing();
            float bottomRowY = topFirstOrder.get(topFirstOrder.size() - 1).centerY;
            float topLimit = resolveTopLimit(menu);
            if (rowSpacing <= 0.0f || !overflowsTopLimit(topLimit)) {
                deactivate(menu);
                return;
            }

            // The bottom vanilla row is handed over to the paging controls, so the entries start one
            // row higher and the controls end up flush with the bottom of the menu column.
            float firstRowY = bottomRowY + rowSpacing;
            rowsPerPage = Math.max(1, (int) Math.floor((topLimit - firstRowY) / rowSpacing) + 1);
            pageCount = Math.max(1, (topFirstOrder.size() + rowsPerPage - 1) / rowsPerPage);
            page = Math.max(0, Math.min(page, pageCount - 1));
            active = true;

            controlsY = bottomRowY;
            leftArrow.move(MenuButton.FONT_X + 20.0f * Settings.scale, controlsY);
            rightArrow.move(MenuButton.FONT_X + 160.0f * Settings.scale, controlsY);
            pageLabelX = (leftArrow.cX + rightArrow.cX) / 2.0f;

            int firstIndex = page * rowsPerPage;
            int lastIndex = Math.min(topFirstOrder.size(), firstIndex + rowsPerPage);
            int pageSize = lastIndex - firstIndex;

            visibleRows.clear();
            pageBottomFirst.clear();
            for (int i = 0; i < topFirstOrder.size(); i++) {
                ButtonLayout layout = topFirstOrder.get(i);
                if (i < firstIndex || i >= lastIndex) {
                    layout.button.hb.move(layout.centerX, OFFSCREEN_Y);
                    clearInput(layout.button.hb);
                    continue;
                }
                // Pages stay bottom-anchored like the vanilla menu, so a partially filled last page
                // still sits directly above the paging controls.
                int row = pageSize - 1 - (i - firstIndex);
                layout.button.hb.move(layout.centerX, firstRowY + row * rowSpacing);
                visibleRows.put(layout.button, Integer.valueOf(row));
                pageBottomFirst.add(layout.button);
            }
            Collections.reverse(pageBottomFirst);
        }

        private void updateInput(MainMenuScreen menu) {
            if (!active) {
                return;
            }
            if (!isInteractive(menu)) {
                clearInput(leftArrow);
                clearInput(rightArrow);
                return;
            }

            boolean canPageUp = page > 0;
            boolean canPageDown = page < pageCount - 1;

            leftArrow.update();
            rightArrow.update();
            if (!canPageUp) {
                clearInput(leftArrow);
            }
            if (!canPageDown) {
                clearInput(rightArrow);
            }

            if (leftArrow.justHovered || rightArrow.justHovered) {
                CardCrawlGame.sound.playV("UI_HOVER", 0.75f);
            }
            if (InputHelper.justClickedLeft) {
                if (leftArrow.hovered) {
                    leftArrow.clickStarted = true;
                    CardCrawlGame.sound.playA("UI_CLICK_1", -0.1f);
                } else if (rightArrow.hovered) {
                    rightArrow.clickStarted = true;
                    CardCrawlGame.sound.playA("UI_CLICK_1", -0.1f);
                }
            }

            boolean pageUp = leftArrow.clicked;
            boolean pageDown = rightArrow.clicked;
            leftArrow.clicked = false;
            rightArrow.clicked = false;

            if (canPageUp && CInputActionSet.pageLeftViewDeck.isJustPressed()) {
                CInputActionSet.pageLeftViewDeck.unpress();
                pageUp = true;
            }
            if (canPageDown && CInputActionSet.pageRightViewExhaust.isJustPressed()) {
                CInputActionSet.pageRightViewExhaust.unpress();
                pageDown = true;
            }

            if (pageUp && canPageUp) {
                page--;
                clearInput(leftArrow);
                clearInput(rightArrow);
            } else if (pageDown && canPageDown) {
                page++;
                clearInput(leftArrow);
                clearInput(rightArrow);
            }
        }

        private void render(MainMenuScreen menu, SpriteBatch sb) {
            if (!active || !isInteractive(menu)) {
                return;
            }

            // Mirrors the slide-out offset MenuButton.render applies to the bottom row.
            float slide = -1000.0f * Settings.scale
                * Interpolation.circleIn.apply(menu.bg.slider);

            renderArrow(sb, ImageMaster.CF_LEFT_ARROW, leftArrow, slide, page > 0);
            renderArrow(sb, ImageMaster.CF_RIGHT_ARROW, rightArrow, slide, page < pageCount - 1);
            FontHelper.renderFontCentered(
                sb,
                FontHelper.cardDescFont_N,
                (page + 1) + " / " + pageCount,
                pageLabelX + slide,
                controlsY,
                Settings.CREAM_COLOR
            );
        }

        private boolean updateController(MainMenuScreen menu) {
            if (!active || !Settings.isControllerMode || pageBottomFirst.isEmpty()) {
                return false;
            }
            if (!isInteractive(menu)) {
                return true;
            }

            int hovered = -1;
            for (int i = 0; i < pageBottomFirst.size(); i++) {
                Hitbox hb = pageBottomFirst.get(i).hb;
                if (hb != null && hb.hovered) {
                    hovered = i;
                    break;
                }
            }

            int target;
            if (hovered < 0) {
                target = 0;
            } else if (isDownPressed()) {
                target = hovered == 0 ? pageBottomFirst.size() - 1 : hovered - 1;
            } else if (isUpPressed()) {
                target = hovered == pageBottomFirst.size() - 1 ? 0 : hovered + 1;
            } else {
                return true;
            }

            CInputHelper.setCursor(pageBottomFirst.get(target).hb);
            return true;
        }

        private boolean isParked(MenuButton button) {
            return active && !visibleRows.containsKey(button);
        }

        private int resolveRenderIndex(MenuButton button, int originalIndex) {
            if (!active) {
                return originalIndex;
            }
            Integer row = visibleRows.get(button);
            return row == null ? originalIndex : row.intValue();
        }

        /**
         * Captures the untouched constructor position of every new button and drops entries for
         * buttons that left the list, so the snapshot always mirrors the current vanilla layout.
         */
        private void syncVanillaLayout(ArrayList<MenuButton> buttons) {
            for (int i = 0; i < buttons.size(); i++) {
                MenuButton button = buttons.get(i);
                if (button == null || button.hb == null || vanillaLayout.containsKey(button)) {
                    continue;
                }
                vanillaLayout.put(button, new ButtonLayout(button, button.hb.cX, button.hb.cY));
            }
            if (vanillaLayout.size() == buttons.size()) {
                return;
            }
            Iterator<MenuButton> stale = vanillaLayout.keySet().iterator();
            while (stale.hasNext()) {
                if (!containsIdentity(buttons, stale.next())) {
                    stale.remove();
                }
            }
        }

        private void collectTopFirstOrder(ArrayList<MenuButton> buttons) {
            topFirstOrder.clear();
            for (int i = 0; i < buttons.size(); i++) {
                ButtonLayout layout = vanillaLayout.get(buttons.get(i));
                if (layout != null && layout.button.hb != null) {
                    topFirstOrder.add(layout);
                }
            }
            Collections.sort(topFirstOrder, TOP_FIRST);
        }

        /**
         * Derives the row pitch from the captured positions rather than recomputing it from
         * {@code MenuButton.SPACE_Y}, which would ignore any patch that changed the menu metrics.
         */
        private float resolveRowSpacing() {
            float spacing = 0.0f;
            for (int i = 1; i < topFirstOrder.size(); i++) {
                float gap = topFirstOrder.get(i - 1).centerY - topFirstOrder.get(i).centerY;
                if (gap > 0.0f && (spacing == 0.0f || gap < spacing)) {
                    spacing = gap;
                }
            }
            return spacing > 0.0f ? spacing : MenuButton.SPACE_Y;
        }

        /**
         * Resolves the highest row centre a menu entry may occupy. The save-slot button
         * ({@code MainMenuScreen.nameEditHb}) sits in the top-left corner above the menu column, so
         * the limit is placed just below it and an entry that would reach into it counts as
         * overflow. Falls back to the screen edge when the hitbox cannot be read.
         */
        private float resolveTopLimit(MainMenuScreen menu) {
            float halfHeight = maxHalfHeight();
            float screenLimit = Settings.HEIGHT - halfHeight;
            Hitbox saveSlot = resolveSaveSlotHitbox(menu);
            if (saveSlot == null) {
                return screenLimit;
            }
            // Only the buttons sharing horizontal space with the save-slot button can collide.
            if (!overlapsHorizontally(saveSlot)) {
                return screenLimit;
            }
            return Math.min(screenLimit, saveSlot.y - halfHeight);
        }

        private boolean overlapsHorizontally(Hitbox saveSlot) {
            float slotLeft = saveSlot.x;
            float slotRight = saveSlot.x + saveSlot.width;
            for (int i = 0; i < topFirstOrder.size(); i++) {
                ButtonLayout layout = topFirstOrder.get(i);
                Hitbox hb = layout.button.hb;
                float left = layout.centerX - hb.width / 2.0f;
                float right = layout.centerX + hb.width / 2.0f;
                if (left < slotRight && right > slotLeft) {
                    return true;
                }
            }
            return false;
        }

        private static Hitbox resolveSaveSlotHitbox(MainMenuScreen menu) {
            if (saveSlotHitboxUnavailable) {
                return null;
            }
            try {
                if (saveSlotHitboxField == null) {
                    java.lang.reflect.Field field =
                        MainMenuScreen.class.getDeclaredField("nameEditHb");
                    field.setAccessible(true);
                    saveSlotHitboxField = field;
                }
                Object value = saveSlotHitboxField.get(menu);
                return value instanceof Hitbox ? (Hitbox) value : null;
            } catch (Throwable t) {
                saveSlotHitboxUnavailable = true;
                return null;
            }
        }

        private boolean overflowsTopLimit(float topLimit) {
            for (int i = 0; i < topFirstOrder.size(); i++) {
                if (topFirstOrder.get(i).centerY > topLimit) {
                    return true;
                }
            }
            return false;
        }

        private float maxHalfHeight() {
            float half = 0.0f;
            for (int i = 0; i < topFirstOrder.size(); i++) {
                float candidate = topFirstOrder.get(i).button.hb.height / 2.0f;
                if (candidate > half) {
                    half = candidate;
                }
            }
            return half;
        }

        private void deactivate(MainMenuScreen menu) {
            if (active) {
                restoreVanillaLayout(menu);
            }
            active = false;
            page = 0;
            pageCount = 0;
            rowsPerPage = 0;
            visibleRows.clear();
            pageBottomFirst.clear();
            clearInput(leftArrow);
            clearInput(rightArrow);
        }

        private void restoreVanillaLayout(MainMenuScreen menu) {
            if (menu.buttons == null) {
                return;
            }
            for (int i = 0; i < menu.buttons.size(); i++) {
                MenuButton button = menu.buttons.get(i);
                ButtonLayout layout = vanillaLayout.get(button);
                if (layout != null && button.hb != null) {
                    button.hb.move(layout.centerX, layout.centerY);
                }
            }
        }

        private static boolean isInteractive(MainMenuScreen menu) {
            return menu.bg != null
                && menu.screen == MainMenuScreen.CurScreen.MAIN_MENU
                && menu.bg.slider < 0.5f;
        }

        private static boolean containsIdentity(ArrayList<MenuButton> buttons, MenuButton button) {
            for (int i = 0; i < buttons.size(); i++) {
                if (buttons.get(i) == button) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isUpPressed() {
            return CInputActionSet.up.isJustPressed() || CInputActionSet.altUp.isJustPressed();
        }

        private static boolean isDownPressed() {
            return CInputActionSet.down.isJustPressed() || CInputActionSet.altDown.isJustPressed();
        }

        private static void renderArrow(
            SpriteBatch sb,
            Texture texture,
            Hitbox hitbox,
            float slide,
            boolean enabled
        ) {
            if (!enabled) {
                return;
            }
            sb.setColor(hitbox.hovered ? Color.WHITE : Color.LIGHT_GRAY);
            float size = 48.0f * Settings.scale;
            sb.draw(
                texture,
                hitbox.cX + slide - size / 2.0f,
                hitbox.cY - size / 2.0f,
                size,
                size
            );
            sb.setColor(Color.WHITE);
        }

        private static void clearInput(Hitbox hitbox) {
            if (hitbox == null) {
                return;
            }
            hitbox.hovered = false;
            hitbox.justHovered = false;
            hitbox.clickStarted = false;
            hitbox.clicked = false;
        }

        private static final class ButtonLayout {
            private final MenuButton button;
            private final float centerX;
            private final float centerY;

            private ButtonLayout(MenuButton button, float centerX, float centerY) {
                this.button = button;
                this.centerX = centerX;
                this.centerY = centerY;
            }
        }
    }
}

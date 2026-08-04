package io.stamethyst.compatmod.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.controller.CInputActionSet;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MenuButton;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

/**
 * Pages mod-added root-menu entries once the vanilla vertical list no longer fits on screen.
 * The page controls deliberately reuse the base game's small left/right arrow textures.
 */
public final class MainMenuPaginationPatches {
    private static final WeakHashMap<MainMenuScreen, PaginationState> STATES =
        new WeakHashMap<MainMenuScreen, PaginationState>();

    private MainMenuPaginationPatches() {
    }

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = "update"
    )
    public static class MainMenuScreenUpdatePatch {
        public static void Prefix(MainMenuScreen __instance) {
            updatePagination(__instance);
        }
    }

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class MainMenuScreenRenderPatch {
        public static void Postfix(MainMenuScreen __instance, SpriteBatch sb) {
            renderPagination(__instance, sb);
        }
    }

    @SpirePatch2(
        clz = MenuButton.class,
        method = "update"
    )
    public static class MenuButtonUpdatePatch {
        public static SpireReturn<Void> Prefix(MenuButton __instance) {
            return isVisibleOnCurrentPage(__instance)
                ? SpireReturn.Continue()
                : SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        clz = MenuButton.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class MenuButtonRenderPatch {
        public static SpireReturn<Void> Prefix(MenuButton __instance) {
            return isVisibleOnCurrentPage(__instance)
                ? SpireReturn.Continue()
                : SpireReturn.Return(null);
        }

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

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = "updateMenuButtonController"
    )
    public static class MainMenuControllerPatch {
        public static SpireReturn<Void> Prefix(MainMenuScreen __instance) {
            return updateControllerCursor(__instance)
                ? SpireReturn.Return(null)
                : SpireReturn.Continue();
        }
    }

    private static void updatePagination(MainMenuScreen menu) {
        PaginationState state = stateFor(menu);
        if (!state.refresh(menu)) {
            return;
        }

        state.leftArrow.update();
        state.rightArrow.update();
        if (state.page == 0) {
            state.clearInput(state.leftArrow);
        }
        if (state.page == state.pageCount - 1) {
            state.clearInput(state.rightArrow);
        }
        if (InputHelper.justClickedLeft) {
            if (state.leftArrow.hovered && state.page > 0) {
                state.leftArrow.clickStarted = true;
                CardCrawlGame.sound.play("UI_CLICK_1");
            } else if (state.rightArrow.hovered && state.page < state.pageCount - 1) {
                state.rightArrow.clickStarted = true;
                CardCrawlGame.sound.play("UI_CLICK_1");
            }
        }
        if (state.leftArrow.justHovered || state.rightArrow.justHovered) {
            CardCrawlGame.sound.play("UI_HOVER");
        }

        boolean previousPage = state.leftArrow.clicked
            || CInputActionSet.pageLeftViewDeck.isJustPressed();
        boolean nextPage = state.rightArrow.clicked
            || CInputActionSet.pageRightViewExhaust.isJustPressed();
        state.leftArrow.clicked = false;
        state.rightArrow.clicked = false;
        if (previousPage && state.page > 0) {
            CInputActionSet.pageLeftViewDeck.unpress();
            state.changePage(-1);
        } else if (nextPage && state.page < state.pageCount - 1) {
            CInputActionSet.pageRightViewExhaust.unpress();
            state.changePage(1);
        }
    }

    private static void renderPagination(MainMenuScreen menu, SpriteBatch sb) {
        PaginationState state = stateFor(menu);
        if (!state.refresh(menu)) {
            return;
        }

        renderArrow(sb, ImageMaster.CF_LEFT_ARROW, state.leftArrow, state.page > 0);
        renderArrow(sb, ImageMaster.CF_RIGHT_ARROW, state.rightArrow, state.page < state.pageCount - 1);
        FontHelper.renderFontCentered(
            sb,
            FontHelper.cardDescFont_N,
            (state.page + 1) + " / " + state.pageCount,
            Settings.WIDTH / 2.0f,
            state.arrowY - 8.0f * Settings.scale,
            Settings.CREAM_COLOR
        );
        state.leftArrow.render(sb);
        state.rightArrow.render(sb);
    }

    private static boolean updateControllerCursor(MainMenuScreen menu) {
        PaginationState state = stateFor(menu);
        if (!Settings.isControllerMode || !state.refresh(menu)) {
            return false;
        }

        int firstIndex = state.page * state.pageSize;
        int lastIndex = Math.min(menu.buttons.size(), firstIndex + state.pageSize);
        int hoveredIndex = -1;
        for (int index = firstIndex; index < lastIndex; index++) {
            MenuButton button = menu.buttons.get(index);
            if (button != null && button.hb != null && button.hb.hovered) {
                hoveredIndex = index;
                break;
            }
        }

        int nextIndex = hoveredIndex;
        if (hoveredIndex >= firstIndex && isUpPressed()) {
            nextIndex = hoveredIndex == firstIndex ? lastIndex - 1 : hoveredIndex - 1;
        } else if (hoveredIndex >= firstIndex && isDownPressed()) {
            nextIndex = hoveredIndex == lastIndex - 1 ? firstIndex : hoveredIndex + 1;
        } else if (hoveredIndex < firstIndex) {
            nextIndex = lastIndex - 1;
        }

        if (nextIndex >= firstIndex && nextIndex < lastIndex) {
            com.megacrit.cardcrawl.helpers.controller.CInputHelper.setCursor(
                menu.buttons.get(nextIndex).hb
            );
        }
        return true;
    }

    private static boolean isUpPressed() {
        return CInputActionSet.up.isJustPressed() || CInputActionSet.altUp.isJustPressed();
    }

    private static boolean isDownPressed() {
        return CInputActionSet.down.isJustPressed() || CInputActionSet.altDown.isJustPressed();
    }

    private static void renderArrow(SpriteBatch sb, Texture texture, Hitbox hitbox, boolean enabled) {
        boolean highlighted = enabled && hitbox.hovered;
        sb.setColor(highlighted ? Color.WHITE : Color.LIGHT_GRAY);
        float halfSize = 24.0f * Settings.scale;
        sb.draw(
            texture,
            hitbox.cX - halfSize,
            hitbox.cY - halfSize,
            halfSize,
            halfSize,
            halfSize * 2.0f,
            halfSize * 2.0f,
            1.0f,
            1.0f,
            0.0f,
            0,
            0,
            48,
            48,
            false,
            false
        );
        sb.setColor(Color.WHITE);
    }

    private static boolean isVisibleOnCurrentPage(MenuButton button) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null) {
            return true;
        }
        PaginationState state = stateFor(menu);
        if (!state.refresh(menu)) {
            return true;
        }
        int index = menu.buttons.indexOf(button);
        return index >= state.page * state.pageSize && index < (state.page + 1) * state.pageSize;
    }

    public static int resolveMenuButtonRenderIndex(MenuButton button, int originalIndex) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null) {
            return originalIndex;
        }
        PaginationState state = stateFor(menu);
        if (!state.refresh(menu)) {
            return originalIndex;
        }
        int index = menu.buttons.indexOf(button);
        return index < 0 ? originalIndex : index - state.page * state.pageSize;
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
        private int page;
        private int pageSize;
        private int pageCount;
        private float arrowY;
        private boolean active;
        private final Hitbox leftArrow = new Hitbox(70.0f, 70.0f);
        private final Hitbox rightArrow = new Hitbox(70.0f, 70.0f);
        private final IdentityHashMap<MenuButton, ButtonLayout> vanillaLayout =
            new IdentityHashMap<MenuButton, ButtonLayout>();

        private boolean refresh(MainMenuScreen menu) {
            if (menu.bg == null
                || menu.screen != MainMenuScreen.CurScreen.MAIN_MENU
                || menu.bg.slider >= 0.5f) {
                deactivate(menu);
                clearInput();
                return false;
            }

            ArrayList<MenuButton> buttons = menu.buttons;
            if (buttons == null || buttons.isEmpty()) {
                deactivate(menu);
                clearInput();
                return false;
            }

            if (active) {
                captureMissingVanillaLayout(buttons);
                restoreVanillaLayout(menu);
            } else {
                vanillaLayout.clear();
                captureMissingVanillaLayout(buttons);
            }

            boolean largeLayout = Settings.isTouchScreen || Settings.isMobile;
            float rowSpacing = MenuButton.SPACE_Y * (largeLayout ? 2.0f : 1.0f);
            int firstOverflowIndex = findFirstOverflowIndex(buttons);
            if (firstOverflowIndex < 0) {
                deactivate(menu);
                clearInput();
                return false;
            }

            active = true;
            pageSize = Math.max(1, firstOverflowIndex);
            pageCount = (buttons.size() + pageSize - 1) / pageSize;
            page = Math.min(page, pageCount - 1);
            arrowY = MenuButton.START_Y + pageSize * rowSpacing;
            float arrowOffset = 62.0f * Settings.scale;
            leftArrow.resize(70.0f * Settings.scale, 70.0f * Settings.scale);
            rightArrow.resize(70.0f * Settings.scale, 70.0f * Settings.scale);
            leftArrow.move(Settings.WIDTH / 2.0f - arrowOffset, arrowY);
            rightArrow.move(Settings.WIDTH / 2.0f + arrowOffset, arrowY);

            int firstIndex = page * pageSize;
            int lastIndex = Math.min(buttons.size(), firstIndex + pageSize);
            for (int index = 0; index < buttons.size(); index++) {
                MenuButton button = buttons.get(index);
                if (button == null || button.hb == null) {
                    continue;
                }
                if (index >= firstIndex && index < lastIndex) {
                    float centerX = button.hb.width / 2.0f + 75.0f * Settings.scale;
                    float centerY = MenuButton.START_Y + (index - firstIndex) * rowSpacing;
                    button.hb.move(centerX, centerY);
                } else {
                    clearInput(button.hb);
                }
            }
            return true;
        }

        private int findFirstOverflowIndex(ArrayList<MenuButton> buttons) {
            float availableBottom = Settings.HEIGHT - 120.0f * Settings.scale;
            for (int index = 0; index < buttons.size(); index++) {
                MenuButton button = buttons.get(index);
                ButtonLayout layout = vanillaLayout.get(button);
                if (layout != null && layout.bottom() > availableBottom) {
                    return index;
                }
            }
            return -1;
        }

        private void captureMissingVanillaLayout(ArrayList<MenuButton> buttons) {
            for (MenuButton button : buttons) {
                if (button == null || button.hb == null || vanillaLayout.containsKey(button)) {
                    continue;
                }
                vanillaLayout.put(
                    button,
                    new ButtonLayout(button.hb.cX, button.hb.cY, button.hb.y + button.hb.height)
                );
            }
        }

        private void deactivate(MainMenuScreen menu) {
            if (active) {
                restoreVanillaLayout(menu);
            }
            active = false;
            page = 0;
            pageCount = 0;
            vanillaLayout.clear();
        }

        private void changePage(int offset) {
            page += offset;
            clearInput();
        }

        private void restoreVanillaLayout(MainMenuScreen menu) {
            if (menu.buttons == null) {
                return;
            }
            for (MenuButton button : menu.buttons) {
                if (button == null || button.hb == null) {
                    continue;
                }
                ButtonLayout layout = vanillaLayout.get(button);
                if (layout != null) {
                    button.hb.move(layout.centerX, layout.centerY);
                }
            }
        }

        private void clearInput() {
            clearInput(leftArrow);
            clearInput(rightArrow);
        }

        private static void clearInput(Hitbox hitbox) {
            hitbox.hovered = false;
            hitbox.justHovered = false;
            hitbox.clickStarted = false;
            hitbox.clicked = false;
        }

        private static final class ButtonLayout {
            private final float centerX;
            private final float centerY;
            private final float bottom;

            private ButtonLayout(float centerX, float centerY, float bottom) {
                this.centerX = centerX;
                this.centerY = centerY;
                this.bottom = bottom;
            }

            private float bottom() {
                return bottom;
            }
        }
    }
}

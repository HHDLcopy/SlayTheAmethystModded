package io.stamethyst.compatmod.autoplay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.saveAndContinue.SaveAndContinue;
import com.megacrit.cardcrawl.screens.charSelect.CharacterOption;
import com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MenuButton;

import java.util.ArrayList;

/**
 * Drives the main menu and character-select screens.
 *
 * <p>Fresh-run policy:
 * <ol>
 *   <li>Delete stale autosaves once per autoplay JVM session and remove already-built
 *       Resume/Abandon menu buttons.</li>
 *   <li>Open the character select screen directly, queue a native character-option click, then
 *       trigger the Embark confirm.</li>
 * </ol>
 *
 * <p>The driver never opens the panel screen; it skips straight to {@code charSelectScreen.open()}
 * so we don't have to navigate through {@code MenuPanelScreen → PLAY → PLAY_NORMAL}.</p>
 */
final class AutoplayMainMenuActions {
    private static String lastStateSnapshot;
    private static boolean staleSavesDeleted;

    private AutoplayMainMenuActions() {
    }

    static void tick() {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        logStateChange(menu);
        if (CardCrawlGame.mode == CardCrawlGame.GameMode.GAMEPLAY) {
            return;
        }
        if (menu == null) {
            return;
        }
        if (menu.isFadingOut || menu.fadedOut) {
            return;
        }

        MainMenuScreen.CurScreen curScreen = menu.screen;
        if (curScreen == MainMenuScreen.CurScreen.MAIN_MENU) {
            handleMainMenuRoot(menu);
        } else if (curScreen == MainMenuScreen.CurScreen.CHAR_SELECT) {
            handleCharacterSelect(menu);
        }
        // All other CurScreen states (SAVE_SLOT, SETTINGS, PANEL_MENU, …) are user-initiated
        // and shouldn't be touched by autoplay.
    }

    private static void logStateChange(MainMenuScreen menu) {
        String snapshot = describeState(menu);
        if (snapshot.equals(lastStateSnapshot)) {
            return;
        }
        lastStateSnapshot = snapshot;
        AutoplayLog.info("main menu state: " + snapshot);
    }

    private static String describeState(MainMenuScreen menu) {
        StringBuilder builder = new StringBuilder();
        builder.append("mode=").append(CardCrawlGame.mode);
        if (menu == null) {
            return builder.append(" menu=<null>").toString();
        }
        builder.append(" screen=").append(menu.screen)
            .append(" isFadingOut=").append(menu.isFadingOut)
            .append(" fadedOut=").append(menu.fadedOut)
            .append(" buttons=").append(describeButtons(menu.buttons));
        return builder.toString();
    }

    private static String describeButtons(ArrayList<MenuButton> buttons) {
        if (buttons == null) {
            return "<null>";
        }
        StringBuilder builder = new StringBuilder("[");
        for (MenuButton button : buttons) {
            if (builder.length() > 1) {
                builder.append(',');
            }
            builder.append(button == null ? "<null>" : button.result);
        }
        return builder.append(']').toString();
    }

    private static void handleMainMenuRoot(MainMenuScreen menu) {
        deleteStaleSavesOnce();
        removeStaleResumeButtons(menu);

        // Always skip the panel hop and open character select directly for a fresh autoplay run.
        if (menu.charSelectScreen == null) {
            return;
        }
        AutoplayLog.info("main menu: opening character select for fresh autoplay run");
        menu.charSelectScreen.open(false);
    }

    private static void removeStaleResumeButtons(MainMenuScreen menu) {
        ArrayList<MenuButton> buttons = menu.buttons;
        if (buttons == null) {
            return;
        }
        boolean hasPlayButton = false;
        int removed = 0;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            MenuButton button = buttons.get(i);
            if (button == null) {
                continue;
            }
            if (button.result == MenuButton.ClickResult.PLAY) {
                hasPlayButton = true;
                continue;
            }
            if (button.result == MenuButton.ClickResult.ABANDON_RUN
                || button.result == MenuButton.ClickResult.RESUME_GAME) {
                buttons.remove(i);
                removed++;
            }
        }
        if (removed > 0 && !hasPlayButton) {
            buttons.add(new MenuButton(MenuButton.ClickResult.PLAY, buttons.size()));
        }
        if (removed > 0) {
            AutoplayLog.info("main menu: removed stale resume buttons count=" + removed);
        }
    }

    private static void deleteStaleSavesOnce() {
        if (staleSavesDeleted) {
            return;
        }
        staleSavesDeleted = true;
        int deleted = 0;
        AbstractPlayer.PlayerClass[] classes = AbstractPlayer.PlayerClass.values();
        for (AbstractPlayer.PlayerClass playerClass : classes) {
            String savePath = SaveAndContinue.getPlayerSavePath(playerClass);
            if (deleteSaveFile(savePath)) {
                deleted++;
            }
            if (deleteSaveFile(savePath + ".backUp")) {
                deleted++;
            }
        }
        AutoplayLog.info("main menu: cleared stale autoplay saves count=" + deleted);
    }

    private static boolean deleteSaveFile(String savePath) {
        if (savePath == null || savePath.length() == 0) {
            return false;
        }
        try {
            FileHandle file = Gdx.files.local(savePath);
            if (file.exists() && file.delete()) {
                AutoplayLog.info("main menu: deleted stale save " + savePath);
                return true;
            }
        } catch (Throwable t) {
            AutoplayLog.warn("main menu: failed to delete stale save " + savePath, t);
        }
        return false;
    }

    private static void handleCharacterSelect(MainMenuScreen menu) {
        CharacterSelectScreen screen = menu.charSelectScreen;
        if (screen == null || screen.confirmButton == null) {
            return;
        }
        if (screen.confirmButton.hb == null) {
            return;
        }
        if (screen.confirmButton.hb.clicked) {
            // We already queued a click last tick; let the screen process it.
            return;
        }
        if (screen.options == null || screen.options.isEmpty()) {
            return;
        }

        CharacterOption chosen = pickFirstUnlocked(screen.options);
        if (chosen == null) {
            AutoplayLog.warn("character select: no unlocked option available", null);
            return;
        }

        if (chosen.selected && isIncompleteNativeSelection(chosen)) {
            chosen.selected = false;
            AutoplayLog.info("character select: retrying incomplete selection option=" + describe(chosen));
        }

        if (!chosen.selected) {
            queueCharacterOptionClick(chosen);
            return; // Confirm next tick, after the screen has refreshed.
        }

        if (screen.confirmButton.isDisabled) {
            AutoplayLog.debug("character select: waiting for Embark to enable option=" + describe(chosen));
            return;
        }
        screen.confirmButton.hb.clicked = true;
        AutoplayLog.info("character select: pressed Embark");
    }

    private static boolean isIncompleteNativeSelection(CharacterOption option) {
        if (option == null || option.c == null || option.c.chosenClass == null) {
            return false;
        }
        return CardCrawlGame.chosenCharacter != option.c.chosenClass;
    }

    private static void queueCharacterOptionClick(CharacterOption option) {
        if (option.hb == null) {
            AutoplayLog.warn("character select: option hitbox missing option=" + describe(option), null);
            return;
        }
        if (option.hb.clicked) {
            return;
        }
        InputHelper.mX = Math.round(option.hb.cX);
        InputHelper.mY = Math.round(option.hb.cY);
        option.hb.clicked = true;
        AutoplayLog.info("character select: queued option click=" + describe(option));
    }

    private static CharacterOption pickFirstUnlocked(ArrayList<CharacterOption> options) {
        for (CharacterOption option : options) {
            if (option == null || option.locked) {
                continue;
            }
            return option;
        }
        return null;
    }

    private static String describe(CharacterOption option) {
        if (option == null) {
            return "<null>";
        }
        if (option.c != null && option.c.chosenClass != null) {
            return option.c.chosenClass.name();
        }
        return option.name == null ? "<unnamed>" : option.name;
    }
}

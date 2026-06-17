package io.stamethyst.compatmod.autoplay;

import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.neow.NeowUnlockScreen;
import com.megacrit.cardcrawl.screens.DeathScreen;
import com.megacrit.cardcrawl.screens.GameOverScreen;
import com.megacrit.cardcrawl.ui.buttons.ReturnToMenuButton;
import com.megacrit.cardcrawl.ui.buttons.UnlockConfirmButton;

import java.lang.reflect.Field;

/**
 * Drives death-end screens back to the main menu so autoplay can start the next run.
 */
final class AutoplayEndScreenActions {
    private static Field returnButtonField;
    private static boolean returnButtonFieldLookupFailed;

    private AutoplayEndScreenActions() {
    }

    static void tick() {
        if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) {
            return;
        }
        if (!AbstractDungeon.isScreenUp) {
            return;
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.DEATH) {
            handleDeathScreen();
            return;
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.NEOW_UNLOCK
            && AbstractDungeon.previousScreen == AbstractDungeon.CurrentScreen.DEATH) {
            handleDeathUnlockScreen();
        }
    }

    private static void handleDeathScreen() {
        DeathScreen screen = AbstractDungeon.deathScreen;
        if (screen == null) {
            return;
        }
        ReturnToMenuButton button = readReturnButton(screen);
        if (button == null || !button.show) {
            return;
        }
        queueHitboxClick(button.hb, "death screen return button label=" + describe(button.label));
    }

    private static void handleDeathUnlockScreen() {
        NeowUnlockScreen screen = AbstractDungeon.gUnlockScreen;
        if (screen == null) {
            return;
        }
        UnlockConfirmButton button = screen.button;
        if (button == null) {
            return;
        }
        queueHitboxClick(button.hb, "death unlock confirm button");
    }

    private static void queueHitboxClick(Hitbox hb, String reason) {
        if (hb == null || hb.clicked) {
            return;
        }
        hb.clicked = true;
        AutoplayLog.info("end screen: pressed " + reason);
    }

    private static ReturnToMenuButton readReturnButton(DeathScreen screen) {
        Field field = getReturnButtonField();
        if (field == null) {
            return null;
        }
        try {
            return (ReturnToMenuButton) field.get(screen);
        } catch (Throwable t) {
            returnButtonFieldLookupFailed = true;
            AutoplayLog.warn("end screen: failed to read death return button", t);
            return null;
        }
    }

    private static Field getReturnButtonField() {
        if (returnButtonFieldLookupFailed) {
            return null;
        }
        if (returnButtonField != null) {
            return returnButtonField;
        }
        try {
            Field field = GameOverScreen.class.getDeclaredField("returnButton");
            field.setAccessible(true);
            returnButtonField = field;
            return field;
        } catch (Throwable t) {
            returnButtonFieldLookupFailed = true;
            AutoplayLog.warn("end screen: return button field unavailable", t);
            return null;
        }
    }

    private static String describe(String value) {
        if (value == null || value.length() == 0) {
            return "<empty>";
        }
        return value;
    }
}

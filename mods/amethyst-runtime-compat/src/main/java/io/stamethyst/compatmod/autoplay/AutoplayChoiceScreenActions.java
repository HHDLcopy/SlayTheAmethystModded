package io.stamethyst.compatmod.autoplay;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.ui.buttons.ConfirmButton;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Random;

final class AutoplayChoiceScreenActions {
    private static final Random RNG = new Random();
    private static AbstractCard pendingCardRewardChoice;
    private static long pendingCardRewardChoiceMillis;
    private static boolean pendingCardRewardChoiceArmed;
    private static CardRewardScreen delayedChoiceScreen;
    private static long delayedChoiceScreenMillis;
    private static int delayedChoiceGroupIdentity;
    private static Field cardRewardDiscoveryField;
    private static boolean cardRewardDiscoveryFieldLookupAttempted;

    private AutoplayChoiceScreenActions() {
    }

    static boolean handleOpenChoiceScreen(String source) {
        if (!AbstractDungeon.isScreenUp) {
            clearPendingCardRewardChoice();
            return false;
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CARD_REWARD) {
            AutoplayLog.debug(
                "choice: inspecting card_reward"
                    + " source=" + source
                    + " currentAction=" + currentActionName()
            );
            return chooseCardReward(source);
        }
        clearPendingCardRewardChoice();
        return false;
    }

    private static boolean chooseCardReward(String source) {
        CardRewardScreen screen = AbstractDungeon.cardRewardScreen;
        if (screen == null || screen.rewardGroup == null || screen.rewardGroup.isEmpty()) {
            return false;
        }
        if (screen.discoveryCard != null) {
            return true;
        }
        if (pressCardRewardConfirm(screen, source)) {
            return true;
        }
        if (!choiceDelayElapsed(screen, source)) {
            return true;
        }

        long now = currentTimeMillis();
        if (pendingCardRewardChoice != null && screen.rewardGroup.contains(pendingCardRewardChoice)) {
            queueCardClick(screen, pendingCardRewardChoice);
            return true;
        }
        pendingCardRewardChoice = null;
        pendingCardRewardChoiceMillis = 0L;
        pendingCardRewardChoiceArmed = false;

        AbstractCard selected = pickRandomCard(screen.rewardGroup);
        if (selected == null) {
            return false;
        }

        pendingCardRewardChoice = selected;
        pendingCardRewardChoiceMillis = now;
        pendingCardRewardChoiceArmed = false;
        queueCardClick(screen, selected);
        AutoplayLog.info(
            "choice: queued card_reward"
                + " source=" + source
                + " card=" + safeCardId(selected)
                + " groupSize=" + screen.rewardGroup.size()
                + " currentAction=" + currentActionName()
                + " discovery=" + isDiscoveryScreen(screen)
        );
        return true;
    }

    private static AbstractCard pickRandomCard(ArrayList<AbstractCard> cards) {
        ArrayList<AbstractCard> candidates = new ArrayList<>();
        for (AbstractCard card : cards) {
            if (card != null) {
                candidates.add(card);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(RNG.nextInt(candidates.size()));
    }

    private static boolean isDiscoveryScreen(CardRewardScreen screen) {
        Field field = getCardRewardDiscoveryField(screen);
        if (field == null) {
            return false;
        }
        try {
            return field.getBoolean(screen);
        } catch (Throwable t) {
            AutoplayLog.debug("choice: failed to read card_reward discovery flag");
            return false;
        }
    }

    private static Field getCardRewardDiscoveryField(CardRewardScreen screen) {
        if (screen == null) {
            return null;
        }
        if (cardRewardDiscoveryField != null || cardRewardDiscoveryFieldLookupAttempted) {
            return cardRewardDiscoveryField;
        }
        cardRewardDiscoveryFieldLookupAttempted = true;
        try {
            cardRewardDiscoveryField = screen.getClass().getDeclaredField("discovery");
            cardRewardDiscoveryField.setAccessible(true);
        } catch (Throwable t) {
            AutoplayLog.debug("choice: card_reward discovery flag unavailable");
        }
        return cardRewardDiscoveryField;
    }

    private static void queueCardClick(CardRewardScreen screen, AbstractCard card) {
        clearOtherRewardHitboxes(screen, card);
        if (card.hb == null) {
            screen.discoveryCard = card;
            AutoplayLog.info("choice: set card_reward discoveryCard card=" + safeCardId(card));
            return;
        }
        if (!isHitboxOnScreen(card.hb)) {
            return;
        }
        moveInputTo(card.hb);
        if (!pendingCardRewardChoiceArmed) {
            pendingCardRewardChoiceArmed = true;
            return;
        }
        if (!card.hb.hovered && !containsInput(card.hb)) {
            return;
        }
        card.hb.hovered = true;
        card.hb.justHovered = true;
        card.hb.clickStarted = true;
        card.hb.clicked = true;
        InputHelper.justClickedLeft = true;
        InputHelper.isMouseDown = false;
        InputHelper.justReleasedClickLeft = false;
    }

    private static void clearOtherRewardHitboxes(CardRewardScreen screen, AbstractCard selected) {
        if (screen == null || screen.rewardGroup == null) {
            return;
        }
        for (AbstractCard card : screen.rewardGroup) {
            if (card == null || card == selected || card.hb == null) {
                continue;
            }
            card.hb.clicked = false;
            card.hb.clickStarted = false;
        }
    }

    private static boolean isHitboxOnScreen(Hitbox hitbox) {
        if (hitbox == null) {
            return false;
        }
        return hitbox.cX >= 0.0F
            && hitbox.cX <= Settings.WIDTH
            && hitbox.cY >= 0.0F
            && hitbox.cY <= Settings.HEIGHT;
    }

    private static boolean containsInput(Hitbox hitbox) {
        if (hitbox == null) {
            return false;
        }
        return InputHelper.mX >= hitbox.x
            && InputHelper.mX <= hitbox.x + hitbox.width
            && InputHelper.mY >= hitbox.y
            && InputHelper.mY <= hitbox.y + hitbox.height;
    }

    private static void moveInputTo(Hitbox hitbox) {
        int x = Math.round(hitbox.cX);
        int y = Math.round(hitbox.cY);
        InputHelper.mX = x;
        InputHelper.mY = y;
        try {
            if (com.badlogic.gdx.Gdx.input != null) {
                com.badlogic.gdx.Gdx.input.setCursorPosition(x, Settings.HEIGHT - y);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean pressCardRewardConfirm(CardRewardScreen screen, String source) {
        ConfirmButton button = screen.confirmButton;
        if (button == null || button.hb == null || button.isDisabled || button.hb.clicked) {
            return false;
        }
        button.hb.clicked = true;
        AutoplayLog.info(
            "choice: confirmed card_reward"
                + " source=" + source
                + " currentAction=" + currentActionName()
        );
        clearPendingCardRewardChoice();
        return true;
    }

    private static void clearPendingCardRewardChoice() {
        pendingCardRewardChoice = null;
        pendingCardRewardChoiceMillis = 0L;
        pendingCardRewardChoiceArmed = false;
        delayedChoiceScreen = null;
        delayedChoiceScreenMillis = 0L;
        delayedChoiceGroupIdentity = 0;
    }

    private static boolean choiceDelayElapsed(CardRewardScreen screen, String source) {
        long delayMs = AutoplayConfig.getChoiceDelayMs();
        if (delayMs <= 0L) {
            return true;
        }
        long now = currentTimeMillis();
        int groupIdentity = screen.rewardGroup == null ? 0 : System.identityHashCode(screen.rewardGroup);
        if (delayedChoiceScreen != screen || delayedChoiceGroupIdentity != groupIdentity) {
            delayedChoiceScreen = screen;
            delayedChoiceScreenMillis = now;
            delayedChoiceGroupIdentity = groupIdentity;
            pendingCardRewardChoice = null;
            pendingCardRewardChoiceMillis = 0L;
            pendingCardRewardChoiceArmed = false;
            AutoplayLog.info(
                "choice: delaying card_reward"
                    + " source=" + source
                    + " delayMs=" + delayMs
                    + " currentAction=" + currentActionName()
            );
            return false;
        }
        return now - delayedChoiceScreenMillis >= delayMs;
    }

    private static String safeCardId(AbstractCard card) {
        if (card == null || card.cardID == null || card.cardID.length() == 0) {
            return "<unknown>";
        }
        return card.cardID.replace(' ', '_');
    }

    private static String currentActionName() {
        GameActionManager actionManager = AbstractDungeon.actionManager;
        if (actionManager == null || actionManager.currentAction == null) {
            return "<none>";
        }
        return actionManager.currentAction.getClass().getName();
    }

    private static long currentTimeMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}

package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.actions.utility.WaitAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.vfx.AbstractGameEffect;
import com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndAddToDiscardEffect;
import com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndAddToHandEffect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public final class GrimmRedHoodPowerCompatPatches {
    private static final String RED_HOOD_POWER_CLASS = "GrimmFairyTalesDLC.powers.RedHoodPower";
    private static final String ENABLED_PROP =
        "amethyst.runtime_compat.grimm_red_hood_serial_choices";
    private static final String LOG_PREFIX = "[amethyst-runtime-compat] ";
    private static boolean serialActionLogged;
    private static boolean failureLogged;

    private GrimmRedHoodPowerCompatPatches() {
    }

    @SpirePatch2(
        cls = RED_HOOD_POWER_CLASS,
        method = "atStartOfTurn",
        optional = true
    )
    public static class RedHoodPowerAtStartOfTurnPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> before(Object __instance) {
            if (!isEnabled()) {
                return SpireReturn.Continue();
            }
            if (!(__instance instanceof AbstractPower)) {
                return SpireReturn.Continue();
            }
            if (AbstractDungeon.getMonsters().areMonstersBasicallyDead()) {
                return SpireReturn.Return();
            }

            AbstractPower power = (AbstractPower)__instance;
            power.flash();
            AbstractDungeon.actionManager.addToBottom(new SerialRedHoodAction(Math.max(power.amount, 0)));
            logSerialActionOnce();
            return SpireReturn.Return();
        }
    }

    private static final class SerialRedHoodAction extends AbstractGameAction {
        private static final int SETTLE_FRAMES_AFTER_CHOICE = 3;
        private static final List<String> RED_HOOD_CARD_CLASSES = Arrays.asList(
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodNight",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodSmile",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodBreath",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodClean",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodBlunt",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodEat",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodFast",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodFire",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodJump",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodSlash",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodStage",
            "GrimmFairyTalesDLC.cards.GrimmDLC.RedHoodWolf"
        );

        private final int choicesToResolve;
        private final List<AbstractGameEffect> pendingObtainEffects =
            new ArrayList<AbstractGameEffect>();
        private int resolvedChoices;
        private int settleFrames;
        private boolean screenOpen;

        private SerialRedHoodAction(int choicesToResolve) {
            this.choicesToResolve = choicesToResolve;
            this.actionType = ActionType.CARD_MANIPULATION;
        }

        @Override
        public void update() {
            if (choicesToResolve <= 0 || AbstractDungeon.getMonsters().areMonstersBasicallyDead()) {
                isDone = true;
                return;
            }
            if (!screenOpen && hasPendingObtainEffects()) {
                clearClickEdges();
                return;
            }
            if (!screenOpen && (settleFrames > 0 || hasActiveClickInput())) {
                settleFrames = Math.max(settleFrames - 1, 0);
                clearClickEdges();
                return;
            }
            if (!screenOpen && resolvedChoices >= choicesToResolve) {
                AbstractDungeon.actionManager.addToTop(new WaitAction(0.05F));
                isDone = true;
                return;
            }
            if (!screenOpen) {
                openNextChoice();
                return;
            }

            AbstractCard selectedCard = AbstractDungeon.cardRewardScreen.discoveryCard;
            if (selectedCard == null) {
                return;
            }

            AbstractGameEffect effect = addSelectedCard(selectedCard);
            if (effect != null) {
                pendingObtainEffects.add(effect);
            }
            AbstractDungeon.cardRewardScreen.discoveryCard = null;
            clearRewardScreenInput(AbstractDungeon.cardRewardScreen);
            clearPlayerHandInputState();
            screenOpen = false;
            resolvedChoices++;
            settleFrames = SETTLE_FRAMES_AFTER_CHOICE;
        }

        private void openNextChoice() {
            CardRewardScreen screen = AbstractDungeon.cardRewardScreen;
            clearRewardScreenInput(screen);
            screen.customCombatOpen(
                generateRedHoodCardChoices(),
                CardRewardScreen.TEXT[1],
                false
            );
            screenOpen = true;
        }

        private static ArrayList<AbstractCard> generateRedHoodCardChoices() {
            ArrayList<AbstractCard> cards = new ArrayList<AbstractCard>();
            int guard = 0;
            while (cards.size() < 3 && guard < 60) {
                guard++;
                AbstractCard card = randomRedHoodCard();
                if (card == null || containsCardId(cards, card.cardID)) {
                    continue;
                }
                cards.add(card.makeCopy());
            }
            return cards;
        }

        private static boolean containsCardId(ArrayList<AbstractCard> cards, String cardId) {
            for (AbstractCard card : cards) {
                if (card != null && card.cardID != null && card.cardID.equals(cardId)) {
                    return true;
                }
            }
            return false;
        }

        private static AbstractCard randomRedHoodCard() {
            if (RED_HOOD_CARD_CLASSES.isEmpty()) {
                return null;
            }
            int index = AbstractDungeon.cardRandomRng.random(RED_HOOD_CARD_CLASSES.size() - 1);
            return instantiateCard(RED_HOOD_CARD_CLASSES.get(index));
        }

        private static AbstractCard instantiateCard(String className) {
            try {
                Object instance = Class.forName(className).newInstance();
                if (instance instanceof AbstractCard) {
                    return (AbstractCard)instance;
                }
            } catch (Throwable throwable) {
                logFailureOnce("failed to create Grimm RedHood choice " + className, throwable);
            }
            return null;
        }

        private boolean hasPendingObtainEffects() {
            Iterator<AbstractGameEffect> iterator = pendingObtainEffects.iterator();
            while (iterator.hasNext()) {
                AbstractGameEffect effect = iterator.next();
                if (effect == null
                    || effect.isDone
                    || !AbstractDungeon.effectList.contains(effect)) {
                    iterator.remove();
                }
            }
            return !pendingObtainEffects.isEmpty();
        }

        private static AbstractGameEffect addSelectedCard(AbstractCard selectedCard) {
            AbstractCard firstCopy = selectedCard.makeStatEquivalentCopy();
            firstCopy.upgrade();
            firstCopy.setCostForTurn(0);
            firstCopy.current_x = -1000.0F * com.megacrit.cardcrawl.core.Settings.xScale;

            AbstractPlayer player = AbstractDungeon.player;
            if (player == null || player.hand == null) {
                return null;
            }

            AbstractGameEffect effect;
            if (player.hand.size() < 10) {
                effect = new ShowCardAndAddToHandEffect(
                    firstCopy,
                    com.megacrit.cardcrawl.core.Settings.WIDTH / 2.0F,
                    com.megacrit.cardcrawl.core.Settings.HEIGHT / 2.0F
                );
            } else {
                effect = new ShowCardAndAddToDiscardEffect(
                    firstCopy,
                    com.megacrit.cardcrawl.core.Settings.WIDTH / 2.0F,
                    com.megacrit.cardcrawl.core.Settings.HEIGHT / 2.0F
                );
            }
            AbstractDungeon.effectList.add(effect);
            return effect;
        }

        private static void clearRewardScreenInput(CardRewardScreen screen) {
            if (screen != null) {
                screen.discoveryCard = null;
                clearCurrentRewardCards(screen);
            }
            clearClickEdges();
        }

        private static void clearCurrentRewardCards(CardRewardScreen screen) {
            if (screen == null || screen.rewardGroup == null) {
                return;
            }
            for (AbstractCard card : screen.rewardGroup) {
                if (card == null) {
                    continue;
                }
                resetHitbox(card.hb);
                try {
                    card.unhover();
                    card.untip();
                } catch (RuntimeException ignored) {
                }
            }
        }

        private static void clearPlayerHandInputState() {
            AbstractPlayer player = AbstractDungeon.player;
            if (player == null) {
                return;
            }
            player.hoveredCard = null;
            player.toHover = null;
            player.isDraggingCard = false;
            player.isHoveringDropZone = false;
            try {
                player.releaseCard();
            } catch (RuntimeException ignored) {
            }
            try {
                if (player.hand != null) {
                    player.hand.refreshHandLayout();
                }
            } catch (RuntimeException ignored) {
            }
        }

        private static void resetHitbox(Hitbox hitbox) {
            if (hitbox == null) {
                return;
            }
            hitbox.clicked = false;
            hitbox.clickStarted = false;
            hitbox.hovered = false;
            hitbox.justHovered = false;
        }

        private static boolean hasActiveClickInput() {
            return InputHelper.isMouseDown
                || InputHelper.isMouseDown_R
                || InputHelper.justClickedLeft
                || InputHelper.justClickedRight
                || InputHelper.justReleasedClickLeft
                || InputHelper.justReleasedClickRight;
        }

        private static void clearClickEdges() {
            InputHelper.justClickedLeft = false;
            InputHelper.justClickedRight = false;
            InputHelper.justReleasedClickLeft = false;
            InputHelper.justReleasedClickRight = false;
        }
    }

    private static boolean isEnabled() {
        return readBooleanSystemProperty(ENABLED_PROP, isAmethystRuntime());
    }

    private static boolean isAmethystRuntime() {
        return System.getProperty("amethyst.gdx.native_dir") != null
            || System.getProperty("amethyst.expected_exit_marker") != null;
    }

    private static void logSerialActionOnce() {
        if (serialActionLogged) {
            return;
        }
        serialActionLogged = true;
        System.out.println(
            LOG_PREFIX
                + "Grimm RedHoodPower choices are serialized to avoid reward-screen state reuse"
        );
    }

    private static void logFailureOnce(String reason, Throwable throwable) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        System.out.println(
            LOG_PREFIX
                + "Grimm RedHoodPower compatibility patch skipped a choice: "
                + reason
                + " ("
                + throwable.getClass().getSimpleName()
                + ": "
                + throwable.getMessage()
                + ")"
        );
    }

    private static boolean readBooleanSystemProperty(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim();
        if (normalized.length() == 0) {
            return defaultValue;
        }
        if ("0".equals(normalized) || "false".equalsIgnoreCase(normalized)) {
            return false;
        }
        if ("off".equalsIgnoreCase(normalized)) {
            return false;
        }
        return true;
    }
}

package io.stamethyst.compatmod;

import com.badlogic.gdx.Input;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

public final class TouchscreenCardInputRuntime {
    private static final float TOUCH_SLOP = 30.0f;
    private static final float PLAY_GESTURE_MIN_UPWARD_DRAG = 90.0f;
    private static final float TARGET_ASSIST_PADDING = 80.0f;
    private static AbstractPlayer gesturePlayer;
    private static AbstractCard gestureCard;
    private static int gestureStartX;
    private static int gestureStartY;
    private static int gestureMaxY;
    private static AbstractPlayer tapInspectPlayer;
    private static AbstractCard tapInspectCard;
    private static int tapInspectStartX;
    private static int tapInspectStartY;
    private static boolean tapInspectSawRelease;
    private static AbstractCard inspectedTouchCard;
    private static final Map<AbstractCard, Float> preservedHoverDrawScales = new IdentityHashMap<>();
    private static boolean unconfirmedDropCancelLogged;
    private static boolean inspectCountClampLogged;
    private static boolean cursorWarpSuppressionLogged;
    private static boolean idleCardHoverCleanupLogged;
    private static boolean tapInspectLogged;
    private static boolean targetAssistHoverLogged;
    private static boolean reflectionFailureLogged;
    private static Field touchscreenInspectCountField;
    private static boolean touchscreenInspectCountFieldResolved;

    private TouchscreenCardInputRuntime() {
    }

    public static boolean isNativeTouchscreenCardInputActive() {
        return CompatRuntimeState.resolveVanillaAllowlistedTouchscreenFlag(Settings.isTouchScreen)
            && !Settings.isControllerMode;
    }

    public static boolean isTargetedCard(AbstractCard card) {
        return card != null
            && (card.target == AbstractCard.CardTarget.ENEMY
            || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY);
    }

    public static void beforeClickAndDragCards(AbstractPlayer player) {
        if (!isCardGestureTrackingActive()) {
            clearGesture();
            return;
        }
        if (player == null) {
            clearGesture();
            return;
        }

        if (InputHelper.justClickedLeft && player.hoveredCard != null && !player.isDraggingCard) {
            startGesture(player, player.hoveredCard);
        }
        if (InputHelper.justReleasedClickLeft && player.hoveredCard != null) {
            clampTouchscreenInspectCountForRelease(player);
        }
        updateGestureIfCurrent(player);
    }

    public static void afterClickAndDragCards(AbstractPlayer player) {
        if (!isCardGestureTrackingActive()) {
            clearGesture();
            return;
        }
        updateGestureIfCurrent(player);
        if (InputHelper.justReleasedClickLeft) {
            clampTouchscreenInspectCountForRelease(player);
            clearGesture();
            return;
        }
        if (!InputHelper.isMouseDown && (player == null || !player.isDraggingCard)) {
            clearGesture();
        }
    }

    public static void beforeTouchscreenTapInspect(AbstractPlayer player) {
        tapInspectSawRelease = InputHelper.justReleasedClickLeft;
        if (!isCardTapInspectActive()) {
            clearTapInspectCandidate();
            clearTouchInspect();
            return;
        }
        if (player == null) {
            clearTapInspectCandidate();
            syncTouchInspect(null);
            return;
        }

        syncTouchInspect(player);
        if (InputHelper.justClickedLeft) {
            AbstractCard touchedCard = getTouchedHandCard(player);
            if (inspectedTouchCard != null && touchedCard == null) {
                clearTouchInspectForPlayer(player, true);
                player.hoveredCard = null;
                player.toHover = null;
                player.isDraggingCard = false;
                player.isHoveringDropZone = false;
                clearTapInspectCandidate();
                return;
            }
            if (inspectedTouchCard != null && touchedCard != null && touchedCard != inspectedTouchCard) {
                clearTouchInspectForPlayer(player, true);
                player.hoveredCard = touchedCard;
                player.toHover = null;
            }
            if (touchedCard != null && !player.isDraggingCard) {
                setTapInspectCandidate(player, touchedCard);
            }
        }
        if (InputHelper.justReleasedClickLeft
            && tapInspectCard == null
            && player.hoveredCard != null
            && isCardInPlayerHand(player, player.hoveredCard)) {
            setTapInspectCandidate(player, player.hoveredCard);
        }
    }

    public static void afterTouchscreenTapInspect(AbstractPlayer player) {
        if (!isCardTapInspectActive()) {
            clearTapInspectCandidate();
            clearTouchInspect();
            tapInspectSawRelease = false;
            return;
        }
        if (player == null) {
            clearTapInspectCandidate();
            syncTouchInspect(null);
            tapInspectSawRelease = false;
            return;
        }

        if (tapInspectSawRelease) {
            AbstractCard card = tapInspectCard != null ? tapInspectCard : player.hoveredCard;
            if (shouldKeepCardInspectedAfterTap(player, card)) {
                keepCardInspectedAfterTap(player, card);
            }
            clearTapInspectCandidate();
        } else {
            stabilizeTapInspectCandidateHover(player);
        }
        syncTouchInspect(player);
        tapInspectSawRelease = false;
    }

    public static void beforeReleaseCard(AbstractPlayer player) {
        clearTouchInspect();
        if (player == null || tapInspectPlayer == player) {
            clearTapInspectCandidate();
        }
    }

    public static void beforePlayCard(AbstractPlayer player) {
        clearTouchInspect();
        if (player == null || tapInspectPlayer == player) {
            clearTapInspectCandidate();
        }
    }

    public static boolean resolveInspectedCardHoveredInHand(boolean originalHovered, AbstractCard card) {
        return originalHovered || isTouchInspectCard(card);
    }

    public static boolean resolveInspectedCardHover(boolean originalHovered, AbstractCard card) {
        return originalHovered || isTouchInspectCard(card);
    }

    public static void setAnimatedTouchHoverCurrentY(AbstractCard card, float value) {
        if (!shouldAnimateNativeTouchHandHover(card)) {
            card.current_y = value;
            return;
        }
        card.target_y = value;
    }

    public static void setAnimatedTouchHoverDrawScale(AbstractCard card, float value) {
        if (!shouldAnimateNativeTouchHandHover(card)) {
            card.drawScale = value;
            return;
        }
        card.targetDrawScale = value;
    }

    public static void beforeCardHover(AbstractCard card) {
        if (!shouldAnimateNativeTouchHandHover(card)) {
            return;
        }
        preservedHoverDrawScales.put(card, card.drawScale);
    }

    public static void afterCardHover(AbstractCard card) {
        Float drawScale = preservedHoverDrawScales.remove(card);
        if (drawScale == null) {
            return;
        }
        card.drawScale = drawScale;
        card.targetDrawScale = 1.0f;
    }

    public static boolean shouldCancelUnconfirmedDropPlay(AbstractPlayer player) {
        if (!isCardGestureTrackingActive()) {
            return false;
        }
        if (player == null || player.hoveredCard == null) {
            return false;
        }
        if (!InputHelper.justReleasedClickLeft || !player.isDraggingCard || !player.isHoveringDropZone) {
            return false;
        }
        if (isTargetedCard(player.hoveredCard)) {
            return false;
        }
        if (!isGestureFor(player, player.hoveredCard)) {
            return false;
        }
        return !hasConfirmedPlayGesture();
    }

    public static void cancelUnconfirmedDropPlay(AbstractPlayer player) {
        playCancelSound();
        if (player != null) {
            player.releaseCard();
            clampTouchscreenInspectCountForRelease(player);
        }
        clearGesture();
        if (!unconfirmedDropCancelLogged) {
            unconfirmedDropCancelLogged = true;
            System.out.println(
                "[amethyst-runtime-compat] touchscreen card drop ignored: tap did not include an upward play gesture"
            );
        }
    }

    public static void releaseSelectedCard(AbstractPlayer player) {
        if (player != null) {
            player.releaseCard();
        }
        clearGesture();
    }

    public static void clearIdleCardHoverBeforeUpdate(AbstractPlayer player) {
        syncTouchInspect(player);
        if (!shouldSuppressIdleCardHover()) {
            return;
        }
        if (player == null || player.hoveredCard == null) {
            return;
        }
        if (player.isDraggingCard || player.inSingleTargetMode || player.isInKeyboardMode) {
            return;
        }
        if (!isCardInPlayerHand(player, player.hoveredCard)) {
            return;
        }
        if (isTouchInspectCard(player.hoveredCard)) {
            return;
        }
        player.releaseCard();
        player.toHover = null;
        clearGesture();
        logIdleCardHoverCleanupOnce();
    }

    public static AbstractCard getHoveredCardForNativeTouch(CardGroup group) {
        if (group == null) {
            return null;
        }
        if (shouldSuppressIdleCardHover() && isCurrentPlayerHandGroup(group)) {
            return null;
        }
        return group.getHoveredCard();
    }

    public static boolean isHoveringLiveMonster() {
        return findLiveMonsterNearInput(0.0f) != null;
    }

    public static boolean isHoveringOrNearLiveMonster() {
        if (!isTargetAssistActive()) {
            return isHoveringLiveMonster();
        }
        return findLiveMonsterNearInput(getTargetAssistPadding()) != null;
    }

    public static boolean resolveSingleTargetMonsterHover(boolean originalHovered, Hitbox hitbox) {
        if (originalHovered) {
            return true;
        }
        if (!isTargetAssistActive() || hitbox == null) {
            return false;
        }
        AbstractMonster monster = findLiveMonsterNearInput(getTargetAssistPadding());
        if (monster == null || monster.hb != hitbox) {
            return false;
        }
        logTargetAssistHoverOnce(monster);
        return true;
    }

    public static AbstractMonster findLiveMonsterNearInput(float padding) {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room == null || room.monsters == null || room.monsters.areMonstersBasicallyDead()) {
                return null;
            }

            AbstractMonster bestMonster = null;
            float bestDistance = Float.MAX_VALUE;
            for (AbstractMonster monster : room.monsters.monsters) {
                if (!isLiveMonster(monster) || monster.hb == null) {
                    continue;
                }
                Hitbox hb = monster.hb;
                if (!isInputInsideHitbox(hb, padding)) {
                    continue;
                }
                float dx = InputHelper.mX - hb.cX;
                float dy = InputHelper.mY - hb.cY;
                float distance = dx * dx + dy * dy;
                if (bestMonster == null || distance < bestDistance) {
                    bestMonster = monster;
                    bestDistance = distance;
                }
            }
            return bestMonster;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void setCombatTouchCursorPosition(Input input, int x, int y) {
        if (input == null) {
            return;
        }
        if (shouldSuppressCombatCursorWarp()) {
            logCursorWarpSuppressionOnce();
            return;
        }
        input.setCursorPosition(x, y);
    }

    private static boolean isCardGestureTrackingActive() {
        return isNativeTouchscreenCardInputActive()
            && CompatRuntimeState.isTouchscreenCardGestureEnabled();
    }

    private static boolean isCardTapInspectActive() {
        return isNativeTouchscreenCardInputActive()
            && CompatRuntimeState.isTouchscreenCardTapInspectEnabled();
    }

    private static boolean isTargetAssistActive() {
        return isNativeTouchscreenCardInputActive()
            && CompatRuntimeState.isTouchscreenTargetAssistEnabled();
    }

    private static boolean shouldSuppressCombatCursorWarp() {
        return isNativeTouchscreenCardInputActive()
            && CompatRuntimeState.isTouchscreenCursorWarpCleanupEnabled();
    }

    private static boolean shouldSuppressIdleCardHover() {
        return isNativeTouchscreenCardInputActive()
            && CompatRuntimeState.isTouchscreenIdleCardHoverCleanupEnabled()
            && !InputHelper.isMouseDown
            && !InputHelper.justClickedLeft
            && !InputHelper.justReleasedClickLeft;
    }

    private static void startGesture(AbstractPlayer player, AbstractCard card) {
        gesturePlayer = player;
        gestureCard = card;
        gestureStartX = InputHelper.mX;
        gestureStartY = InputHelper.mY;
        gestureMaxY = InputHelper.mY;
    }

    private static void updateGestureIfCurrent(AbstractPlayer player) {
        if (player == null || !isGestureFor(player, gestureCard)) {
            return;
        }
        if (InputHelper.mY > gestureMaxY) {
            gestureMaxY = InputHelper.mY;
        }
    }

    private static boolean isGestureFor(AbstractPlayer player, AbstractCard card) {
        return gesturePlayer == player && gestureCard != null && gestureCard == card;
    }

    private static boolean hasConfirmedPlayGesture() {
        int upwardDrag = gestureMaxY - gestureStartY;
        if (upwardDrag >= scaled(PLAY_GESTURE_MIN_UPWARD_DRAG)) {
            return true;
        }
        int dx = Math.abs(InputHelper.mX - gestureStartX);
        int dy = Math.abs(InputHelper.mY - gestureStartY);
        if (dx <= scaled(TOUCH_SLOP) && dy <= scaled(TOUCH_SLOP)) {
            return false;
        }
        return upwardDrag >= scaled(PLAY_GESTURE_MIN_UPWARD_DRAG);
    }

    private static void setTapInspectCandidate(AbstractPlayer player, AbstractCard card) {
        tapInspectPlayer = player;
        tapInspectCard = card;
        tapInspectStartX = InputHelper.mX;
        tapInspectStartY = InputHelper.mY;
    }

    private static void clearTapInspectCandidate() {
        tapInspectPlayer = null;
        tapInspectCard = null;
        tapInspectStartX = 0;
        tapInspectStartY = 0;
    }

    private static boolean shouldKeepCardInspectedAfterTap(AbstractPlayer player, AbstractCard card) {
        if (player == null || card == null) {
            return false;
        }
        if (tapInspectPlayer != null && tapInspectPlayer != player) {
            return false;
        }
        if (!isCardInPlayerHand(player, card)) {
            return false;
        }
        if (player.inSingleTargetMode || player.isHoveringDropZone) {
            return false;
        }
        return isTapInspectGesture();
    }

    private static boolean isTapInspectGesture() {
        if (tapInspectCard == null) {
            return true;
        }
        int dx = Math.abs(InputHelper.mX - tapInspectStartX);
        int dy = Math.abs(InputHelper.mY - tapInspectStartY);
        return dx <= scaled(TOUCH_SLOP) && dy <= scaled(TOUCH_SLOP);
    }

    private static void keepCardInspectedAfterTap(AbstractPlayer player, AbstractCard card) {
        inspectedTouchCard = card;
        player.hoveredCard = card;
        player.toHover = null;
        player.isDraggingCard = false;
        player.isHoveringDropZone = false;
        animateCardToNaturalHover(card);
        card.hover();
        logTapInspectOnce();
    }

    private static void stabilizeTapInspectCandidateHover(AbstractPlayer player) {
        if (player == null || tapInspectCard == null) {
            return;
        }
        if (tapInspectPlayer != player || player.hoveredCard != tapInspectCard) {
            return;
        }
        if (!InputHelper.isMouseDown || InputHelper.justReleasedClickLeft) {
            return;
        }
        if (!isCardInPlayerHand(player, tapInspectCard) || !isTapInspectGesture()) {
            return;
        }
        if (player.inSingleTargetMode || player.isHoveringDropZone) {
            return;
        }
        animateCardToNaturalHover(tapInspectCard);
    }

    private static void syncTouchInspect(AbstractPlayer player) {
        if (inspectedTouchCard == null) {
            return;
        }
        if (player == null) {
            clearTouchInspect();
            return;
        }
        if (!isCardInPlayerHand(player, inspectedTouchCard)) {
            clearTouchInspect();
            return;
        }
        if (player.hoveredCard != inspectedTouchCard && player.toHover != inspectedTouchCard) {
            clearTouchInspectForPlayer(player, true);
            return;
        }
        if (player.isDraggingCard || player.inSingleTargetMode) {
            clearTouchInspect();
        }
    }

    private static void clearTouchInspect() {
        inspectedTouchCard = null;
    }

    private static void clearTouchInspectForPlayer(AbstractPlayer player, boolean refreshHandLayout) {
        AbstractCard card = inspectedTouchCard;
        inspectedTouchCard = null;
        if (card != null) {
            if (player != null) {
                if (player.hoveredCard == card) {
                    player.hoveredCard = null;
                }
                if (player.toHover == card) {
                    player.toHover = null;
                }
            }
            card.unhover();
        }
        if (!refreshHandLayout || player == null || player.hand == null) {
            return;
        }
        try {
            player.hand.refreshHandLayout();
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean isTouchInspectCard(AbstractCard card) {
        return card != null
            && inspectedTouchCard == card
            && isCardTapInspectActive()
            && AbstractDungeon.player != null
            && isCardInPlayerHand(AbstractDungeon.player, card);
    }

    private static AbstractCard getTouchedHandCard(AbstractPlayer player) {
        try {
            if (player == null || player.hand == null) {
                return null;
            }
            return player.hand.getHoveredCard();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void animateCardToNaturalHover(AbstractCard card) {
        if (card == null) {
            return;
        }
        card.target_y = AbstractPlayer.HOVER_CARD_Y_POSITION;
        card.targetDrawScale = 1.0f;
        card.setAngle(0.0f);
    }

    private static boolean shouldAnimateNativeTouchHandHover(AbstractCard card) {
        if (!isCardTapInspectActive()) {
            return false;
        }
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || card == null || !isCardInPlayerHand(player, card)) {
            return false;
        }
        if (player.isDraggingCard || player.inSingleTargetMode || player.isHoveringDropZone) {
            return false;
        }
        return InputHelper.isMouseDown
            || InputHelper.justClickedLeft
            || InputHelper.justReleasedClickLeft
            || isTouchInspectCard(card);
    }

    private static void clearGesture() {
        gesturePlayer = null;
        gestureCard = null;
        gestureStartX = 0;
        gestureStartY = 0;
        gestureMaxY = 0;
    }

    private static boolean isInputInsideHitbox(Hitbox hb, float padding) {
        return InputHelper.mX >= hb.x - padding
            && InputHelper.mX <= hb.x + hb.width + padding
            && InputHelper.mY >= hb.y - padding
            && InputHelper.mY <= hb.y + hb.height + padding;
    }

    private static boolean isCurrentPlayerHandGroup(CardGroup group) {
        try {
            return group != null && AbstractDungeon.player != null && group == AbstractDungeon.player.hand;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isCardInPlayerHand(AbstractPlayer player, AbstractCard card) {
        try {
            return player != null
                && card != null
                && player.hand != null
                && player.hand.group != null
                && player.hand.group.contains(card);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isLiveMonster(AbstractMonster monster) {
        return monster != null
            && !monster.isDying
            && !monster.isEscaping
            && monster.currentHealth > 0;
    }

    private static void clampTouchscreenInspectCountForRelease(AbstractPlayer player) {
        Field field = getTouchscreenInspectCountField();
        if (field == null || player == null) {
            return;
        }
        try {
            if (field.getInt(player) != 0) {
                field.setInt(player, 0);
                if (!inspectCountClampLogged) {
                    inspectCountClampLogged = true;
                    System.out.println(
                        "[amethyst-runtime-compat] touchscreen inspect-card switch disabled for native touch card input"
                    );
                }
            }
        } catch (IllegalAccessException e) {
            logReflectionFailureOnce("touchscreenInspectCount", e);
        } catch (RuntimeException e) {
            logReflectionFailureOnce("touchscreenInspectCount", e);
        }
    }

    private static Field getTouchscreenInspectCountField() {
        if (!touchscreenInspectCountFieldResolved) {
            touchscreenInspectCountFieldResolved = true;
            touchscreenInspectCountField = findField(AbstractPlayer.class, "touchscreenInspectCount");
        }
        return touchscreenInspectCountField;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (RuntimeException e) {
                logReflectionFailureOnce(name, e);
                return null;
            }
        }
        logReflectionFailureOnce(name, new NoSuchFieldException(name));
        return null;
    }

    private static int scaled(float value) {
        return Math.round(value * Settings.scale);
    }

    private static float getTargetAssistPadding() {
        return TARGET_ASSIST_PADDING * Settings.scale;
    }

    private static void playCancelSound() {
        try {
            if (CardCrawlGame.sound != null) {
                CardCrawlGame.sound.play("UI_CLICK_2");
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void logCursorWarpSuppressionOnce() {
        if (cursorWarpSuppressionLogged) {
            return;
        }
        cursorWarpSuppressionLogged = true;
        System.out.println(
            "[amethyst-runtime-compat] touchscreen combat cursor warp suppressed for native touch input"
        );
    }

    private static void logIdleCardHoverCleanupOnce() {
        if (idleCardHoverCleanupLogged) {
            return;
        }
        idleCardHoverCleanupLogged = true;
        System.out.println(
            "[amethyst-runtime-compat] touchscreen idle hand-card hover cleared while no touch is active"
        );
    }

    private static void logTapInspectOnce() {
        if (tapInspectLogged) {
            return;
        }
        tapInspectLogged = true;
        System.out.println(
            "[amethyst-runtime-compat] touchscreen card tap preserved inspect-only hand-card hover"
        );
    }

    private static void logTargetAssistHoverOnce(AbstractMonster monster) {
        if (targetAssistHoverLogged) {
            return;
        }
        targetAssistHoverLogged = true;
        System.out.println(
            "[amethyst-runtime-compat] touchscreen target assist mapped near tap to "
                + describeMonster(monster)
        );
    }

    private static void logReflectionFailureOnce(String member, Throwable error) {
        if (reflectionFailureLogged) {
            return;
        }
        reflectionFailureLogged = true;
        System.out.println(
            "[amethyst-runtime-compat] touchscreen card input reflection fallback member="
                + member
                + " reason="
                + error.getClass().getSimpleName()
                + ": "
                + error.getMessage()
        );
    }

    private static String describeMonster(AbstractMonster monster) {
        if (monster == null) {
            return "<null>";
        }
        if (monster.id != null) {
            return monster.id;
        }
        if (monster.name != null) {
            return monster.name;
        }
        return monster.getClass().getName();
    }
}

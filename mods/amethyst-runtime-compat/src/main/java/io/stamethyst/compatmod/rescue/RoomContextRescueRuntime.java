package io.stamethyst.compatmod.rescue;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

final class RoomContextRescueRuntime {
    private RoomContextRescueRuntime() {
    }

    static boolean hasCurrentRoom() {
        return getCurrentRoomOrNull() != null;
    }

    static AbstractRoom getCurrentRoomOrNull() {
        try {
            MapRoomNode node = AbstractDungeon.getCurrMapNode();
            if (node == null) {
                return null;
            }
            return node.getRoom();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static void showProceedButtonIfPossible() {
        try {
            if (AbstractDungeon.overlayMenu != null
                && AbstractDungeon.overlayMenu.proceedButton != null) {
                AbstractDungeon.overlayMenu.proceedButton.show();
            }
        } catch (RuntimeException ignored) {
        }
    }

    static boolean isLikelyNullContextFailure(RuntimeException exception) {
        if (exception instanceof NullPointerException) {
            return true;
        }
        if (exception instanceof IllegalArgumentException) {
            String message = exception.getMessage();
            return message != null
                && (message.contains("n must be positive")
                || message.contains("bound must be positive"));
        }
        return false;
    }

    static String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "<null>";
        }
        String message = throwable.getMessage();
        if (message == null || message.length() == 0) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }
}

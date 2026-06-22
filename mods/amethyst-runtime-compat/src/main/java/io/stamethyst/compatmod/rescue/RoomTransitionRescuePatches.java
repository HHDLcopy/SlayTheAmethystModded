package io.stamethyst.compatmod.rescue;

import io.stamethyst.compatmod.core.CompatRuntimeState;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.saveAndContinue.SaveFile;

import java.util.ArrayList;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class RoomTransitionRescuePatches {
    private RoomTransitionRescuePatches() {
    }

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "nextRoomTransition",
        paramtypez = {SaveFile.class}
    )
    public static class AbstractDungeonNextRoomTransitionPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!AbstractDungeon.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"getCurrRoom".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ "
                            + "if ("
                            + RoomTransitionRescuePatches.class.getName()
                            + ".shouldUseMissingCurrentRoomFallback()) { "
                            + "$_ = "
                            + RoomTransitionRescuePatches.class.getName()
                            + ".handleMissingGetCurrRoomForTransition(); "
                            + "} else { "
                            + "$_ = $proceed($$); "
                            + "if ($_ == null) { "
                            + "$_ = "
                            + RoomTransitionRescuePatches.class.getName()
                            + ".handleNullGetCurrRoomForTransitionResult(); "
                            + "} "
                            + "} "
                            + "}"
                    );
                }
            };
        }
    }

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "setCurrMapNode"
    )
    public static class AbstractDungeonSetCurrMapNodePatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(MapRoomNode currMapNode) {
            if (!CompatRuntimeState.isRoomTransitionRescueEnabled()) {
                return SpireReturn.Continue();
            }
            if (currMapNode == null || hasPreviousCurrentRoom()) {
                return SpireReturn.Continue();
            }

            MapRoomNode resolvedNode = resolveRequestedNodeWithRoom(currMapNode);
            AbstractDungeon.currMapNode = resolvedNode;
            if (AbstractDungeon.nextRoom != null
                && resolvedNode != null
                && resolvedNode.room != null) {
                AbstractDungeon.nextRoom.room = resolvedNode.room;
            }

            RoomStateRescueNoticeBridge.notifyRescue(
                "room_transition_curr_map_node",
                "Set current map node during room transition without transferring old souls because the previous current room was unavailable"
            );
            return SpireReturn.Return();
        }
    }

    public static boolean shouldUseMissingCurrentRoomFallback() {
        return CompatRuntimeState.isRoomTransitionRescueEnabled()
            && RoomContextRescueRuntime.getCurrentRoomOrNull() == null;
    }

    public static AbstractRoom handleMissingGetCurrRoomForTransition() {
        notifyMissingCurrentRoom();
        return null;
    }

    public static AbstractRoom handleNullGetCurrRoomForTransitionResult() {
        if (!CompatRuntimeState.isRoomTransitionRescueEnabled()) {
            return null;
        }
        notifyMissingCurrentRoom();
        return null;
    }

    private static void notifyMissingCurrentRoom() {
        RoomStateRescueNoticeBridge.notifyRescue(
            "room_transition_get_curr_room",
            "AbstractDungeon.nextRoomTransition continued after the current room was unavailable"
        );
    }

    private static boolean hasPreviousCurrentRoom() {
        try {
            return AbstractDungeon.currMapNode != null
                && AbstractDungeon.currMapNode.room != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static MapRoomNode resolveRequestedNodeWithRoom(MapRoomNode requestedNode) {
        if (requestedNode == null || requestedNode.room != null) {
            return requestedNode;
        }
        MapRoomNode fallbackNode = findFallbackNodeOnRequestedRow(requestedNode.y);
        if (fallbackNode == null) {
            return requestedNode;
        }
        return fallbackNode;
    }

    private static MapRoomNode findFallbackNodeOnRequestedRow(int rowIndex) {
        try {
            if (AbstractDungeon.map == null
                || rowIndex < 0
                || rowIndex >= AbstractDungeon.map.size()) {
                return null;
            }
            ArrayList<MapRoomNode> row = AbstractDungeon.map.get(rowIndex);
            if (row == null) {
                return null;
            }
            int maxColumns = Math.min(5, row.size());
            for (int i = 0; i < maxColumns; ++i) {
                MapRoomNode candidate = row.get(i);
                if (candidate != null && candidate.room != null) {
                    return candidate;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }
}

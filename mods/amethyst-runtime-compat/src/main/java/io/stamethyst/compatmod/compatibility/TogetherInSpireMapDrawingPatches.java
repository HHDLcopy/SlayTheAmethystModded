package io.stamethyst.compatmod.compatibility;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.screens.DungeonMapScreen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class TogetherInSpireMapDrawingPatches {
    private static final String MAP_PAINTER_CLASS = "spireTogether.map.MapPainter";
    private static final String MAP_PAINTER_UPDATER_CLASS =
        "spireTogether.map.MapPainter$MapPainterUpdater";
    private static final String MAP_PAINTER_RENDER_CLASS =
        "spireTogether.map.MapPainter$MapPainterRender";
    private static final String MAP_NODE_CLICK_PATCH_CLASS =
        "spireTogether.patches.ui.MapNodePatches$ClickNode";
    private static final String ROOM_DATA_MANAGER_CLASS = "spireTogether.other.RoomDataManager";
    private static final String P2P_MANAGER_CLASS = "spireTogether.network.P2P.P2PManager";
    private static final String NETWORK_LOCATION_CLASS =
        "spireTogether.network.objects.rooms.NetworkLocation";
    private static final String P2P_MESSAGE_SENDER_CLASS =
        "spireTogether.network.P2P.P2PMessageSender";

    private static final String ICON_ROOT =
        "amethyst-runtime-compat/images/together-in-spire/";
    private static final float BUTTON_SIZE = 64.0f;
    private static final float BUTTON_GAP = 12.0f;
    private static final float BUTTON_LEFT = 42.0f;
    private static final float BUTTON_BOTTOM = 48.0f;
    private static final float ERASER_RADIUS = 40.0f;
    private static final float MAP_DOT_HALF_SIZE = 9.0f;

    private static final Color BUTTON_IDLE = new Color(0.10f, 0.12f, 0.15f, 0.92f);
    private static final Color BUTTON_ACTIVE = new Color(0.64f, 0.43f, 0.16f, 0.98f);
    private static final Color BUTTON_BORDER = new Color(0.78f, 0.73f, 0.62f, 0.95f);

    private static final Hitbox penHitbox = new Hitbox(1.0f, 1.0f);
    private static final Hitbox eraserHitbox = new Hitbox(1.0f, 1.0f);
    private static final Hitbox exitHitbox = new Hitbox(1.0f, 1.0f);

    private static Texture penTexture;
    private static Texture eraserTexture;
    private static Texture exitTexture;
    private static ToolMode toolMode = ToolMode.NONE;
    private static float lastDrawX = -1.0f;
    private static float lastDrawY = -1.0f;
    private static boolean eraserDirty;
    private static boolean restoreInput;
    private static boolean savedMouseDown;
    private static boolean savedJustClicked;
    private static boolean savedJustReleased;
    private static boolean toolbarPressThisFrame;

    private static volatile Method addDotMethod;
    private static volatile Method mapNodeClickPrefixMethod;
    private static volatile Method getFloorMethod;
    private static volatile Method getRoomByLocationMethod;
    private static volatile Method clearOwnMarksMethod;
    private static volatile Method addStoredDotMethod;
    private static volatile Method setRoomMarkMethod;
    private static volatile Method removeRoomMarkMethod;
    private static volatile Method sendChangedNodeMarkMethod;
    private static volatile Constructor<?> networkLocationNodeConstructor;
    private static volatile Field paintField;
    private static volatile Field selfIdField;
    private static volatile Field dotXField;
    private static volatile Field dotYField;
    private static volatile Field dotYOffsetField;
    private static volatile Field dotPlayerIdField;
    private static volatile Field roomMarkField;

    private TogetherInSpireMapDrawingPatches() {
    }

    static boolean isToolActive() {
        return toolMode != ToolMode.NONE
            && TogetherInSpireCompatRuntime.isConnected();
    }

    @SpirePatch2(
        cls = MAP_PAINTER_UPDATER_CLASS,
        method = "Postfix",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class DisableDesktopPainterUpdatePatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            if (TogetherInSpireCompatRuntime.isHybridInputMode()) {
                return SpireReturn.Return();
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch2(
        cls = MAP_PAINTER_RENDER_CLASS,
        method = "Postfix",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class DisableDesktopClearButtonPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            if (TogetherInSpireCompatRuntime.isHybridInputMode()) {
                return SpireReturn.Return();
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch2(
        clz = DungeonMapScreen.class,
        method = "update",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class DungeonMapUpdatePatch {
        @SpirePrefixPatch
        public static void Prefix(DungeonMapScreen __instance) {
            toolbarPressThisFrame = false;
            if (!isToolbarActive()) {
                resetTools();
                return;
            }

            layoutHitboxes();
            updateHoverState();
            boolean overToolbar = isPointerOverToolbar();
            if (InputHelper.justClickedLeft && overToolbar) {
                toolbarPressThisFrame = true;
                if (penHitbox.hovered) {
                    finishPendingErasure();
                    toolMode = ToolMode.PEN;
                    resetDrawSegment();
                } else if (eraserHitbox.hovered) {
                    toolMode = ToolMode.ERASER;
                    resetDrawSegment();
                } else if (exitHitbox.hovered) {
                    resetTools();
                }
            }

            if (toolMode == ToolMode.PEN && InputHelper.isMouseDown && !overToolbar) {
                appendRoutePoint();
            } else if (toolMode == ToolMode.ERASER
                && InputHelper.isMouseDown
                && !overToolbar) {
                eraseTouchedMarks();
            } else if (!InputHelper.isMouseDown) {
                resetDrawSegment();
                finishPendingErasure();
            }

            if (overToolbar || toolMode != ToolMode.NONE) {
                suppressMapInput(__instance);
            }
        }

        @SpirePostfixPatch
        public static void Postfix(DungeonMapScreen __instance) {
            if (toolbarPressThisFrame) {
                __instance.clicked = false;
                __instance.clickTimer = 0.0f;
            }
            restoreMapInput();
        }
    }

    @SpirePatch2(
        clz = DungeonMapScreen.class,
        method = "render",
        paramtypez = {SpriteBatch.class},
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class DungeonMapRenderPatch {
        @SpirePostfixPatch
        public static void Postfix(SpriteBatch sb) {
            if (!isToolbarActive()) {
                return;
            }
            layoutHitboxes();
            updateHoverState();
            loadTextures();
            renderButton(sb, penHitbox, penTexture, toolMode == ToolMode.PEN);
            renderButton(sb, eraserHitbox, eraserTexture, toolMode == ToolMode.ERASER);
            renderButton(sb, exitHitbox, exitTexture, false);
        }
    }

    @SpirePatch2(
        clz = MapRoomNode.class,
        method = "update",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class MapRoomNodePenHighlightPatch {
        @SpirePostfixPatch
        public static void Postfix(MapRoomNode __instance) {
            if (!isPenStrokeActive()
                || !TogetherInSpireCompatRuntime.isConnected()
                || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.MAP
                || __instance.hb == null
                || !containsPointer(__instance.hb)) {
                return;
            }
            __instance.hb.hovered = true;
            __instance.highlighted = true;
            __instance.color = MapRoomNode.AVAILABLE_COLOR.cpy();
            markNodeWithOriginalPainter(__instance);
        }
    }

    @SpirePatch2(
        clz = DungeonMapScreen.class,
        method = "close",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class DungeonMapClosePatch {
        @SpirePostfixPatch
        public static void Postfix() {
            resetTools();
            restoreMapInput();
        }
    }

    @SpirePatch2(
        clz = DungeonMapScreen.class,
        method = "closeInstantly",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class DungeonMapCloseInstantlyPatch {
        @SpirePostfixPatch
        public static void Postfix() {
            resetTools();
            restoreMapInput();
        }
    }

    private static boolean isToolbarActive() {
        return TogetherInSpireCompatRuntime.isConnected()
            && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP;
    }

    private static void layoutHitboxes() {
        float size = BUTTON_SIZE * Settings.scale;
        float gap = BUTTON_GAP * Settings.scale;
        float centerY = BUTTON_BOTTOM * Settings.scale + size / 2.0f;
        float firstCenterX = BUTTON_LEFT * Settings.scale + size / 2.0f;
        layoutHitbox(penHitbox, firstCenterX, centerY, size);
        layoutHitbox(eraserHitbox, firstCenterX + size + gap, centerY, size);
        layoutHitbox(exitHitbox, firstCenterX + (size + gap) * 2.0f, centerY, size);
    }

    private static void layoutHitbox(Hitbox hitbox, float centerX, float centerY, float size) {
        hitbox.resize(size, size);
        hitbox.move(centerX, centerY);
    }

    private static void updateHoverState() {
        updateHoverState(penHitbox);
        updateHoverState(eraserHitbox);
        updateHoverState(exitHitbox);
    }

    private static void updateHoverState(Hitbox hitbox) {
        hitbox.hovered = containsPointer(hitbox);
    }

    private static boolean containsPointer(Hitbox hitbox) {
        return InputHelper.mX >= hitbox.x
            && InputHelper.mX <= hitbox.x + hitbox.width
            && InputHelper.mY >= hitbox.y
            && InputHelper.mY <= hitbox.y + hitbox.height;
    }

    private static boolean isPenStrokeActive() {
        boolean pointerDown = restoreInput ? savedMouseDown : InputHelper.isMouseDown;
        return toolMode == ToolMode.PEN && pointerDown && !isPointerOverToolbar();
    }

    private static boolean isPointerOverToolbar() {
        return penHitbox.hovered || eraserHitbox.hovered || exitHitbox.hovered;
    }

    private static void suppressMapInput(DungeonMapScreen screen) {
        if (!restoreInput) {
            savedMouseDown = InputHelper.isMouseDown;
            savedJustClicked = InputHelper.justClickedLeft;
            savedJustReleased = InputHelper.justReleasedClickLeft;
            restoreInput = true;
        }
        InputHelper.isMouseDown = false;
        InputHelper.justClickedLeft = false;
        InputHelper.justReleasedClickLeft = false;
        screen.clicked = false;
        screen.clickTimer = 0.0f;
    }

    private static void restoreMapInput() {
        if (!restoreInput) {
            return;
        }
        InputHelper.isMouseDown = savedMouseDown;
        InputHelper.justClickedLeft = savedJustClicked;
        InputHelper.justReleasedClickLeft = savedJustReleased;
        restoreInput = false;
    }

    private static void appendRoutePoint() {
        try {
            Method method = addDotMethod;
            if (method == null) {
                Class<?> mapPainter = Class.forName(
                    MAP_PAINTER_CLASS,
                    false,
                    TogetherInSpireMapDrawingPatches.class.getClassLoader()
                );
                method = mapPainter.getMethod("addDot", float.class, float.class);
                addDotMethod = method;
            }
            method.invoke(null, lastDrawX, lastDrawY);
            lastDrawX = InputHelper.mX / Settings.xScale - 9.0f;
            lastDrawY = InputHelper.mY / Settings.yScale - 9.0f;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            resetDrawSegment();
        }
    }

    private static void eraseTouchedMarks() {
        try {
            Object floor = getFloor();
            if (floor == null) {
                return;
            }

            List<?> paint = getPaint(floor);
            Integer selfId = getSelfId();
            if (paint == null || selfId == null) {
                return;
            }

            float radius = ERASER_RADIUS * Settings.scale;
            float radiusSquared = radius * radius;
            boolean dotRemoved = false;
            synchronized (paint) {
                Iterator<?> iterator = paint.iterator();
                while (iterator.hasNext()) {
                    Object dot = iterator.next();
                    if (!selfId.equals(readDotPlayerId(dot))) {
                        continue;
                    }
                    float centerX = readFloat(dot, getDotXField(dot)) * Settings.xScale
                        + MAP_DOT_HALF_SIZE * Settings.xScale;
                    float centerY = resolveRenderedDotCenterY(
                        readFloat(dot, getDotYField(dot)),
                        readFloat(dot, getDotYOffsetField(dot)),
                        DungeonMapScreen.offsetY,
                        Settings.yScale
                    );
                    float deltaX = InputHelper.mX - centerX;
                    float deltaY = InputHelper.mY - centerY;
                    if (deltaX * deltaX + deltaY * deltaY <= radiusSquared) {
                        iterator.remove();
                        dotRemoved = true;
                    }
                }
            }
            eraseTouchedNodeMarks(selfId, radiusSquared);
            eraserDirty |= dotRemoved;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
    }

    private static void eraseTouchedNodeMarks(Integer selfId, float radiusSquared)
        throws ReflectiveOperationException {
        if (AbstractDungeon.map == null) {
            return;
        }
        for (List<MapRoomNode> row : AbstractDungeon.map) {
            if (row == null) {
                continue;
            }
            for (MapRoomNode node : row) {
                if (node == null
                    || node.hb == null
                    || !circleIntersectsHitbox(
                        InputHelper.mX,
                        InputHelper.mY,
                        radiusSquared,
                        node.hb
                    )) {
                    continue;
                }
                Object location = createNetworkLocation(node);
                Object room = getRoomByLocation(location);
                if (room == null || !selfId.equals(readRoomMark(room))) {
                    continue;
                }
                Method removeMark = removeRoomMarkMethod;
                if (removeMark == null || removeMark.getDeclaringClass() != room.getClass()) {
                    removeMark = room.getClass().getMethod("removeMark");
                    removeRoomMarkMethod = removeMark;
                }
                removeMark.invoke(room);
                sendNodeMarkChange(location, false);
            }
        }
    }

    private static boolean circleIntersectsHitbox(
        float pointerX,
        float pointerY,
        float radiusSquared,
        Hitbox hitbox
    ) {
        return circleIntersectsRectangle(
            pointerX,
            pointerY,
            (float) Math.sqrt(radiusSquared),
            hitbox.x,
            hitbox.y,
            hitbox.width,
            hitbox.height
        );
    }

    static boolean circleIntersectsRectangle(
        float pointerX,
        float pointerY,
        float radius,
        float left,
        float bottom,
        float width,
        float height
    ) {
        float closestX = Math.max(left, Math.min(pointerX, left + width));
        float closestY = Math.max(bottom, Math.min(pointerY, bottom + height));
        float deltaX = pointerX - closestX;
        float deltaY = pointerY - closestY;
        return deltaX * deltaX + deltaY * deltaY <= radius * radius;
    }

    private static void finishPendingErasure() {
        if (!eraserDirty) {
            return;
        }
        try {
            Object floor = getFloor();
            if (floor == null) {
                return;
            }
            List<?> paint = getPaint(floor);
            Integer selfId = getSelfId();
            if (paint == null || selfId == null) {
                return;
            }

            List<Object> preserved = new ArrayList<>();
            List<PreservedNodeMark> preservedNodeMarks = snapshotOwnNodeMarks(selfId);
            synchronized (paint) {
                for (Object dot : paint) {
                    if (selfId.equals(readDotPlayerId(dot))) {
                        preserved.add(dot);
                    }
                }

                Method clearMethod = clearOwnMarksMethod;
                if (clearMethod == null || clearMethod.getDeclaringClass() != floor.getClass()) {
                    clearMethod = floor.getClass().getMethod("clearOwnMarks");
                    clearOwnMarksMethod = clearMethod;
                }
                clearMethod.invoke(floor);

                Method addMethod = addStoredDotMethod;
                if (addMethod == null || addMethod.getDeclaringClass() != floor.getClass()) {
                    Class<?> dotClass = Class.forName(
                        "spireTogether.map.MapDot",
                        false,
                        TogetherInSpireMapDrawingPatches.class.getClassLoader()
                    );
                    addMethod = floor.getClass().getMethod("addDot", dotClass, boolean.class);
                    addStoredDotMethod = addMethod;
                }
                for (Object dot : preserved) {
                    addMethod.invoke(floor, dot, true);
                }
                restoreNodeMarks(preservedNodeMarks, selfId);
            }
            eraserDirty = false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
    }

    private static void markNodeWithOriginalPainter(MapRoomNode node) {
        boolean savedRightDown = InputHelper.isMouseDown_R;
        boolean savedRightClick = InputHelper.justClickedRight;
        try {
            Method method = mapNodeClickPrefixMethod;
            if (method == null) {
                Class<?> clickPatch = Class.forName(
                    MAP_NODE_CLICK_PATCH_CLASS,
                    false,
                    TogetherInSpireMapDrawingPatches.class.getClassLoader()
                );
                method = clickPatch.getMethod("Prefix", MapRoomNode.class);
                mapNodeClickPrefixMethod = method;
            }
            InputHelper.isMouseDown_R = true;
            InputHelper.justClickedRight = false;
            method.invoke(null, node);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        } finally {
            InputHelper.isMouseDown_R = savedRightDown;
            InputHelper.justClickedRight = savedRightClick;
        }
    }

    private static List<PreservedNodeMark> snapshotOwnNodeMarks(Integer selfId)
        throws ReflectiveOperationException {
        List<PreservedNodeMark> marks = new ArrayList<>();
        if (AbstractDungeon.map == null) {
            return marks;
        }
        for (List<MapRoomNode> row : AbstractDungeon.map) {
            if (row == null) {
                continue;
            }
            for (MapRoomNode node : row) {
                if (node == null) {
                    continue;
                }
                Object location = createNetworkLocation(node);
                Object room = getRoomByLocation(location);
                if (room != null && selfId.equals(readRoomMark(room))) {
                    marks.add(new PreservedNodeMark(location, room));
                }
            }
        }
        return marks;
    }

    private static void restoreNodeMarks(List<PreservedNodeMark> marks, Integer selfId)
        throws ReflectiveOperationException {
        for (PreservedNodeMark mark : marks) {
            Method setMark = setRoomMarkMethod;
            if (setMark == null || setMark.getDeclaringClass() != mark.room.getClass()) {
                setMark = mark.room.getClass().getMethod("setMark", Integer.class);
                setRoomMarkMethod = setMark;
            }
            setMark.invoke(mark.room, selfId);
        }
        for (PreservedNodeMark mark : marks) {
            sendNodeMarkChange(mark.location, true);
        }
    }

    private static void sendNodeMarkChange(Object location, boolean marked)
        throws ReflectiveOperationException {
        Method sendMark = sendChangedNodeMarkMethod;
        if (sendMark == null) {
            Class<?> sender = Class.forName(
                P2P_MESSAGE_SENDER_CLASS,
                false,
                TogetherInSpireMapDrawingPatches.class.getClassLoader()
            );
            sendMark = sender.getMethod(
                "Send_ChangedNodeMark",
                location.getClass(),
                boolean.class
            );
            sendChangedNodeMarkMethod = sendMark;
        }
        sendMark.invoke(null, location, marked);
    }

    private static Object createNetworkLocation(MapRoomNode node)
        throws ReflectiveOperationException {
        Constructor<?> constructor = networkLocationNodeConstructor;
        if (constructor == null) {
            Class<?> locationClass = Class.forName(
                NETWORK_LOCATION_CLASS,
                false,
                TogetherInSpireMapDrawingPatches.class.getClassLoader()
            );
            constructor = locationClass.getConstructor(MapRoomNode.class);
            networkLocationNodeConstructor = constructor;
        }
        return constructor.newInstance(node);
    }

    private static Object getRoomByLocation(Object location) throws ReflectiveOperationException {
        Method method = getRoomByLocationMethod;
        if (method == null) {
            Class<?> manager = Class.forName(
                ROOM_DATA_MANAGER_CLASS,
                false,
                TogetherInSpireMapDrawingPatches.class.getClassLoader()
            );
            method = manager.getMethod("getRoom", location.getClass());
            getRoomByLocationMethod = method;
        }
        return method.invoke(null, location);
    }

    private static Integer readRoomMark(Object room) throws ReflectiveOperationException {
        Field field = roomMarkField;
        if (field == null || field.getDeclaringClass() != room.getClass()) {
            field = room.getClass().getDeclaredField("roomMark");
            field.setAccessible(true);
            roomMarkField = field;
        }
        Object value = field.get(room);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static Object getFloor() throws ReflectiveOperationException {
        Method method = getFloorMethod;
        if (method == null) {
            Class<?> manager = Class.forName(
                ROOM_DATA_MANAGER_CLASS,
                false,
                TogetherInSpireMapDrawingPatches.class.getClassLoader()
            );
            method = manager.getMethod("getFloor");
            getFloorMethod = method;
        }
        return method.invoke(null);
    }

    private static List<?> getPaint(Object floor) throws ReflectiveOperationException {
        Field field = paintField;
        if (field == null || field.getDeclaringClass() != floor.getClass()) {
            field = floor.getClass().getField("paint");
            paintField = field;
        }
        Object value = field.get(floor);
        return value instanceof List<?> ? (List<?>) value : null;
    }

    private static Integer getSelfId() throws ReflectiveOperationException {
        Field field = selfIdField;
        if (field == null) {
            Class<?> manager = Class.forName(
                P2P_MANAGER_CLASS,
                false,
                TogetherInSpireMapDrawingPatches.class.getClassLoader()
            );
            field = manager.getField("selfID");
            selfIdField = field;
        }
        Object value = field.get(null);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static Integer readDotPlayerId(Object dot) throws ReflectiveOperationException {
        Field field = dotPlayerIdField;
        if (field == null || field.getDeclaringClass() != dot.getClass()) {
            field = dot.getClass().getField("playerID");
            dotPlayerIdField = field;
        }
        Object value = field.get(dot);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static Field getDotXField(Object dot) throws ReflectiveOperationException {
        if (dotXField == null || dotXField.getDeclaringClass() != dot.getClass()) {
            dotXField = dot.getClass().getField("x");
        }
        return dotXField;
    }

    private static Field getDotYField(Object dot) throws ReflectiveOperationException {
        if (dotYField == null || dotYField.getDeclaringClass() != dot.getClass()) {
            dotYField = dot.getClass().getField("y");
        }
        return dotYField;
    }

    private static Field getDotYOffsetField(Object dot) throws ReflectiveOperationException {
        if (dotYOffsetField == null || dotYOffsetField.getDeclaringClass() != dot.getClass()) {
            dotYOffsetField = dot.getClass().getField("yOffset");
        }
        return dotYOffsetField;
    }

    private static float readFloat(Object target, Field field) throws IllegalAccessException {
        Object value = field.get(target);
        return value instanceof Number ? ((Number) value).floatValue() : 0.0f;
    }

    static float resolveRenderedDotCenterY(
        float dotY,
        float dotYOffset,
        float mapOffsetY,
        float yScale
    ) {
        // Match MapDot.render: y - (storedOffset - currentOffset), then center the 18px dot.
        return (dotY - (dotYOffset - mapOffsetY / yScale)) * yScale
            + MAP_DOT_HALF_SIZE * yScale;
    }

    private static void loadTextures() {
        if (penTexture == null) {
            penTexture = ImageMaster.loadImage(ICON_ROOT + "feather.png");
        }
        if (eraserTexture == null) {
            eraserTexture = ImageMaster.loadImage(ICON_ROOT + "eraser.png");
        }
        if (exitTexture == null) {
            exitTexture = ImageMaster.loadImage(ICON_ROOT + "log-out.png");
        }
    }

    private static void renderButton(
        SpriteBatch sb,
        Hitbox hitbox,
        Texture icon,
        boolean active
    ) {
        Color previous = sb.getColor().cpy();
        sb.setColor(BUTTON_BORDER);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, hitbox.x, hitbox.y, hitbox.width, hitbox.height);
        float inset = 2.0f * Settings.scale;
        sb.setColor(active ? BUTTON_ACTIVE : BUTTON_IDLE);
        sb.draw(
            ImageMaster.WHITE_SQUARE_IMG,
            hitbox.x + inset,
            hitbox.y + inset,
            hitbox.width - inset * 2.0f,
            hitbox.height - inset * 2.0f
        );
        if (icon != null) {
            float iconInset = 14.0f * Settings.scale;
            sb.setColor(Color.WHITE);
            sb.draw(
                icon,
                hitbox.x + iconInset,
                hitbox.y + iconInset,
                hitbox.width - iconInset * 2.0f,
                hitbox.height - iconInset * 2.0f
            );
        }
        sb.setColor(previous);
    }

    private static void resetTools() {
        finishPendingErasure();
        toolMode = ToolMode.NONE;
        resetDrawSegment();
    }

    private static void resetDrawSegment() {
        lastDrawX = -1.0f;
        lastDrawY = -1.0f;
    }

    private enum ToolMode {
        NONE,
        PEN,
        ERASER
    }

    private static final class PreservedNodeMark {
        final Object location;
        final Object room;

        PreservedNodeMark(Object location, Object room) {
            this.location = location;
            this.room = room;
        }
    }
}

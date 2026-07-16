package io.stamethyst.compatmod.compatibility;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.screens.DungeonMapScreen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class TogetherInSpireRouteLockPatches {
    private static final String P2P_MANAGER_CLASS = "spireTogether.network.P2P.P2PManager";
    private static final String GAMEPLAY_HELP_CLASS = "spireTogether.util.SpireHelp$Gameplay";
    private static final String ROOM_ENTRY_PATCH_CLASS =
        "spireTogether.patches.network.RoomEntryPatch";
    private static final String ROUTE_EXTRA_DATA_KEY = "amethyst.route_lock.global.v2";
    private static final long ROUTE_REFRESH_INTERVAL_MS = 100L;
    private static final long NOTICE_DURATION_MS = 2200L;

    private static final Color NOTICE_COLOR = new Color(1.0f, 0.80f, 0.34f, 1.0f);

    private static volatile Method getAllPlayersMethod;
    private static volatile Method getSelfMethod;
    private static volatile Method getCurrentActMethod;
    private static volatile Method getExtraDataMethod;
    private static volatile Method updateExtraDataMethod;
    private static volatile Field infiniteCounterField;
    private static volatile Field notTakenColorField;
    private static volatile Field nodeScaleField;
    private static long noticeUntilMs;
    private static String noticeText = "";
    private static boolean reflectionFailureLogged;
    private static RecordedRoute globalRoute;
    private static long nextRouteRefreshMs;
    private static boolean refreshingRoute;

    private TogetherInSpireRouteLockPatches() {
    }

    @SpirePatch2(
        clz = MapRoomNode.class,
        method = "update",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class MapRoomNodeUpdatePatch {
        @SpirePrefixPatch
        public static void Prefix(MapRoomNode __instance) {
            DungeonMapScreen screen = AbstractDungeon.dungeonMapScreen;
            if (!TogetherInSpireCompatRuntime.isRouteLockEnabledAndConnected()
                || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.MAP
                || screen == null
                || !screen.clicked
                || __instance.hb == null
                || !__instance.hb.hovered) {
                return;
            }

            if (TogetherInSpireMapDrawingPatches.isToolActive()) {
                blockSelection(screen, BlockReason.DRAWING_ACTIVE);
                return;
            }

            BlockReason reason = findBlockReason(__instance);
            if (reason != BlockReason.NONE) {
                blockSelection(screen, reason);
            }
        }

        @SpirePostfixPatch
        public static void Postfix(MapRoomNode __instance) {
            if (!isUnselectedBranch(__instance)) {
                return;
            }
            __instance.highlighted = false;
            __instance.color = unavailableNodeColor();
            stabilizeUnavailableNodeScale(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "nextRoomTransitionStart",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class RecordChosenRoutePatch {
        @SpirePrefixPatch
        public static void Prefix() {
            recordSelfRoute();
        }
    }

    @SpirePatch2(
        clz = DungeonMapScreen.class,
        method = "render",
        paramtypez = {SpriteBatch.class},
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class DungeonMapNoticeRenderPatch {
        @SpirePostfixPatch
        public static void Postfix(SpriteBatch sb) {
            if (System.currentTimeMillis() >= noticeUntilMs || noticeText.isEmpty()) {
                return;
            }
            FontHelper.renderFontCentered(
                sb,
                FontHelper.tipBodyFont,
                noticeText,
                Settings.WIDTH / 2.0f,
                Settings.HEIGHT * 0.84f,
                NOTICE_COLOR
            );
        }
    }

    private static BlockReason findBlockReason(MapRoomNode candidate) {
        try {
            RecordedRoute route = refreshGlobalRoute(false);
            Integer selectedX = route == null ? null : route.nodeXAt(candidate.y);
            return !isGlobalBranchLocked(candidate.x, selectedX)
                ? BlockReason.NONE
                : BlockReason.FOLLOW_ROUTE;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            logReflectionFailure(error);
            return BlockReason.NONE;
        }
    }

    static boolean matchesGlobalRoute(int candidateX, Integer expectedX) {
        return expectedX != null && candidateX == expectedX;
    }

    static boolean isGlobalBranchLocked(int candidateX, Integer selectedX) {
        return selectedX != null && !matchesGlobalRoute(candidateX, selectedX);
    }

    private static boolean isUnselectedBranch(MapRoomNode node) {
        if (!TogetherInSpireCompatRuntime.isRouteLockEnabledAndConnected()
            || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.MAP) {
            return false;
        }
        try {
            RecordedRoute route = refreshGlobalRoute(false);
            Integer selectedX = route == null ? null : route.nodeXAt(node.y);
            return isGlobalBranchLocked(node.x, selectedX);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            logReflectionFailure(error);
            return false;
        }
    }

    private static Color unavailableNodeColor() {
        try {
            Field field = notTakenColorField;
            if (field == null) {
                field = MapRoomNode.class.getDeclaredField("NOT_TAKEN_COLOR");
                field.setAccessible(true);
                notTakenColorField = field;
            }
            Object value = field.get(null);
            if (value instanceof Color) {
                return ((Color) value).cpy();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return Color.WHITE.cpy();
    }

    private static void stabilizeUnavailableNodeScale(MapRoomNode node) {
        try {
            Field field = nodeScaleField;
            if (field == null) {
                field = MapRoomNode.class.getDeclaredField("scale");
                field.setAccessible(true);
                nodeScaleField = field;
            }
            field.setFloat(node, 0.5f);
        } catch (ReflectiveOperationException | RuntimeException error) {
            logReflectionFailure(error);
        }
    }

    private static RecordedRoute refreshGlobalRoute(boolean force)
        throws ReflectiveOperationException {
        if (!TogetherInSpireCompatRuntime.isRouteLockEnabledAndConnected()) {
            globalRoute = null;
            return null;
        }
        ClassLoader loader = TogetherInSpireRouteLockPatches.class.getClassLoader();
        String currentAct = resolveCurrentAct(loader);
        int currentInfinity = resolveCurrentInfinity(loader);
        long now = System.currentTimeMillis();
        if (!force
            && globalRoute != null
            && globalRoute.matches(currentAct, currentInfinity)
            && now < nextRouteRefreshMs) {
            return globalRoute;
        }
        if (refreshingRoute) {
            return globalRoute;
        }

        refreshingRoute = true;
        try {
            List<PlayerProgress> players =
                snapshotPlayers(loader, currentAct, currentInfinity);
            RecordedRoute resolved = chooseGlobalRoute(players);
            globalRoute = resolved == null
                ? new RecordedRoute(currentAct, currentInfinity)
                : resolved.copy();
            mirrorGlobalRouteToSelf(players, globalRoute);
            nextRouteRefreshMs = now + ROUTE_REFRESH_INTERVAL_MS;
            return globalRoute;
        } finally {
            refreshingRoute = false;
        }
    }

    private static List<PlayerProgress> snapshotPlayers(
        ClassLoader loader,
        String currentAct,
        int currentInfinity
    ) throws ReflectiveOperationException {
        Class<?> managerClass = Class.forName(P2P_MANAGER_CLASS, false, loader);
        Method allPlayers = getAllPlayersMethod;
        if (allPlayers == null) {
            allPlayers = managerClass.getMethod("GetAllPlayersAsList");
            getAllPlayersMethod = allPlayers;
        }
        Method selfMethod = getSelfMethod;
        if (selfMethod == null) {
            selfMethod = managerClass.getMethod("GetSelf");
            getSelfMethod = selfMethod;
        }

        Object selfObject = selfMethod.invoke(null);
        Object result = allPlayers.invoke(null);
        if (!(result instanceof List<?>)) {
            return new ArrayList<>();
        }

        MapRoomNode currentNode = AbstractDungeon.getCurrMapNode();
        List<PlayerProgress> snapshot = new ArrayList<>();
        for (Object player : (List<?>) result) {
            if (player == null || !isEmbarkedAndAlive(player)) {
                continue;
            }
            boolean self = player == selfObject;
            int id = readInteger(player, "id", Integer.MAX_VALUE);
            Object location = readField(player, "location");
            int y = -1;
            String act = currentAct;
            int infinity = currentInfinity;
            if (location != null) {
                y = readInteger(location, "y", -1);
                act = readString(location, "act", currentAct);
                infinity = readInteger(location, "infinityCounter", currentInfinity);
            }
            if (self && currentNode != null) {
                y = currentNode.y;
            }
            if (!Objects.equals(currentAct, act) || currentInfinity != infinity) {
                continue;
            }
            RecordedRoute route = readRecordedRoute(player, currentAct, currentInfinity);
            snapshot.add(new PlayerProgress(player, id, y, self, route));
        }
        return snapshot;
    }

    private static RecordedRoute chooseGlobalRoute(List<PlayerProgress> players) {
        PlayerProgress authority = null;
        for (PlayerProgress player : players) {
            if (player.route == null || player.route.maxY() == Integer.MIN_VALUE) {
                continue;
            }
            if (authority == null
                || player.y > authority.y
                || (player.y == authority.y
                && player.route.maxY() > authority.route.maxY())
                || (player.y == authority.y
                && player.route.maxY() == authority.route.maxY()
                && player.id < authority.id)) {
                authority = player;
            }
        }
        return authority == null ? null : authority.route;
    }

    private static void mirrorGlobalRouteToSelf(
        List<PlayerProgress> players,
        RecordedRoute route
    ) throws ReflectiveOperationException {
        if (route == null || route.maxY() == Integer.MIN_VALUE) {
            return;
        }
        String serialized = route.serialize();
        for (PlayerProgress player : players) {
            if (player.self
                && (player.route == null
                || !serialized.equals(player.route.serialize()))) {
                publishRecordedRoute(player.networkPlayer, route);
                return;
            }
        }
    }

    private static void recordSelfRoute() {
        if (!TogetherInSpireCompatRuntime.isRouteLockEnabledAndConnected()
            || AbstractDungeon.nextRoom == null) {
            return;
        }
        try {
            ClassLoader loader = TogetherInSpireRouteLockPatches.class.getClassLoader();
            Class<?> managerClass = Class.forName(P2P_MANAGER_CLASS, false, loader);
            Method selfMethod = getSelfMethod;
            if (selfMethod == null) {
                selfMethod = managerClass.getMethod("GetSelf");
                getSelfMethod = selfMethod;
            }
            Object self = selfMethod.invoke(null);
            if (self == null) {
                return;
            }
            String act = resolveCurrentAct(loader);
            int infinity = resolveCurrentInfinity(loader);
            RecordedRoute route = refreshGlobalRoute(true);
            if (route == null || !route.matches(act, infinity)) {
                route = new RecordedRoute(act, infinity);
            }
            if (route.nodeXAt(AbstractDungeon.nextRoom.y) != null) {
                return;
            }
            RecordedRoute updatedRoute = route.copy();
            updatedRoute.record(AbstractDungeon.nextRoom.y, AbstractDungeon.nextRoom.x);
            globalRoute = updatedRoute;
            nextRouteRefreshMs = System.currentTimeMillis() + ROUTE_REFRESH_INTERVAL_MS;
            publishRecordedRoute(self, updatedRoute);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            logReflectionFailure(error);
        }
    }

    private static RecordedRoute readRecordedRoute(Object player, String act, int infinity)
        throws ReflectiveOperationException {
        Method method = getExtraDataMethod;
        if (method == null || method.getDeclaringClass() != player.getClass()) {
            method = player.getClass().getMethod("getExtraData", String.class);
            getExtraDataMethod = method;
        }
        Object value = method.invoke(player, ROUTE_EXTRA_DATA_KEY);
        RecordedRoute route = value instanceof String ? RecordedRoute.parse((String) value) : null;
        return route != null && route.matches(act, infinity) ? route : null;
    }

    private static void publishRecordedRoute(Object player, RecordedRoute route)
        throws ReflectiveOperationException {
        Method method = updateExtraDataMethod;
        if (method == null || method.getDeclaringClass() != player.getClass()) {
            method = player.getClass().getMethod(
                "UpdateExtraData",
                String.class,
                Object.class,
                boolean.class
            );
            updateExtraDataMethod = method;
        }
        method.invoke(player, ROUTE_EXTRA_DATA_KEY, route.serialize(), true);
    }

    private static boolean isEmbarkedAndAlive(Object player) throws ReflectiveOperationException {
        Object startStatus = readField(player, "startStatus");
        if (startStatus != null && !"EMBARKED".equals(String.valueOf(startStatus))) {
            return false;
        }
        Object healthStatus = readField(player, "healthStatus");
        return healthStatus == null || "ALIVE".equals(String.valueOf(healthStatus));
    }

    private static String resolveCurrentAct(ClassLoader loader) throws ReflectiveOperationException {
        Method method = getCurrentActMethod;
        if (method == null) {
            Class<?> helper = Class.forName(GAMEPLAY_HELP_CLASS, false, loader);
            method = helper.getMethod("GetCurrActName");
            getCurrentActMethod = method;
        }
        Object value = method.invoke(null);
        return value == null ? "" : String.valueOf(value);
    }

    private static int resolveCurrentInfinity(ClassLoader loader) throws ReflectiveOperationException {
        Field field = infiniteCounterField;
        if (field == null) {
            Class<?> roomEntryPatch = Class.forName(ROOM_ENTRY_PATCH_CLASS, false, loader);
            field = roomEntryPatch.getField("infiniteCounter");
            infiniteCounterField = field;
        }
        Object value = field.get(null);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getField(name);
        return field.get(target);
    }

    private static int readInteger(Object target, String name, int fallback)
        throws ReflectiveOperationException {
        Object value = readField(target, name);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static String readString(Object target, String name, String fallback)
        throws ReflectiveOperationException {
        Object value = readField(target, name);
        return value == null ? fallback : String.valueOf(value);
    }

    private static void blockSelection(DungeonMapScreen screen, BlockReason reason) {
        screen.clicked = false;
        noticeText = reason.message();
        noticeUntilMs = System.currentTimeMillis() + NOTICE_DURATION_MS;
        if (CardCrawlGame.sound != null) {
            CardCrawlGame.sound.play("UI_CLICK_2");
        }
    }

    private static void logReflectionFailure(Throwable error) {
        if (!reflectionFailureLogged) {
            reflectionFailureLogged = true;
            System.out.println(
                "[amethyst-runtime-compat] Together in Spire route lock unavailable: "
                    + error.getClass().getSimpleName()
                    + ": "
                    + String.valueOf(error.getMessage())
            );
        }
    }

    private enum BlockReason {
        NONE,
        DRAWING_ACTIVE,
        FOLLOW_ROUTE;

        String message() {
            boolean chinese = Settings.language == Settings.GameLanguage.ZHS
                || Settings.language == Settings.GameLanguage.ZHT;
            if (this == DRAWING_ACTIVE) {
                return chinese ? "请先退出路线绘制模式" : "Exit route drawing before selecting a room";
            }
            if (this == FOLLOW_ROUTE) {
                return chinese
                    ? "该房间不在队伍已经选择的全局路线中"
                    : "That room is not on the party's selected global route";
            }
            return "";
        }
    }

    private static final class PlayerProgress {
        final Object networkPlayer;
        final int id;
        final int y;
        final boolean self;
        final RecordedRoute route;

        PlayerProgress(
            Object networkPlayer,
            int id,
            int y,
            boolean self,
            RecordedRoute route
        ) {
            this.networkPlayer = networkPlayer;
            this.id = id;
            this.y = y;
            this.self = self;
            this.route = route;
        }
    }

    private static final class RecordedRoute {
        private static final String VERSION = "v1";

        final String act;
        final int infinity;
        final TreeMap<Integer, Integer> nodes = new TreeMap<>();

        RecordedRoute(String act, int infinity) {
            this.act = act;
            this.infinity = infinity;
        }

        RecordedRoute copy() {
            RecordedRoute copy = new RecordedRoute(act, infinity);
            copy.nodes.putAll(nodes);
            return copy;
        }

        boolean matches(String otherAct, int otherInfinity) {
            return Objects.equals(act, otherAct) && infinity == otherInfinity;
        }

        boolean record(int y, int x) {
            Integer previous = nodes.put(y, x);
            return previous == null || previous != x;
        }

        Integer nodeXAt(int y) {
            return nodes.get(y);
        }

        int maxY() {
            return nodes.isEmpty() ? Integer.MIN_VALUE : nodes.lastKey();
        }

        String serialize() {
            StringBuilder value = new StringBuilder(VERSION)
                .append('|')
                .append(act)
                .append('|')
                .append(infinity)
                .append('|');
            boolean first = true;
            for (Map.Entry<Integer, Integer> node : nodes.entrySet()) {
                if (!first) {
                    value.append(',');
                }
                value.append(node.getKey()).append(':').append(node.getValue());
                first = false;
            }
            return value.toString();
        }

        static RecordedRoute parse(String value) {
            if (value == null) {
                return null;
            }
            String[] parts = value.split("\\|", -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                return null;
            }
            try {
                RecordedRoute route = new RecordedRoute(parts[1], Integer.parseInt(parts[2]));
                if (parts[3].isEmpty()) {
                    return route;
                }
                for (String encodedNode : parts[3].split(",")) {
                    String[] node = encodedNode.split(":", -1);
                    if (node.length != 2) {
                        return null;
                    }
                    route.nodes.put(Integer.parseInt(node[0]), Integer.parseInt(node[1]));
                }
                return route;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}

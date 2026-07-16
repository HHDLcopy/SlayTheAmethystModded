package io.stamethyst.floatingtools;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.PowerTip;
import com.megacrit.cardcrawl.helpers.TipHelper;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import java.util.ArrayList;
import org.lwjgl.input.Keyboard;

final class FloatingToolPanel {
    private static final String PROP_ENABLED = "amethyst.floating_tools.enabled";

    private static final float TAB_HIT_W = 36f;
    private static final float TAB_HIT_H = 128f;
    private static final float SIDE_PANEL_X = 50f;
    private static final float RELIC_IMG_SIZE = 128f;
    private static final float RELIC_HIT_SIZE = 72f;
    private static final float RELIC_SPACE_Y = 88f;
    private static final float RELIC_START_TOP = 155f;
    private static final float TIP_BOX_W = 320f;
    private static final float TIP_SIDE_PAD = 24f;
    private static final float TIP_ANCHOR_GAP = 46f;
    private static final float TIP_TOP_OFFSET = 58f;
    private static final float BUTTON_PRESS_MAX_SCALE = 1.24f;
    private static final float BUTTON_PRESS_INITIAL_SCALE = 1.08f;
    private static final float BUTTON_PRESS_GROW_SPEED = 18f;
    private static final float BUTTON_PRESS_SHRINK_SPEED = 12f;

    private static final Color WHITE = new Color(1f, 1f, 1f, 1f);
    private static final Color SHADOW = new Color(0f, 0f, 0f, 0.38f);
    private static final Color PASSIVE_OUTLINE = new Color(0f, 0f, 0f, 0.33f);
    private static final Color ACTIVE_OUTLINE = new Color(0.62f, 0.9f, 0.38f, 0.72f);
    private static final Color RELIC_DARK = new Color(0.075f, 0.068f, 0.052f, 1f);
    private static final Color RELIC_MID = new Color(0.18f, 0.14f, 0.075f, 1f);
    private static final Color RELIC_GREEN = new Color(0.60f, 0.85f, 0.42f, 1f);
    private static final Color ICON_ACTIVE_TINT = new Color(0.82f, 1f, 0.64f, 1f);
    private static final String ICON_PATH = "amethystFloatingTools/images/tools/";

    private final ArrayList<ToolButton> buttons = new ArrayList<ToolButton>();
    private final ArrayList<PowerTip> tabTips = new ArrayList<PowerTip>();
    private final FloatingToolWheel wheel = new FloatingToolWheel();
    private final Color sidePanelTint = new Color();
    private final Texture[] iconTextures = new Texture[Action.values().length];
    private final float[] buttonScaleOffsets = new float[Action.values().length];

    private Texture sidePanelTab;
    private Texture sidePanelArrow;
    private boolean enabled;
    private boolean expanded;
    private boolean ctrlDown;
    private boolean shiftDown;
    private boolean altDown;
    private boolean locked;
    private boolean rightMode;
    private boolean rightSurfaceDown;
    private boolean uiLeftPressActive;

    private float drawerProgress;
    private float tabCenterX;
    private float tabCenterY;
    private float drawerX;
    private float drawerY;
    private float drawerW;
    private float drawerH;
    private Action lastHoveredAction;
    private Action pressedAction;

    void configureFromSystemProperties() {
        boolean wasEnabled = enabled;
        enabled = Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "false"));
        if (!enabled && wasEnabled) {
            releaseAllHeldKeys();
            expanded = false;
            locked = false;
            rightMode = false;
            rightSurfaceDown = false;
            uiLeftPressActive = false;
            lastHoveredAction = null;
            clearButtonPressState();
            wheel.end();
        }
        layout();
    }

    boolean isEnabled() {
        return enabled;
    }

    void updateFrame() {
        if (!enabled) {
            return;
        }
        float delta = Gdx.graphics.getDeltaTime();
        updateButtonPressScales(delta);
        float target = expanded ? 1f : 0f;
        drawerProgress = MathUtils.lerp(drawerProgress, target, delta * 6f);
        if (Math.abs(drawerProgress - target) < 0.005f) {
            drawerProgress = target;
        }
        layout();
    }

    void updateFromInputHelper() {
        if (!enabled) {
            return;
        }
        layout();
        if (wheel.isActive()) {
            updateActiveWheel();
            consumeLeftInput();
            return;
        }
        if (uiLeftPressActive) {
            if (InputHelper.justReleasedClickLeft || !InputHelper.isMouseDown) {
                uiLeftPressActive = false;
                endButtonPress();
            }
            consumeLeftInput();
            return;
        }

        boolean overTab = containsTab(InputHelper.mX, InputHelper.mY);
        ToolButton button = findButton(InputHelper.mX, InputHelper.mY);
        updateTabHoverSound(overTab);
        boolean overDrawer = containsDrawer(InputHelper.mX, InputHelper.mY);
        if (overTab && (InputHelper.justClickedLeft || InputHelper.justClickedRight)) {
            toggleExpanded();
            playClick();
            consumePointerInput();
            return;
        }
        if (button != null && InputHelper.justClickedLeft) {
            startButtonPress(button.action);
            activate(button);
            playClick();
            uiLeftPressActive = button.action != Action.WHEEL;
            consumePointerInput();
            return;
        }
        if (overDrawer || overTab) {
            if (hasPointerInput()) {
                consumePointerInput();
            }
            return;
        }
        if (locked) {
            rightSurfaceDown = false;
            consumePointerInput();
            return;
        }
        if (rightMode) {
            transformLeftClickToRightClick();
        } else {
            rightSurfaceDown = false;
        }
    }

    void render(SpriteBatch sb) {
        if (!enabled) {
            return;
        }
        layout();
        if (drawerProgress > 0.02f) {
            renderDrawer(sb);
        }
        renderTab(sb);
        renderTabTip();
        sb.setColor(Color.WHITE);
    }

    private void toggleExpanded() {
        expanded = !expanded;
    }

    private void activate(ToolButton button) {
        if (button == null) {
            return;
        }
        switch (button.action) {
            case ONLINE:
                expanded = false;
                FloatingToolInputBridge.requestOnlinePanel("floating_tools_drawer");
                break;
            case CTRL:
                ctrlDown = toggleKey(Keyboard.KEY_LCONTROL, ctrlDown);
                break;
            case SHIFT:
                shiftDown = toggleKey(Keyboard.KEY_LSHIFT, shiftDown);
                break;
            case TAB:
                FloatingToolInputBridge.sendKeyStroke(Keyboard.KEY_TAB, '\t');
                break;
            case ALT:
                altDown = toggleKey(Keyboard.KEY_LMENU, altDown);
                break;
            case LOCK:
                locked = !locked;
                rightSurfaceDown = false;
                break;
            case WHEEL:
                wheel.begin(wheelOffset(button));
                break;
            case MOUSE_MODE:
                rightMode = !rightMode;
                rightSurfaceDown = false;
                break;
            case KEYBOARD:
                expanded = false;
                FloatingToolInputBridge.requestKeyboard("floating_tools_drawer");
                break;
            case ADD_KEY:
                expanded = false;
                FloatingToolInputBridge.requestCustomButton("floating_tools_drawer");
                break;
            default:
                break;
        }
    }

    private boolean toggleKey(int keyCode, boolean wasDown) {
        FloatingToolInputBridge.sendKey(keyCode, !wasDown);
        return !wasDown;
    }

    private void releaseAllHeldKeys() {
        if (ctrlDown) {
            FloatingToolInputBridge.sendKey(Keyboard.KEY_LCONTROL, false);
            ctrlDown = false;
        }
        if (shiftDown) {
            FloatingToolInputBridge.sendKey(Keyboard.KEY_LSHIFT, false);
            shiftDown = false;
        }
        if (altDown) {
            FloatingToolInputBridge.sendKey(Keyboard.KEY_LMENU, false);
            altDown = false;
        }
    }

    private void updateActiveWheel() {
        ToolButton button = buttonFor(Action.WHEEL);
        if (button != null && InputHelper.isMouseDown) {
            wheel.update(wheelOffset(button));
        }
        if (InputHelper.justReleasedClickLeft || !InputHelper.isMouseDown) {
            wheel.end();
            endButtonPress();
        }
    }

    private void updateButtonPressScales(float delta) {
        if (delta <= 0f) {
            return;
        }
        for (Action action : Action.values()) {
            int index = action.ordinal();
            float target = action == pressedAction ? BUTTON_PRESS_MAX_SCALE - 1f : 0f;
            float speed = action == pressedAction ? BUTTON_PRESS_GROW_SPEED : BUTTON_PRESS_SHRINK_SPEED;
            buttonScaleOffsets[index] = MathUtils.lerp(
                buttonScaleOffsets[index],
                target,
                MathUtils.clamp(delta * speed, 0f, 1f)
            );
            if (Math.abs(buttonScaleOffsets[index] - target) < 0.002f) {
                buttonScaleOffsets[index] = target;
            }
        }
    }

    private void clearButtonPressState() {
        pressedAction = null;
        for (int i = 0; i < buttonScaleOffsets.length; i++) {
            buttonScaleOffsets[i] = 0f;
        }
    }

    private void startButtonPress(Action action) {
        pressedAction = action;
        int index = action.ordinal();
        buttonScaleOffsets[index] = Math.max(
            buttonScaleOffsets[index],
            BUTTON_PRESS_INITIAL_SCALE - 1f
        );
    }

    private void endButtonPress() {
        pressedAction = null;
    }

    private float buttonPressScale(Action action) {
        return 1f + buttonScaleOffsets[action.ordinal()];
    }

    private float wheelOffset(ToolButton button) {
        return MathUtils.clamp((InputHelper.mY - button.centerY) / (button.hitH / 2f), -1f, 1f);
    }

    private void transformLeftClickToRightClick() {
        if (InputHelper.justClickedLeft) {
            rightSurfaceDown = true;
            clearLeftFields();
            InputHelper.justClickedRight = true;
            InputHelper.isMouseDown_R = true;
            return;
        }
        if (!rightSurfaceDown) {
            return;
        }
        if (InputHelper.justReleasedClickLeft || !InputHelper.isMouseDown) {
            rightSurfaceDown = false;
            clearLeftFields();
            InputHelper.justReleasedClickRight = true;
            InputHelper.isMouseDown_R = false;
        } else {
            clearLeftFields();
            InputHelper.isMouseDown_R = true;
        }
    }

    private void layout() {
        buttons.clear();
        float s = Settings.scale;
        float hit = RELIC_HIT_SIZE * s;
        float hiddenX = Settings.WIDTH + 100f * s;
        float targetX = Settings.WIDTH - SIDE_PANEL_X * s;
        float currentX = MathUtils.lerp(hiddenX, targetX, drawerProgress);
        float yPos = Settings.HEIGHT - RELIC_START_TOP * Settings.yScale;
        float spaceY = RELIC_SPACE_Y * s;

        addButton(Action.ONLINE, "Online", currentX, yPos, hit, hit);
        addButton(Action.CTRL, "Ctrl", currentX, yPos -= spaceY, hit, hit);
        addButton(Action.SHIFT, "Shift", currentX, yPos -= spaceY, hit, hit);
        addButton(Action.TAB, "Tab", currentX, yPos -= spaceY, hit, hit);
        addButton(Action.ALT, "Alt", currentX, yPos -= spaceY, hit, hit);
        addButton(Action.LOCK, locked ? "Unlock" : "Lock", currentX, yPos -= spaceY, hit, hit);
        addButton(Action.WHEEL, "Wheel", currentX, yPos -= spaceY, hit, hit);
        addButton(Action.MOUSE_MODE, rightMode ? "Right mouse" : "Left mouse", currentX, yPos -= spaceY, hit, hit);
        addButton(Action.KEYBOARD, "Keyboard", currentX, yPos -= spaceY, hit, hit);
        addButton(Action.ADD_KEY, "Add key", currentX, yPos -= spaceY, hit, hit);

        float visual = RELIC_IMG_SIZE * s;
        drawerX = currentX - visual / 2f;
        drawerW = visual;
        drawerY = yPos - visual / 2f;
        drawerH = spaceY * (buttons.size() - 1) + visual;

        float collapsedTabX = Settings.WIDTH - TAB_HIT_W * s / 2f;
        float expandedTabX = targetX - TAB_HIT_W * s * 1.5f;
        tabCenterX = MathUtils.lerp(collapsedTabX, expandedTabX, drawerProgress);
        tabCenterY = Settings.HEIGHT / 2f;
    }

    private void addButton(Action action, String label, float centerX, float centerY, float hitW, float hitH) {
        buttons.add(new ToolButton(action, label, centerX, centerY, hitW, hitH));
    }

    private ToolButton findButton(float px, float py) {
        if (drawerProgress < 0.22f) {
            return null;
        }
        for (ToolButton button : buttons) {
            if (button.contains(px, py)) {
                return button;
            }
        }
        return null;
    }

    private ToolButton buttonFor(Action action) {
        for (ToolButton button : buttons) {
            if (button.action == action) {
                return button;
            }
        }
        return null;
    }

    private boolean containsDrawer(float px, float py) {
        return drawerProgress > 0.22f &&
            px >= drawerX &&
            px <= drawerX + drawerW &&
            py >= drawerY &&
            py <= drawerY + drawerH;
    }

    private boolean containsTab(float px, float py) {
        float s = Settings.scale;
        float halfW = TAB_HIT_W * s / 2f;
        float halfH = TAB_HIT_H * s / 2f;
        return px >= tabCenterX - halfW &&
            px <= tabCenterX + halfW &&
            py >= tabCenterY - halfH &&
            py <= tabCenterY + halfH;
    }

    private void renderDrawer(SpriteBatch sb) {
        for (ToolButton button : buttons) {
            renderButton(sb, button);
        }
    }

    private void renderButton(SpriteBatch sb, ToolButton button) {
        boolean hovered = button.contains(InputHelper.mX, InputHelper.mY);
        boolean active = isActive(button.action);
        float drawScale = Settings.scale * buttonPressScale(button.action);
        renderRelicOutline(sb, button.centerX, button.centerY, drawScale, active);
        renderRelicBody(sb, button.centerX, button.centerY, drawScale, active);
        renderIcon(sb, button, drawScale, active);
        if (hovered) {
            queueToolTips(button.tips, button.centerX, button.centerY);
        }
    }

    private void renderTabTip() {
        if (!containsTab(InputHelper.mX, InputHelper.mY)) {
            return;
        }
        tabTips.clear();
        tabTips.add(
            new PowerTip(
                expanded ? "收起工具抽屉" : "展开工具抽屉",
                expanded ?
                    "收起右侧 Loadout 风格工具列，保留右侧侧边标签。" :
                    "展开右侧 Loadout 风格工具列，显示鼠标、键盘和滚轮控制图标。"
            )
        );
        queueToolTips(tabTips, tabCenterX, tabCenterY);
    }

    private void queueToolTips(ArrayList<PowerTip> tips, float anchorX, float anchorY) {
        float s = Settings.scale;
        float boxW = TIP_BOX_W * s;
        float sidePad = TIP_SIDE_PAD * s;
        float x;
        if (anchorX > Settings.WIDTH / 2f) {
            x = anchorX - boxW - TIP_ANCHOR_GAP * s;
        } else {
            x = anchorX + TIP_ANCHOR_GAP * s;
        }
        float maxX = Math.max(sidePad, Settings.WIDTH - boxW - sidePad);
        x = MathUtils.clamp(x, sidePad, maxX);

        float y = Math.min(anchorY + TIP_TOP_OFFSET * s, Settings.HEIGHT - sidePad);
        y += TipHelper.calculateToAvoidOffscreen(tips, y);
        y = Math.min(y, Settings.HEIGHT - sidePad);
        TipHelper.queuePowerTips(x, y, tips);
    }

    private boolean isActive(Action action) {
        switch (action) {
            case CTRL:
                return ctrlDown;
            case SHIFT:
                return shiftDown;
            case ALT:
                return altDown;
            case LOCK:
                return locked;
            case MOUSE_MODE:
                return rightMode;
            case WHEEL:
                return wheel.isActive();
            default:
                return false;
        }
    }

    private void renderTab(SpriteBatch sb) {
        ensureSidePanelTextures();
        updateSidePanelTint();
        float s = Settings.scale;
        sb.setColor(sidePanelTint);
        sb.draw(
            sidePanelTab,
            tabCenterX - 12f,
            tabCenterY - 64f,
            15.5f,
            64f,
            31f,
            128f,
            s,
            s,
            0f,
            0,
            0,
            32,
            128,
            true,
            false
        );
        sb.setColor(WHITE);
        sb.draw(
            sidePanelArrow,
            tabCenterX - 12f,
            tabCenterY - 16f,
            16f,
            16f,
            32f,
            32f,
            s,
            s,
            0f,
            0,
            0,
            32,
            32,
            !expanded,
            false
        );
    }

    private void renderRelicOutline(SpriteBatch sb, float cx, float cy, float scale, boolean active) {
        drawRelicShape(sb, cx + 5f * scale, cy - 5f * scale, scale, SHADOW);
        if (active) {
            sb.setBlendFunction(770, 1);
            drawRelicShape(sb, cx, cy, scale, ACTIVE_OUTLINE);
            sb.setBlendFunction(770, 771);
        } else {
            drawRelicShape(sb, cx, cy, scale, PASSIVE_OUTLINE);
        }
    }

    private void renderRelicBody(SpriteBatch sb, float cx, float cy, float scale, boolean active) {
        drawRelicShape(sb, cx, cy, scale * 0.86f, RELIC_DARK);
        drawRotatedRect(sb, cx, cy, 60f * scale, 60f * scale, 45f, active ? RELIC_GREEN : RELIC_MID);
        drawRelicShape(sb, cx, cy, scale * 0.58f, RELIC_DARK);
    }

    private void drawRelicShape(SpriteBatch sb, float cx, float cy, float scale, Color color) {
        drawRotatedRect(sb, cx, cy, 76f * scale, 76f * scale, 45f, color);
        drawRectCentered(sb, cx, cy, 72f * scale, 52f * scale, color);
        drawRectCentered(sb, cx, cy, 52f * scale, 72f * scale, color);
    }

    private void renderIcon(SpriteBatch sb, ToolButton button, float scale, boolean active) {
        Texture icon = iconFor(button.action);
        if (icon == null) {
            return;
        }
        float size = 88f * scale;
        sb.setColor(active ? ICON_ACTIVE_TINT : WHITE);
        sb.draw(icon, button.centerX - size / 2f, button.centerY - size / 2f, size, size);
        sb.setColor(WHITE);
    }

    private Texture iconFor(Action action) {
        Texture icon = iconTextures[action.ordinal()];
        if (icon != null) {
            return icon;
        }
        String filename = null;
        switch (action) {
            case ONLINE:
                filename = "online.png";
                break;
            case CTRL:
                filename = "ctrl.png";
                break;
            case SHIFT:
                filename = "shift.png";
                break;
            case TAB:
                filename = "tab.png";
                break;
            case ALT:
                filename = "alt.png";
                break;
            case LOCK:
                filename = "lock.png";
                break;
            case WHEEL:
                filename = "wheel.png";
                break;
            case MOUSE_MODE:
                filename = "mouse.png";
                break;
            case KEYBOARD:
                filename = "keyboard.png";
                break;
            case ADD_KEY:
                filename = "add_key.png";
                break;
            default:
                break;
        }
        if (filename == null) {
            return null;
        }
        icon = loadToolTexture(filename);
        iconTextures[action.ordinal()] = icon;
        return icon;
    }

    private void ensureSidePanelTextures() {
        if (sidePanelTab == null) {
            sidePanelTab = loadToolTexture("side_panel_tab.png");
        }
        if (sidePanelArrow == null) {
            sidePanelArrow = loadToolTexture("side_panel_arrow.png");
        }
    }

    private Texture loadToolTexture(String filename) {
        Texture texture = ImageMaster.loadImage(ICON_PATH + filename);
        if (texture != null) {
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }
        return texture;
    }

    private void updateSidePanelTint() {
        sidePanelTint.r = (MathUtils.cosDeg(System.currentTimeMillis() / 10L % 360L) + 1.25f) / 2.3f;
        sidePanelTint.g = (MathUtils.cosDeg((System.currentTimeMillis() + 1000L) / 10L % 360L) + 1.25f) / 2.3f;
        sidePanelTint.b = (MathUtils.cosDeg((System.currentTimeMillis() + 2000L) / 10L % 360L) + 1.25f) / 2.3f;
        sidePanelTint.a = 1f;
    }

    private static void drawRectCentered(SpriteBatch sb, float cx, float cy, float w, float h, Color color) {
        drawRect(sb, cx - w / 2f, cy - h / 2f, w, h, color);
    }

    private static void drawRect(SpriteBatch sb, float x, float y, float w, float h, Color color) {
        sb.setColor(color);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, w, h);
    }

    private static void drawRotatedRect(SpriteBatch sb, float cx, float cy, float w, float h, float angle, Color color) {
        sb.setColor(color);
        sb.draw(
            ImageMaster.WHITE_SQUARE_IMG,
            cx - w / 2f,
            cy - h / 2f,
            w / 2f,
            h / 2f,
            w,
            h,
            1f,
            1f,
            angle,
            0,
            0,
            1,
            1,
            false,
            false
        );
    }

    private void updateTabHoverSound(boolean overTab) {
        Action hovered = overTab ? Action.TAB_HANDLE : null;
        if (hovered != null && hovered != lastHoveredAction) {
            playHover();
        }
        lastHoveredAction = hovered;
    }

    private boolean hasPointerInput() {
        return InputHelper.justClickedLeft ||
            InputHelper.justClickedRight ||
            InputHelper.justReleasedClickLeft ||
            InputHelper.justReleasedClickRight ||
            InputHelper.isMouseDown ||
            InputHelper.isMouseDown_R;
    }

    private void consumePointerInput() {
        consumeLeftInput();
        InputHelper.justClickedRight = false;
        InputHelper.justReleasedClickRight = false;
        InputHelper.isMouseDown_R = false;
    }

    private void consumeLeftInput() {
        clearLeftFields();
    }

    private void clearLeftFields() {
        InputHelper.justClickedLeft = false;
        InputHelper.justReleasedClickLeft = false;
        InputHelper.isMouseDown = false;
        InputHelper.touchDown = false;
        InputHelper.touchUp = false;
    }

    private void playHover() {
        try {
            if (CardCrawlGame.sound != null) {
                CardCrawlGame.sound.playA("UI_HOVER", -0.3f);
            }
        } catch (Throwable ignored) {
        }
    }

    private void playClick() {
        try {
            if (CardCrawlGame.sound != null) {
                CardCrawlGame.sound.playA("UI_CLICK_1", -0.2f);
            }
        } catch (Throwable ignored) {
        }
    }

    private enum Action {
        ONLINE,
        CTRL,
        SHIFT,
        TAB,
        ALT,
        LOCK,
        WHEEL,
        MOUSE_MODE,
        KEYBOARD,
        ADD_KEY,
        TAB_HANDLE
    }

    private static final class ToolButton {
        final Action action;
        final String label;
        final float centerX;
        final float centerY;
        final float hitW;
        final float hitH;
        final ArrayList<PowerTip> tips = new ArrayList<PowerTip>();

        ToolButton(Action action, String label, float centerX, float centerY, float hitW, float hitH) {
            this.action = action;
            this.label = label;
            this.centerX = centerX;
            this.centerY = centerY;
            this.hitW = hitW;
            this.hitH = hitH;
            this.tips.add(new PowerTip(label, descriptionFor(action, label)));
        }

        boolean contains(float px, float py) {
            return px >= centerX - hitW / 2f &&
                px <= centerX + hitW / 2f &&
                py >= centerY - hitH / 2f &&
                py <= centerY + hitH / 2f;
        }

        private static String descriptionFor(Action action, String label) {
            switch (action) {
                case ONLINE:
                    return "打开 Android 联机窗口，可查看房间、连接状态、玩家 IP 和房主管理操作。";
                case CTRL:
                    return "切换 Ctrl 修饰键。启用后保持 Ctrl 按下，再点一次释放。";
                case SHIFT:
                    return "切换 Shift 修饰键。启用后保持 Shift 按下，再点一次释放。";
                case TAB:
                    return "发送一次 Tab 键，用于切换焦点或触发支持 Tab 的游戏界面操作。";
                case ALT:
                    return "切换 Alt 修饰键。启用后保持 Alt 按下，再点一次释放。";
                case LOCK:
                    return "锁定时会吞掉游戏点击，避免误点卡牌或按钮；再次点击解除锁定。";
                case WHEEL:
                    return "按住图标上半部向上滚动，下半部向下滚动；离中心越远滚动越快。";
                case MOUSE_MODE:
                    return "切换触摸点击的鼠标按钮。右键模式会保持启用，直到再次点击此图标切回左键。";
                case KEYBOARD:
                    return "打开旧版 Android 侧键盘输入；根据启动器设置使用内置键盘或系统输入法。";
                case ADD_KEY:
                    return "添加一个 Android 侧自定义按键悬浮窗。悬浮窗由内置软键盘路径发送按键，可长按拖动并拖到垃圾桶删除。";
                default:
                    return label;
            }
        }
    }
}

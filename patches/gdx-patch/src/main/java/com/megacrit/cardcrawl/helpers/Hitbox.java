package com.megacrit.cardcrawl.helpers;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.controller.CInputActionSet;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class Hitbox {
    public float x;
    public float y;
    public float cX;
    public float cY;
    public float width;
    public float height;
    public boolean hovered = false;
    public boolean justHovered = false;
    public boolean clickStarted = false;
    public boolean clicked = false;
    private static final String PRE_CLICK_HOVER_REFRESH_ENABLED_PROP =
        "amethyst.pre_click_hitbox_hover_refresh_enabled";
    private static final String NATIVE_TOUCHSCREEN_ENABLED_PROP =
        "amethyst.native_touchscreen_enabled";
    private static ArrayList<WeakReference<Hitbox>> registeredHitboxes;
    private static Boolean preClickHoverRefreshEnabled;
    private static Boolean nativeTouchscreenEnabled;
    private static long preClickHoverRefreshFrame;
    private static boolean deferredPreClickHoverPress;
    private static boolean deferredPreClickHoverRelease;
    private static boolean deferredPreClickHoverPressDeliveredThisFrame;
    private long preClickHoverRefreshLastUpdatedFrame;

    public Hitbox(float width, float height) {
        this(-10000.0f, -10000.0f, width, height);
    }

    public Hitbox(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.cX = x + width / 2.0f;
        this.cY = y + height / 2.0f;
        registerForPreClickHoverRefresh(this);
    }

    public static void refreshAllHoveredForFreshClick() {
        if (!isPreClickHoverRefreshEnabled()) {
            return;
        }
        ArrayList<WeakReference<Hitbox>> hitboxes = registeredHitboxes;
        if (hitboxes == null || hitboxes.isEmpty() || AbstractDungeon.isFadingOut) {
            return;
        }
        for (int i = hitboxes.size() - 1; i >= 0; --i) {
            Hitbox hitbox = hitboxes.get(i).get();
            if (hitbox == null) {
                hitboxes.remove(i);
            } else if (wasUpdatedInPreviousPreClickHoverFrame(hitbox)) {
                // Match desktop hit-testing: inactive layers must not gain hover from a fresh touch.
                refreshHoveredForFreshClick(hitbox);
            }
        }
    }

    public static void beginPreClickHoverRefreshFrame() {
        ++preClickHoverRefreshFrame;
        deferredPreClickHoverPressDeliveredThisFrame = false;
    }

    public static void dispatchDeferredPreClickHoverInput() {
        if (!isPreClickHoverInputDeferralEnabled()) {
            return;
        }
        if (deferredPreClickHoverPress) {
            deferredPreClickHoverPress = false;
            deferredPreClickHoverPressDeliveredThisFrame = true;
            InputHelper.justClickedLeft = true;
            refreshAllHoveredForFreshClick();
        } else if (deferredPreClickHoverRelease) {
            deferredPreClickHoverRelease = false;
            InputHelper.justReleasedClickLeft = true;
        }
    }

    public static void deferFreshPreClickHoverClick() {
        if (!isPreClickHoverInputDeferralEnabled()) {
            refreshAllHoveredForFreshClick();
            return;
        }
        deferredPreClickHoverPress = true;
        InputHelper.justClickedLeft = false;
    }

    public static void deferPreClickHoverReleaseIfNeeded() {
        if (!isPreClickHoverInputDeferralEnabled()
            || !deferredPreClickHoverPressDeliveredThisFrame) {
            return;
        }
        deferredPreClickHoverRelease = true;
        InputHelper.justReleasedClickLeft = false;
    }

    private static void registerForPreClickHoverRefresh(Hitbox hitbox) {
        if (registeredHitboxes == null) {
            registeredHitboxes = new ArrayList<WeakReference<Hitbox>>();
        }
        registeredHitboxes.add(new WeakReference<Hitbox>(hitbox));
    }

    private static void markUpdatedForPreClickHoverRefresh(Hitbox hitbox) {
        if (hitbox != null) {
            hitbox.preClickHoverRefreshLastUpdatedFrame = preClickHoverRefreshFrame;
        }
    }

    private static boolean wasUpdatedInPreviousPreClickHoverFrame(Hitbox hitbox) {
        return hitbox != null
            && hitbox.preClickHoverRefreshLastUpdatedFrame == preClickHoverRefreshFrame - 1L;
    }

    private static boolean isPreClickHoverRefreshEnabled() {
        if (preClickHoverRefreshEnabled != null) {
            return preClickHoverRefreshEnabled.booleanValue();
        }
        Boolean parsed = parseBooleanLike(System.getProperty(PRE_CLICK_HOVER_REFRESH_ENABLED_PROP));
        preClickHoverRefreshEnabled = parsed == null ? Boolean.TRUE : parsed;
        return preClickHoverRefreshEnabled.booleanValue();
    }

    private static boolean isPreClickHoverInputDeferralEnabled() {
        return isPreClickHoverRefreshEnabled() && isNativeTouchscreenEnabled();
    }

    private static boolean isNativeTouchscreenEnabled() {
        if (nativeTouchscreenEnabled != null) {
            return nativeTouchscreenEnabled.booleanValue();
        }
        Boolean parsed = parseBooleanLike(System.getProperty(NATIVE_TOUCHSCREEN_ENABLED_PROP));
        nativeTouchscreenEnabled = parsed == null ? Boolean.FALSE : parsed;
        return nativeTouchscreenEnabled.booleanValue();
    }

    private static Boolean parseBooleanLike(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String normalized = rawValue.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(normalized)
            || "1".equals(normalized)
            || "yes".equalsIgnoreCase(normalized)
            || "on".equalsIgnoreCase(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(normalized)
            || "0".equals(normalized)
            || "no".equalsIgnoreCase(normalized)
            || "off".equalsIgnoreCase(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static void refreshHoveredForFreshClick(Hitbox hitbox) {
        boolean wasHovered = hitbox.hovered;
        boolean isHovered = isPointerInside(hitbox);
        hitbox.hovered = isHovered;
        hitbox.justHovered = !wasHovered && isHovered;
    }

    private static boolean isPointerInside(Hitbox hitbox) {
        return (float)InputHelper.mX > hitbox.x
            && (float)InputHelper.mX < hitbox.x + hitbox.width
            && (float)InputHelper.mY > hitbox.y
            && (float)InputHelper.mY < hitbox.y + hitbox.height;
    }

    public void update() {
        this.update(this.x, this.y);
        if (this.clickStarted && InputHelper.justReleasedClickLeft) {
            if (this.hovered) {
                this.clicked = true;
            }
            this.clickStarted = false;
        }
    }

    public void update(float x, float y) {
        if (AbstractDungeon.isFadingOut) {
            return;
        }
        this.x = x;
        this.y = y;
        if (this.justHovered) {
            this.justHovered = false;
        }
        if (!this.hovered) {
            this.hovered = isPointerInside(this);
            if (this.hovered) {
                this.justHovered = true;
            }
        } else {
            this.hovered = isPointerInside(this);
        }
    }

    public void encapsulatedUpdate(HitboxListener listener) {
        this.update();
        if (this.justHovered) {
            listener.hoverStarted(this);
        }
        if (this.hovered && InputHelper.justClickedLeft) {
            this.clickStarted = true;
            listener.startClicking(this);
        } else if (this.clicked || this.hovered && CInputActionSet.select.isJustPressed()) {
            CInputActionSet.select.unpress();
            this.clicked = false;
            listener.clicked(this);
        }
    }

    public void unhover() {
        this.hovered = false;
        this.justHovered = false;
    }

    public void move(float cX, float cY) {
        this.cX = cX;
        this.cY = cY;
        this.x = cX - this.width / 2.0f;
        this.y = cY - this.height / 2.0f;
    }

    public void moveY(float cY) {
        this.cY = cY;
        this.y = cY - this.height / 2.0f;
    }

    public void moveX(float cX) {
        this.cX = cX;
        this.x = cX - this.width / 2.0f;
    }

    public void translate(float x, float y) {
        this.x = x;
        this.y = y;
        this.cX = x + this.width / 2.0f;
        this.cY = y + this.height / 2.0f;
    }

    public void resize(float width, float height) {
        this.width = width;
        this.height = height;
    }

    public boolean intersects(Hitbox other) {
        return this.x < other.x + other.width
            && this.x + this.width > other.x
            && this.y < other.y + other.height
            && this.y + this.height > other.y;
    }

    public void render(SpriteBatch sb) {
        if (!Settings.isDebug && !Settings.isInfo) {
            return;
        }
        if (this.clickStarted) {
            sb.setColor(Color.CHARTREUSE);
        } else {
            sb.setColor(Color.RED);
        }
        sb.draw(ImageMaster.DEBUG_HITBOX_IMG, this.x, this.y, this.width, this.height);
    }
}

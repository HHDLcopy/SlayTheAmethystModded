package io.stamethyst.floatingtools;

import basemod.BaseMod;
import basemod.interfaces.PostInitializeSubscriber;
import basemod.interfaces.PostRenderSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;

@SpireInitializer
public class AmethystFloatingTools implements PostInitializeSubscriber, PostRenderSubscriber, PostUpdateSubscriber {
    private static final FloatingToolPanel PANEL = new FloatingToolPanel();

    public static void initialize() {
        PANEL.configureFromSystemProperties();
        BaseMod.subscribe(new AmethystFloatingTools());
    }

    public static void updateInputFromGame() {
        PANEL.updateFromInputHelper();
    }

    public static boolean isEnabled() {
        return PANEL.isEnabled();
    }

    @Override
    public void receivePostInitialize() {
        PANEL.configureFromSystemProperties();
    }

    @Override
    public void receivePostUpdate() {
        PANEL.updateFrame();
    }

    @Override
    public void receivePostRender(SpriteBatch sb) {
        PANEL.render(sb);
    }
}

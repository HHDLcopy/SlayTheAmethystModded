# Amethyst Floating Tools

This built-in mod replaces the Android-side floating mouse window during ModTheSpire launches with an in-game right-side collapsible tool drawer drawn by the game JVM.

## Included fixes and tools

1. `FloatingToolPanel`
Draws a fixed right-side collapsible drawer that mirrors Loadout's `AllInOneBag` side panel: a 32x128 side tab, 32x32 arrow, Loadout-style animated tab movement, and a more widely spaced vertical column of 128x128 relic-style icon tools with no text buttons in the expanded panel. The icon column implements Ctrl, Shift, Tab, Alt, lock mode, virtual wheel, left/right mouse mode, and keyboard actions; the side tab remains the only drawer expand/collapse control. Each icon has a `TipHelper` hover tooltip anchored back into the visible screen from the right-side drawer and is rendered from a dedicated PNG under `amethystFloatingTools/images/tools/` instead of ambiguous procedural line art. Hovering an icon only shows its tooltip; icon scale, outline, and tint no longer change on hover. Pressing or holding an icon smoothly grows the relic-style button, and releasing it smoothly shrinks the button back to normal size. The left/right mouse mode icon uses the same selected-state outline, body, and tint as the other selected icons while right-click mode is enabled. Right-click mode is sticky in this drawer: one right-click action no longer switches it back to left-click mode, and the launcher auto-switch setting is not read for this in-game drawer. The keyboard icon no longer opens the mod's self-drawn JVM keyboard; it now delegates to the previous Android-side soft-keyboard path so the launcher setting decides between the old built-in keyboard and the system IME. This addresses the symptom where the Android overlay could not visually match in-game mod UI and appeared on top of the renderer as a separate platform widget, and also addresses unclear self-drawn tool icons, tooltips being queued off the right edge of the screen, the tool icons appearing too visually crowded, hover feedback feeling like the primary action animation, redundant collapse tooling inside the expanded drawer, right-click mode not matching the selected-state style of other icons, right-click mode unexpectedly switching back after one click in this drawer, and the keyboard button opening the wrong new keyboard implementation.

2. `FloatingToolInputBridge`
Sends left/right mouse button, wheel, modifier, special-key, typed-character, paste, and Android keyboard-request events from the game JVM into the existing LWJGL input queues or launcher request files. Plain keyboard requests reuse the old Android-side keyboard selector, while explicit system-keyboard requests can still force the Android IME. This addresses the symptom where a self-drawn in-game panel needs to drive the same gameplay input paths as the old Android floating window.

3. `FloatingToolWheel`
Implements the virtual scroll wheel row in the drawer. Holding the upper or lower half repeatedly dispatches scroll ticks with a dead zone around the center, addressing the old overlay's repeated wheel gesture behavior.

4. `FloatingToolInputConsumePatches`
Consumes clicks that hit the floating tools UI before the base game sees them, implements lock mode by swallowing game clicks while still allowing cursor movement, and emulates right-click touch mode by swallowing the Android left-click stream and injecting right-button events. This addresses the symptom where a game-drawn overlay would otherwise click underlying cards, buttons, or screens while using the tools.

## Icon assets

The drawer icon set is composed and recolored for this mod. Ctrl, Shift, Tab, Alt, and wheel are local Pillow drawings. Keyboard and mouse symbols are based on Game-icons.net icons by Delapouite, and the lock symbol is based on a Game-icons.net icon by Lorc. Game-icons.net icons are licensed under CC BY 3.0; source attribution is also included in `amethystFloatingTools/images/tools/ATTRIBUTION.txt`.

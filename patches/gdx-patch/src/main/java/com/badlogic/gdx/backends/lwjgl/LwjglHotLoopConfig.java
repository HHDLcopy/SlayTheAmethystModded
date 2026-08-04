package com.badlogic.gdx.backends.lwjgl;

/** Resolved-once view of the system properties that the LWJGL main loop consults every frame.
 *
 * Every property below is published by the launcher as a {@code -D} JVM argument before the game
 * classes load, and nothing in the runtime calls {@code System.setProperty} for them afterwards, so
 * re-reading them per frame only pays for repeated synchronized {@code Hashtable} lookups on
 * {@code System.getProperties()}. Resolving them into {@code static final} fields lets the JIT fold
 * the checks away, and mirrors the existing pattern in {@code GLTexture} / {@code SpriteBatch}.
 *
 * Values that depend on runtime state (GLES context activity, {@code Display} metrics) are
 * deliberately <em>not</em> cached here; only the property parse result is. Callers combine the
 * cached override with the live context query. */
final class LwjglHotLoopConfig {
	static final String FORCE_DEFAULT_FBO_PROP = "amethyst.lwjgl.force_default_framebuffer";
	static final String DEFAULT_FBO_REBIND_CACHE_PROP = "amethyst.lwjgl.default_framebuffer_rebind_cache";
	static final String POST_RENDER_CLEAR_PROP = "amethyst.lwjgl.diag.post_render_clear";
	static final String VIRTUAL_WIDTH_PROP = "amethyst.gdx.virtual_width";
	static final String VIRTUAL_HEIGHT_PROP = "amethyst.gdx.virtual_height";
	static final String GLFWSTUB_PHYSICAL_WIDTH_PROP = "glfwstub.physicalWidth";
	static final String GLFWSTUB_PHYSICAL_HEIGHT_PROP = "glfwstub.physicalHeight";
	static final String GLOBAL_ATLAS_FILTER_COMPAT_PROP = "amethyst.gdx.global_atlas_filter_compat";
	static final String RUNTIME_TEXTURE_COMPAT_PROP = "amethyst.gdx.runtime_texture_compat";
	static final String RUNTIME_TEXTURE_COMPAT_PERIODIC_SCAN_PROP = "amethyst.gdx.runtime_texture_compat_periodic_scan";
	static final String GLOBAL_TEXTURE_COMPAT_VERBOSE_PROP = "amethyst.gdx.global_texture_compat_verbose";
	static final String GPU_RESOURCE_DIAG_ENABLED_PROP = "amethyst.gdx.gpu_resource_diag";
	static final String GPU_RESOURCE_SUMMARY_ENABLED_PROP = "amethyst.gdx.gpu_resource_summary";

	/** {@code null} when the property is absent, i.e. when the caller must fall back to the GLES
	 * context default. */
	static final Boolean FORCE_DEFAULT_FRAMEBUFFER_OVERRIDE =
		parseForceDefaultFramebuffer(System.getProperty(FORCE_DEFAULT_FBO_PROP));
	static final boolean DEFAULT_FBO_REBIND_CACHE_ENABLED =
		parseBoolean(System.getProperty(DEFAULT_FBO_REBIND_CACHE_PROP), true);
	static final boolean POST_RENDER_CLEAR_ENABLED = Boolean.getBoolean(POST_RENDER_CLEAR_PROP);
	/** {@code 0} means "not configured"; the caller derives the value from {@code Display} instead.
	 *
	 * These are the launch-time values only. They describe the surface as it existed when the JVM
	 * started, so anything that can change while the game runs — notably entering, resizing or
	 * leaving a multi-window/freeform window — must not be resolved from them. Callers should go
	 * through {@link #physicalWidth()} / {@link #virtualWidth()}, which prefer the live size the
	 * Android side publishes and only fall back to these constants. */
	static final int PHYSICAL_WIDTH_OVERRIDE = parsePositiveInt(System.getProperty(GLFWSTUB_PHYSICAL_WIDTH_PROP));
	static final int PHYSICAL_HEIGHT_OVERRIDE = parsePositiveInt(System.getProperty(GLFWSTUB_PHYSICAL_HEIGHT_PROP));
	static final int VIRTUAL_WIDTH_OVERRIDE = parsePositiveInt(System.getProperty(VIRTUAL_WIDTH_PROP));
	static final int VIRTUAL_HEIGHT_OVERRIDE = parsePositiveInt(System.getProperty(VIRTUAL_HEIGHT_PROP));

	/** Aspect ratio implied by the launch-time virtual resolution, or {@code 0} when unconfigured.
	 * Used to keep a fixed-resolution mode's shape after a live resize. */
	private static final float LAUNCH_VIRTUAL_ASPECT =
		(VIRTUAL_WIDTH_OVERRIDE > 0 && VIRTUAL_HEIGHT_OVERRIDE > 0)
			? (float)VIRTUAL_WIDTH_OVERRIDE / (float)VIRTUAL_HEIGHT_OVERRIDE
			: 0f;

	private static volatile boolean liveSizeBridgeUnavailable = false;

	/** Live physical surface width, falling back to the launch-time override. */
	static int physicalWidth () {
		int live = nativePhysicalWidth();
		if (live > 0) return live;
		return PHYSICAL_WIDTH_OVERRIDE;
	}

	/** Live physical surface height, falling back to the launch-time override. */
	static int physicalHeight () {
		int live = nativePhysicalHeight();
		if (live > 0) return live;
		return PHYSICAL_HEIGHT_OVERRIDE;
	}

	/** Virtual (render target) width for the current surface size.
	 *
	 * With no launch-time override this is simply the live physical width. With one, the configured
	 * render shape is re-fitted into the live surface so a resize keeps the intended aspect ratio
	 * instead of staying frozen at the startup resolution. */
	static int virtualWidth () {
		if (VIRTUAL_WIDTH_OVERRIDE <= 0) return 0;
		int livePhysicalWidth = nativePhysicalWidth();
		int livePhysicalHeight = nativePhysicalHeight();
		if (livePhysicalWidth <= 0 || livePhysicalHeight <= 0) return VIRTUAL_WIDTH_OVERRIDE;
		return fittedVirtualSize(livePhysicalWidth, livePhysicalHeight, true);
	}

	/** Virtual (render target) height for the current surface size. */
	static int virtualHeight () {
		if (VIRTUAL_HEIGHT_OVERRIDE <= 0) return 0;
		int livePhysicalWidth = nativePhysicalWidth();
		int livePhysicalHeight = nativePhysicalHeight();
		if (livePhysicalWidth <= 0 || livePhysicalHeight <= 0) return VIRTUAL_HEIGHT_OVERRIDE;
		return fittedVirtualSize(livePhysicalWidth, livePhysicalHeight, false);
	}

	/** Fits {@link #LAUNCH_VIRTUAL_ASPECT} inside the live surface, never upscaling past it. */
	private static int fittedVirtualSize (int livePhysicalWidth, int livePhysicalHeight, boolean wantWidth) {
		return fitVirtualSize(
			livePhysicalWidth,
			livePhysicalHeight,
			VIRTUAL_WIDTH_OVERRIDE,
			VIRTUAL_HEIGHT_OVERRIDE,
			LAUNCH_VIRTUAL_ASPECT,
			wantWidth
		);
	}

	/** Pure form of {@link #fittedVirtualSize} so the resize contract is unit testable.
	 *
	 * @param launchAspect aspect ratio to preserve, or {@code <= 0} to just clamp to the surface.
	 * @param wantWidth {@code true} to return the fitted width, {@code false} for the height. */
	static int fitVirtualSize (
		int livePhysicalWidth,
		int livePhysicalHeight,
		int launchVirtualWidth,
		int launchVirtualHeight,
		float launchAspect,
		boolean wantWidth
	) {
		if (launchAspect <= 0f) {
			return Math.max(1, wantWidth ? livePhysicalWidth : livePhysicalHeight);
		}
		int width = livePhysicalWidth;
		int height = Math.max(1, (int)(width / launchAspect));
		if (height > livePhysicalHeight) {
			height = livePhysicalHeight;
			width = Math.max(1, (int)(height * launchAspect));
		}
		// Never render larger than the launch-time budget; that value already accounts for the
		// configured render scale and the fixed-resolution modes.
		if (launchVirtualWidth > 0) width = Math.min(width, launchVirtualWidth);
		if (launchVirtualHeight > 0) height = Math.min(height, launchVirtualHeight);
		return Math.max(1, wantWidth ? width : height);
	}

	private static int nativePhysicalWidth () {
		if (liveSizeBridgeUnavailable) return 0;
		try {
			return org.lwjgl.glfw.CallbackBridge.nativeGetPhysicalWidth();
		} catch (Throwable ignored) {
			liveSizeBridgeUnavailable = true;
			return 0;
		}
	}

	private static int nativePhysicalHeight () {
		if (liveSizeBridgeUnavailable) return 0;
		try {
			return org.lwjgl.glfw.CallbackBridge.nativeGetPhysicalHeight();
		} catch (Throwable ignored) {
			liveSizeBridgeUnavailable = true;
			return 0;
		}
	}
	static final boolean GLOBAL_ATLAS_FILTER_COMPAT_ENABLED =
		parseBoolean(System.getProperty(GLOBAL_ATLAS_FILTER_COMPAT_PROP), true);
	static final boolean RUNTIME_TEXTURE_COMPAT_ENABLED =
		parseBoolean(System.getProperty(RUNTIME_TEXTURE_COMPAT_PROP), false);
	static final boolean RUNTIME_TEXTURE_COMPAT_PERIODIC_SCAN_ENABLED =
		parseBoolean(System.getProperty(RUNTIME_TEXTURE_COMPAT_PERIODIC_SCAN_PROP), false);
	static final boolean GLOBAL_TEXTURE_COMPAT_VERBOSE_ENABLED =
		parseBoolean(System.getProperty(GLOBAL_TEXTURE_COMPAT_VERBOSE_PROP), false);
	static final boolean GPU_RESOURCE_SUMMARY_LOG_ENABLED =
		parseBoolean(System.getProperty(GPU_RESOURCE_DIAG_ENABLED_PROP), false)
			|| parseBoolean(System.getProperty(GPU_RESOURCE_SUMMARY_ENABLED_PROP), false);

	private LwjglHotLoopConfig () {
	}

	/** Mirrors the historical {@code readBooleanSystemProperty} contract: unrecognised and blank
	 * values fall back to {@code defaultValue} instead of parsing as {@code false}. */
	static boolean parseBoolean (String raw, boolean defaultValue) {
		if (raw == null) return defaultValue;
		raw = raw.trim();
		if (raw.length() == 0) return defaultValue;
		if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "off".equalsIgnoreCase(raw)) return false;
		if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "on".equalsIgnoreCase(raw)) return true;
		return defaultValue;
	}

	/** The force-default-FBO switch is opt-out rather than opt-in: any present value that is not an
	 * explicit off token enables it, including a blank one. */
	static Boolean parseForceDefaultFramebuffer (String raw) {
		if (raw == null) return null;
		raw = raw.trim();
		boolean disabled = "0".equals(raw) || "false".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw);
		return disabled ? Boolean.FALSE : Boolean.TRUE;
	}

	/** Returns {@code 0} for absent, non-numeric, zero and negative values. */
	static int parsePositiveInt (String raw) {
		if (raw == null) return 0;
		try {
			int value = Integer.parseInt(raw.trim());
			return value > 0 ? value : 0;
		} catch (Throwable ignored) {
			return 0;
		}
	}
}

package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.GpuResourceGuardianMode
import io.stamethyst.config.LauncherConfig

object CompatibilitySettings {
    @JvmStatic
    fun resetToDefaults(context: Context) {
        setGlobalAtlasFilterCompatEnabled(context, true)
        setModManifestRootCompatEnabled(context, true)
        setFrierenModCompatEnabled(context, true)
        setDownfallImportCompatEnabled(context, true)
        setVupShionModCompatEnabled(context, true)
        setChaofanModCompatEnabled(context, true)
        setJacketNoAnoKoModCompatEnabled(
            context,
            LauncherConfig.DEFAULT_JACKET_NO_ANO_KO_MOD_COMPAT_ENABLED
        )
        setFragmentShaderPrecisionCompatEnabled(
            context,
            LauncherConfig.DEFAULT_FRAGMENT_SHADER_PRECISION_COMPAT_ENABLED
        )
        setRuntimeTextureCompatEnabled(context, false)
        setMainMenuPreviewReuseCompatEnabled(
            context,
            LauncherConfig.DEFAULT_MAIN_MENU_PREVIEW_REUSE_COMPAT_ENABLED
        )
        setRoomContextHandLayoutRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_ROOM_CONTEXT_HAND_LAYOUT_RESCUE_COMPAT_ENABLED
        )
        setRoomTransitionRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_ROOM_TRANSITION_RESCUE_COMPAT_ENABLED
        )
        setEventRoomRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_EVENT_ROOM_RESCUE_COMPAT_ENABLED
        )
        setShopRoomRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_SHOP_ROOM_RESCUE_COMPAT_ENABLED
        )
        setBaseModSaveLoadRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_BASEMOD_SAVE_LOAD_RESCUE_COMPAT_ENABLED
        )
        setRelicEnterRoomRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_RELIC_ENTER_ROOM_RESCUE_COMPAT_ENABLED
        )
        setDungeonRenderRoomContextRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_COMPAT_ENABLED
        )
        setPowerIconRenderRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_POWER_ICON_RENDER_RESCUE_COMPAT_ENABLED
        )
        setBaseModCustomMonsterRenderRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_COMPAT_ENABLED
        )
        setNonCombatPlayerRenderRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_NON_COMBAT_PLAYER_RENDER_RESCUE_COMPAT_ENABLED
        )
        setCardTooltipKeywordRescueCompatEnabled(
            context,
            LauncherConfig.DEFAULT_CARD_TOOLTIP_KEYWORD_RESCUE_COMPAT_ENABLED
        )
        setNativeTouchscreenAllowlistCompatEnabled(
            context,
            LauncherConfig.DEFAULT_NATIVE_TOUCHSCREEN_ALLOWLIST_COMPAT_ENABLED
        )
        setLargeTextureDownscaleCompatEnabled(
            context,
            LauncherConfig.DEFAULT_LARGE_TEXTURE_DOWNSCALE_COMPAT_ENABLED
        )
        setTextureResidencyManagerCompatEnabled(
            context,
            LauncherConfig.DEFAULT_TEXTURE_RESIDENCY_MANAGER_COMPAT_ENABLED
        )
        saveTexturePressureDownscaleDivisor(
            context,
            LauncherConfig.DEFAULT_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR
        )
        LauncherConfig.resetGpuResourceGuardianMode(context)
        setForceLinearMipmapFilterEnabled(context, true)
        setHinaCharacterRenderCompatEnabled(
            context,
            LauncherConfig.DEFAULT_HINA_CHARACTER_RENDER_COMPAT_ENABLED
        )
        setNonRenderableFboFormatCompatEnabled(context, true)
        setAndroidLwjglFramePacingCompatEnabled(
            context,
            LauncherConfig.DEFAULT_ANDROID_LWJGL_FRAME_PACING_COMPAT_ENABLED
        )
        setLwjglHotLoopNoopTrimCompatEnabled(
            context,
            LauncherConfig.DEFAULT_LWJGL_HOT_LOOP_NOOP_TRIM_COMPAT_ENABLED
        )
        setDefaultFramebufferFastRebindCompatEnabled(
            context,
            LauncherConfig.DEFAULT_FRAMEBUFFER_FAST_REBIND_COMPAT_ENABLED
        )
        setNativePreSwapPacingCompatEnabled(
            context,
            LauncherConfig.DEFAULT_NATIVE_PRE_SWAP_PACING_COMPAT_ENABLED
        )
        setEglSwapIntervalPacingCompatEnabled(
            context,
            LauncherConfig.DEFAULT_EGL_SWAP_INTERVAL_PACING_COMPAT_ENABLED
        )
        setFboManagerCompatEnabled(context, LauncherConfig.DEFAULT_FBO_MANAGER_COMPAT_ENABLED)
        setFboIdleReclaimCompatEnabled(context, LauncherConfig.DEFAULT_FBO_IDLE_RECLAIM_COMPAT_ENABLED)
        setFboPressureDownscaleCompatEnabled(
            context,
            LauncherConfig.DEFAULT_FBO_PRESSURE_DOWNSCALE_COMPAT_ENABLED
        )
        setRuntimeDownscaleOrdinaryTexturesEnabled(
            context,
            LauncherConfig.DEFAULT_RUNTIME_DOWNSCALE_ORDINARY_TEXTURES_ENABLED
        )
        setRuntimeDownscaleTextureAtlasPagesEnabled(
            context,
            LauncherConfig.DEFAULT_RUNTIME_DOWNSCALE_TEXTURE_ATLAS_PAGES_QUALITY
        )
        setRuntimeDownscaleSpineTexturesEnabled(
            context,
            LauncherConfig.DEFAULT_RUNTIME_DOWNSCALE_SPINE_TEXTURES_ENABLED
        )
        setRuntimeDownscaleOffscreenFrameBuffersEnabled(
            context,
            LauncherConfig.DEFAULT_RUNTIME_DOWNSCALE_OFFSCREEN_FRAME_BUFFERS_ENABLED
        )
        setImportDownscaleSpineAtlasPagesEnabled(
            context,
            LauncherConfig.DEFAULT_IMPORT_DOWNSCALE_SPINE_ATLAS_PAGES_ENABLED
        )
        setImportDownscaleOrdinaryAtlasPagesEnabled(
            context,
            LauncherConfig.DEFAULT_IMPORT_DOWNSCALE_ORDINARY_ATLAS_PAGES_ENABLED
        )
    }

    @JvmStatic
    fun isGlobalAtlasFilterCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isGlobalAtlasFilterCompatEnabled(context)
    }

    @JvmStatic
    fun setGlobalAtlasFilterCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setGlobalAtlasFilterCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isModManifestRootCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isModManifestRootCompatEnabled(context)
    }

    @JvmStatic
    fun setModManifestRootCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setModManifestRootCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isFrierenModCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isFrierenModCompatEnabled(context)
    }

    @JvmStatic
    fun setFrierenModCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setFrierenModCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isDownfallImportCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isDownfallImportCompatEnabled(context)
    }

    @JvmStatic
    fun setDownfallImportCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setDownfallImportCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isVupShionModCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isVupShionModCompatEnabled(context)
    }

    @JvmStatic
    fun setVupShionModCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setVupShionModCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isChaofanModCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isChaofanModCompatEnabled(context)
    }

    @JvmStatic
    fun setChaofanModCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setChaofanModCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isJacketNoAnoKoModCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isJacketNoAnoKoModCompatEnabled(context)
    }

    @JvmStatic
    fun setJacketNoAnoKoModCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setJacketNoAnoKoModCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isFragmentShaderPrecisionCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isFragmentShaderPrecisionCompatEnabled(context)
    }

    @JvmStatic
    fun setFragmentShaderPrecisionCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setFragmentShaderPrecisionCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isRuntimeTextureCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isRuntimeTextureCompatEnabled(context)
    }

    @JvmStatic
    fun setRuntimeTextureCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setRuntimeTextureCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isMainMenuPreviewReuseCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isMainMenuPreviewReuseCompatEnabled(context)
    }

    @JvmStatic
    fun setMainMenuPreviewReuseCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setMainMenuPreviewReuseCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isRoomContextHandLayoutRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isRoomContextHandLayoutRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setRoomContextHandLayoutRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setRoomContextHandLayoutRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isRoomTransitionRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isRoomTransitionRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setRoomTransitionRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setRoomTransitionRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isEventRoomRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isEventRoomRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setEventRoomRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setEventRoomRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isShopRoomRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isShopRoomRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setShopRoomRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setShopRoomRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isBaseModSaveLoadRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isBaseModSaveLoadRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setBaseModSaveLoadRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setBaseModSaveLoadRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isRelicEnterRoomRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isRelicEnterRoomRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setRelicEnterRoomRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setRelicEnterRoomRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isDungeonRenderRoomContextRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isDungeonRenderRoomContextRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setDungeonRenderRoomContextRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setDungeonRenderRoomContextRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isPowerIconRenderRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isPowerIconRenderRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setPowerIconRenderRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setPowerIconRenderRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isBaseModCustomMonsterRenderRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isBaseModCustomMonsterRenderRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setBaseModCustomMonsterRenderRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setBaseModCustomMonsterRenderRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isNonCombatPlayerRenderRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isNonCombatPlayerRenderRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setNonCombatPlayerRenderRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setNonCombatPlayerRenderRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isCardTooltipKeywordRescueCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isCardTooltipKeywordRescueCompatEnabled(context)
    }

    @JvmStatic
    fun setCardTooltipKeywordRescueCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setCardTooltipKeywordRescueCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isNativeTouchscreenAllowlistCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isNativeTouchscreenAllowlistCompatEnabled(context)
    }

    @JvmStatic
    fun setNativeTouchscreenAllowlistCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setNativeTouchscreenAllowlistCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isLargeTextureDownscaleCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isLargeTextureDownscaleCompatEnabled(context)
    }

    @JvmStatic
    fun setLargeTextureDownscaleCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setLargeTextureDownscaleCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isTextureResidencyManagerCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isTextureResidencyManagerCompatEnabled(context)
    }

    @JvmStatic
    fun setTextureResidencyManagerCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setTextureResidencyManagerCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun readTexturePressureDownscaleDivisor(context: Context): Int {
        return LauncherConfig.readTexturePressureDownscaleDivisor(context)
    }

    @JvmStatic
    fun saveTexturePressureDownscaleDivisor(context: Context, divisor: Int) {
        LauncherConfig.saveTexturePressureDownscaleDivisor(context, divisor)
    }

    @JvmStatic
    fun readGpuResourceGuardianMode(context: Context): GpuResourceGuardianMode {
        return LauncherConfig.readGpuResourceGuardianMode(context)
    }

    @JvmStatic
    fun saveGpuResourceGuardianMode(context: Context, mode: GpuResourceGuardianMode) {
        LauncherConfig.saveGpuResourceGuardianMode(context, mode)
    }

    @JvmStatic
    fun isGpuResourceGuardianPressureDownscaleEnabled(context: Context): Boolean {
        return LauncherConfig.isGpuResourceGuardianPressureDownscaleEnabled(context)
    }

    @JvmStatic
    fun setGpuResourceGuardianPressureDownscaleEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setGpuResourceGuardianPressureDownscaleEnabled(context, enabled)
    }

    @JvmStatic
    fun isForceLinearMipmapFilterEnabled(context: Context): Boolean {
        return LauncherConfig.isForceLinearMipmapFilterEnabled(context)
    }

    @JvmStatic
    fun setForceLinearMipmapFilterEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setForceLinearMipmapFilterEnabled(context, enabled)
    }

    @JvmStatic
    fun isHinaCharacterRenderCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isHinaCharacterRenderCompatEnabled(context)
    }

    @JvmStatic
    fun setHinaCharacterRenderCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setHinaCharacterRenderCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isNonRenderableFboFormatCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isNonRenderableFboFormatCompatEnabled(context)
    }

    @JvmStatic
    fun setNonRenderableFboFormatCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setNonRenderableFboFormatCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isAndroidLwjglFramePacingCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isAndroidLwjglFramePacingCompatEnabled(context)
    }

    @JvmStatic
    fun setAndroidLwjglFramePacingCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setAndroidLwjglFramePacingCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isLwjglHotLoopNoopTrimCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isLwjglHotLoopNoopTrimCompatEnabled(context)
    }

    @JvmStatic
    fun setLwjglHotLoopNoopTrimCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setLwjglHotLoopNoopTrimCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isDefaultFramebufferFastRebindCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isDefaultFramebufferFastRebindCompatEnabled(context)
    }

    @JvmStatic
    fun setDefaultFramebufferFastRebindCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setDefaultFramebufferFastRebindCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isNativePreSwapPacingCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isNativePreSwapPacingCompatEnabled(context)
    }

    @JvmStatic
    fun setNativePreSwapPacingCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setNativePreSwapPacingCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isEglSwapIntervalPacingCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isEglSwapIntervalPacingCompatEnabled(context)
    }

    @JvmStatic
    fun setEglSwapIntervalPacingCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setEglSwapIntervalPacingCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isFboManagerCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isFboManagerCompatEnabled(context)
    }

    @JvmStatic
    fun setFboManagerCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setFboManagerCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isFboIdleReclaimCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isFboIdleReclaimCompatEnabled(context)
    }

    @JvmStatic
    fun setFboIdleReclaimCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setFboIdleReclaimCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun isFboPressureDownscaleCompatEnabled(context: Context): Boolean {
        return LauncherConfig.isFboPressureDownscaleCompatEnabled(context)
    }

    @JvmStatic
    fun setFboPressureDownscaleCompatEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setFboPressureDownscaleCompatEnabled(context, enabled)
    }

    @JvmStatic
    fun readRuntimeDownscaleMaterialPolicy(context: Context): RuntimeDownscaleMaterialPolicy {
        return LauncherConfig.readRuntimeDownscaleMaterialPolicy(context)
    }

    @JvmStatic
    fun setRuntimeDownscaleOrdinaryTexturesEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setRuntimeDownscaleOrdinaryTexturesEnabled(context, enabled)
    }

    @JvmStatic
    fun setRuntimeDownscaleTextureAtlasPagesEnabled(
        context: Context,
        quality: RuntimeTextureAtlasDownscaleQuality
    ) {
        LauncherConfig.setRuntimeDownscaleTextureAtlasPagesQuality(context, quality)
    }

    @JvmStatic
    fun setRuntimeDownscaleSpineTexturesEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setRuntimeDownscaleSpineTexturesEnabled(context, enabled)
    }

    @JvmStatic
    fun setRuntimeDownscaleOffscreenFrameBuffersEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setRuntimeDownscaleOffscreenFrameBuffersEnabled(context, enabled)
    }

    @JvmStatic
    fun readImportDownscaleMaterialPolicy(context: Context): ImportDownscaleMaterialPolicy {
        return LauncherConfig.readImportDownscaleMaterialPolicy(context)
    }

    @JvmStatic
    fun setImportDownscaleSpineAtlasPagesEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setImportDownscaleSpineAtlasPagesEnabled(context, enabled)
    }

    @JvmStatic
    fun setImportDownscaleOrdinaryAtlasPagesEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setImportDownscaleOrdinaryAtlasPagesEnabled(context, enabled)
    }
}

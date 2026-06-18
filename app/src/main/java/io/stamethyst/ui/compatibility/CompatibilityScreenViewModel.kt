package io.stamethyst.ui.compatibility

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.RuntimeTextureAtlasDownscaleQuality

@Stable
class CompatibilityScreenViewModel : ViewModel() {
    data class UiState(
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val globalAtlasFilterCompatEnabled: Boolean = true,
        val modManifestRootCompatEnabled: Boolean = true,
        val frierenModCompatEnabled: Boolean = true,
        val downfallImportCompatEnabled: Boolean = true,
        val vupShionModCompatEnabled: Boolean = true,
        val fragmentShaderPrecisionCompatEnabled: Boolean = true,
        val runtimeTextureCompatEnabled: Boolean = false,
        val mainMenuPreviewReuseCompatEnabled: Boolean = true,
        val roomContextHandLayoutRescueCompatEnabled: Boolean = true,
        val roomTransitionRescueCompatEnabled: Boolean = true,
        val eventRoomRescueCompatEnabled: Boolean = true,
        val shopRoomRescueCompatEnabled: Boolean = true,
        val baseModSaveLoadRescueCompatEnabled: Boolean = true,
        val relicEnterRoomRescueCompatEnabled: Boolean = true,
        val dungeonRenderRoomContextRescueCompatEnabled: Boolean = true,
        val powerIconRenderRescueCompatEnabled: Boolean = true,
        val baseModCustomMonsterRenderRescueCompatEnabled: Boolean = true,
        val nonCombatPlayerRenderRescueCompatEnabled: Boolean = true,
        val cardTooltipKeywordRescueCompatEnabled: Boolean = true,
        val nativeTouchscreenAllowlistCompatEnabled: Boolean = true,
        val largeTextureDownscaleCompatEnabled: Boolean = false,
        val textureResidencyManagerCompatEnabled: Boolean = false,
        val texturePressureDownscaleDivisor: Int = 2,
        val forceLinearMipmapFilterEnabled: Boolean = true,
        val hinaCharacterRenderCompatEnabled: Boolean = true,
        val nonRenderableFboFormatCompatEnabled: Boolean = true,
        val androidLwjglFramePacingCompatEnabled: Boolean = false,
        val lwjglHotLoopNoopTrimCompatEnabled: Boolean = false,
        val defaultFramebufferFastRebindCompatEnabled: Boolean = false,
        val nativePreSwapPacingCompatEnabled: Boolean = false,
        val eglSwapIntervalPacingCompatEnabled: Boolean = false,
        val fboManagerCompatEnabled: Boolean = false,
        val fboIdleReclaimCompatEnabled: Boolean = false,
        val fboPressureDownscaleCompatEnabled: Boolean = false,
        val runtimeDownscaleOrdinaryTexturesEnabled: Boolean = true,
        val runtimeDownscaleTextureAtlasPagesQuality: RuntimeTextureAtlasDownscaleQuality =
            RuntimeTextureAtlasDownscaleQuality.P1080,
        val runtimeDownscaleSpineTexturesEnabled: Boolean = false,
        val runtimeDownscaleOffscreenFrameBuffersEnabled: Boolean = true,
        val importDownscaleSpineAtlasPagesEnabled: Boolean = true,
        val importDownscaleOrdinaryAtlasPagesEnabled: Boolean = false
    )

    var uiState by mutableStateOf(UiState())
        private set

    fun refresh(host: Context) {
        val runtimeDownscalePolicy = CompatibilitySettings.readRuntimeDownscaleMaterialPolicy(host)
        val importDownscalePolicy = CompatibilitySettings.readImportDownscaleMaterialPolicy(host)
        uiState = uiState.copy(
            busy = false,
            busyMessage = null,
            globalAtlasFilterCompatEnabled = CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(host),
            modManifestRootCompatEnabled = CompatibilitySettings.isModManifestRootCompatEnabled(host),
            frierenModCompatEnabled = CompatibilitySettings.isFrierenModCompatEnabled(host),
            downfallImportCompatEnabled = CompatibilitySettings.isDownfallImportCompatEnabled(host),
            vupShionModCompatEnabled = CompatibilitySettings.isVupShionModCompatEnabled(host),
            fragmentShaderPrecisionCompatEnabled = CompatibilitySettings.isFragmentShaderPrecisionCompatEnabled(host),
            runtimeTextureCompatEnabled = CompatibilitySettings.isRuntimeTextureCompatEnabled(host),
            mainMenuPreviewReuseCompatEnabled = CompatibilitySettings.isMainMenuPreviewReuseCompatEnabled(host),
            roomContextHandLayoutRescueCompatEnabled =
                CompatibilitySettings.isRoomContextHandLayoutRescueCompatEnabled(host),
            roomTransitionRescueCompatEnabled =
                CompatibilitySettings.isRoomTransitionRescueCompatEnabled(host),
            eventRoomRescueCompatEnabled = CompatibilitySettings.isEventRoomRescueCompatEnabled(host),
            shopRoomRescueCompatEnabled = CompatibilitySettings.isShopRoomRescueCompatEnabled(host),
            baseModSaveLoadRescueCompatEnabled =
                CompatibilitySettings.isBaseModSaveLoadRescueCompatEnabled(host),
            relicEnterRoomRescueCompatEnabled =
                CompatibilitySettings.isRelicEnterRoomRescueCompatEnabled(host),
            dungeonRenderRoomContextRescueCompatEnabled =
                CompatibilitySettings.isDungeonRenderRoomContextRescueCompatEnabled(host),
            powerIconRenderRescueCompatEnabled =
                CompatibilitySettings.isPowerIconRenderRescueCompatEnabled(host),
            baseModCustomMonsterRenderRescueCompatEnabled =
                CompatibilitySettings.isBaseModCustomMonsterRenderRescueCompatEnabled(host),
            nonCombatPlayerRenderRescueCompatEnabled =
                CompatibilitySettings.isNonCombatPlayerRenderRescueCompatEnabled(host),
            cardTooltipKeywordRescueCompatEnabled =
                CompatibilitySettings.isCardTooltipKeywordRescueCompatEnabled(host),
            nativeTouchscreenAllowlistCompatEnabled =
                CompatibilitySettings.isNativeTouchscreenAllowlistCompatEnabled(host),
            largeTextureDownscaleCompatEnabled = CompatibilitySettings.isLargeTextureDownscaleCompatEnabled(host),
            textureResidencyManagerCompatEnabled = CompatibilitySettings.isTextureResidencyManagerCompatEnabled(host),
            texturePressureDownscaleDivisor = CompatibilitySettings.readTexturePressureDownscaleDivisor(host),
            forceLinearMipmapFilterEnabled = CompatibilitySettings.isForceLinearMipmapFilterEnabled(host),
            hinaCharacterRenderCompatEnabled = CompatibilitySettings.isHinaCharacterRenderCompatEnabled(host),
            nonRenderableFboFormatCompatEnabled = CompatibilitySettings.isNonRenderableFboFormatCompatEnabled(host),
            androidLwjglFramePacingCompatEnabled =
                CompatibilitySettings.isAndroidLwjglFramePacingCompatEnabled(host),
            lwjglHotLoopNoopTrimCompatEnabled =
                CompatibilitySettings.isLwjglHotLoopNoopTrimCompatEnabled(host),
            defaultFramebufferFastRebindCompatEnabled =
                CompatibilitySettings.isDefaultFramebufferFastRebindCompatEnabled(host),
            nativePreSwapPacingCompatEnabled =
                CompatibilitySettings.isNativePreSwapPacingCompatEnabled(host),
            eglSwapIntervalPacingCompatEnabled =
                CompatibilitySettings.isEglSwapIntervalPacingCompatEnabled(host),
            fboManagerCompatEnabled = CompatibilitySettings.isFboManagerCompatEnabled(host),
            fboIdleReclaimCompatEnabled = CompatibilitySettings.isFboIdleReclaimCompatEnabled(host),
            fboPressureDownscaleCompatEnabled = CompatibilitySettings.isFboPressureDownscaleCompatEnabled(host),
            runtimeDownscaleOrdinaryTexturesEnabled = runtimeDownscalePolicy.ordinaryTextures,
            runtimeDownscaleTextureAtlasPagesQuality = runtimeDownscalePolicy.textureAtlasPages,
            runtimeDownscaleSpineTexturesEnabled = runtimeDownscalePolicy.spineTextures,
            runtimeDownscaleOffscreenFrameBuffersEnabled = runtimeDownscalePolicy.offscreenFrameBuffers,
            importDownscaleSpineAtlasPagesEnabled = importDownscalePolicy.spineAtlasPages,
            importDownscaleOrdinaryAtlasPagesEnabled = importDownscalePolicy.ordinaryAtlasPages
        )
    }

    fun onGlobalAtlasFilterCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setGlobalAtlasFilterCompatEnabled(host, enabled)
        uiState = uiState.copy(globalAtlasFilterCompatEnabled = enabled)
    }

    fun onModManifestRootCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setModManifestRootCompatEnabled(host, enabled)
        uiState = uiState.copy(modManifestRootCompatEnabled = enabled)
    }

    fun onFrierenModCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setFrierenModCompatEnabled(host, enabled)
        uiState = uiState.copy(frierenModCompatEnabled = enabled)
    }

    fun onDownfallImportCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setDownfallImportCompatEnabled(host, enabled)
        uiState = uiState.copy(downfallImportCompatEnabled = enabled)
    }

    fun onVupShionModCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setVupShionModCompatEnabled(host, enabled)
        uiState = uiState.copy(vupShionModCompatEnabled = enabled)
    }

    fun onFragmentShaderPrecisionCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setFragmentShaderPrecisionCompatEnabled(host, enabled)
        uiState = uiState.copy(fragmentShaderPrecisionCompatEnabled = enabled)
    }

    fun onRuntimeTextureCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setRuntimeTextureCompatEnabled(host, enabled)
        uiState = uiState.copy(runtimeTextureCompatEnabled = enabled)
    }

    fun onMainMenuPreviewReuseCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setMainMenuPreviewReuseCompatEnabled(host, enabled)
        uiState = uiState.copy(mainMenuPreviewReuseCompatEnabled = enabled)
    }

    fun onNativeTouchscreenAllowlistCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setNativeTouchscreenAllowlistCompatEnabled(host, enabled)
        uiState = uiState.copy(nativeTouchscreenAllowlistCompatEnabled = enabled)
    }

    fun onRoomContextHandLayoutRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setRoomContextHandLayoutRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(roomContextHandLayoutRescueCompatEnabled = enabled)
    }

    fun onEventRoomRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setEventRoomRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(eventRoomRescueCompatEnabled = enabled)
    }

    fun onRoomTransitionRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setRoomTransitionRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(roomTransitionRescueCompatEnabled = enabled)
    }

    fun onShopRoomRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setShopRoomRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(shopRoomRescueCompatEnabled = enabled)
    }

    fun onBaseModSaveLoadRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setBaseModSaveLoadRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(baseModSaveLoadRescueCompatEnabled = enabled)
    }

    fun onRelicEnterRoomRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setRelicEnterRoomRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(relicEnterRoomRescueCompatEnabled = enabled)
    }

    fun onDungeonRenderRoomContextRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setDungeonRenderRoomContextRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(dungeonRenderRoomContextRescueCompatEnabled = enabled)
    }

    fun onPowerIconRenderRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setPowerIconRenderRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(powerIconRenderRescueCompatEnabled = enabled)
    }

    fun onBaseModCustomMonsterRenderRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setBaseModCustomMonsterRenderRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(baseModCustomMonsterRenderRescueCompatEnabled = enabled)
    }

    fun onNonCombatPlayerRenderRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setNonCombatPlayerRenderRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(nonCombatPlayerRenderRescueCompatEnabled = enabled)
    }

    fun onCardTooltipKeywordRescueCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setCardTooltipKeywordRescueCompatEnabled(host, enabled)
        uiState = uiState.copy(cardTooltipKeywordRescueCompatEnabled = enabled)
    }

    fun onLargeTextureDownscaleCompatToggled(host: Context, enabled: Boolean) {
        CompatibilitySettings.setLargeTextureDownscaleCompatEnabled(host, false)
        uiState = uiState.copy(largeTextureDownscaleCompatEnabled = false)
    }

    fun onTextureResidencyManagerCompatToggled(host: Context, enabled: Boolean) {
        CompatibilitySettings.setTextureResidencyManagerCompatEnabled(host, false)
        uiState = uiState.copy(textureResidencyManagerCompatEnabled = false)
    }

    fun onTexturePressureDownscaleDivisorChanged(host: Context, divisor: Int) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.saveTexturePressureDownscaleDivisor(host, divisor)
        uiState = uiState.copy(texturePressureDownscaleDivisor = divisor)
    }

    fun onForceLinearMipmapFilterToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setForceLinearMipmapFilterEnabled(host, enabled)
        uiState = uiState.copy(forceLinearMipmapFilterEnabled = enabled)
    }

    fun onHinaCharacterRenderCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setHinaCharacterRenderCompatEnabled(host, enabled)
        uiState = uiState.copy(hinaCharacterRenderCompatEnabled = enabled)
    }

    fun onNonRenderableFboFormatCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setNonRenderableFboFormatCompatEnabled(host, enabled)
        uiState = uiState.copy(nonRenderableFboFormatCompatEnabled = enabled)
    }

    fun onAndroidLwjglFramePacingCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setAndroidLwjglFramePacingCompatEnabled(host, enabled)
        uiState = uiState.copy(androidLwjglFramePacingCompatEnabled = enabled)
    }

    fun onLwjglHotLoopNoopTrimCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setLwjglHotLoopNoopTrimCompatEnabled(host, enabled)
        uiState = uiState.copy(lwjglHotLoopNoopTrimCompatEnabled = enabled)
    }

    fun onDefaultFramebufferFastRebindCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setDefaultFramebufferFastRebindCompatEnabled(host, enabled)
        uiState = uiState.copy(defaultFramebufferFastRebindCompatEnabled = enabled)
    }

    fun onNativePreSwapPacingCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setNativePreSwapPacingCompatEnabled(host, enabled)
        uiState = uiState.copy(nativePreSwapPacingCompatEnabled = enabled)
    }

    fun onEglSwapIntervalPacingCompatToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        CompatibilitySettings.setEglSwapIntervalPacingCompatEnabled(host, enabled)
        uiState = uiState.copy(eglSwapIntervalPacingCompatEnabled = enabled)
    }

    fun onFboManagerCompatToggled(host: Context, enabled: Boolean) {
        CompatibilitySettings.setFboManagerCompatEnabled(host, false)
        uiState = uiState.copy(fboManagerCompatEnabled = false)
    }

    fun onFboIdleReclaimCompatToggled(host: Context, enabled: Boolean) {
        CompatibilitySettings.setFboIdleReclaimCompatEnabled(host, false)
        uiState = uiState.copy(fboIdleReclaimCompatEnabled = false)
    }

    fun onFboPressureDownscaleCompatToggled(host: Context, enabled: Boolean) {
        CompatibilitySettings.setFboPressureDownscaleCompatEnabled(host, false)
        uiState = uiState.copy(fboPressureDownscaleCompatEnabled = false)
    }

    fun onRuntimeDownscaleOrdinaryTexturesToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) return
        CompatibilitySettings.setRuntimeDownscaleOrdinaryTexturesEnabled(host, enabled)
        uiState = uiState.copy(runtimeDownscaleOrdinaryTexturesEnabled = enabled)
    }

    fun onRuntimeDownscaleTextureAtlasPagesQualityChanged(
        host: Context,
        quality: RuntimeTextureAtlasDownscaleQuality
    ) {
        if (uiState.busy) return
        CompatibilitySettings.setRuntimeDownscaleTextureAtlasPagesEnabled(host, quality)
        uiState = uiState.copy(runtimeDownscaleTextureAtlasPagesQuality = quality)
    }

    fun onRuntimeDownscaleSpineTexturesToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) return
        CompatibilitySettings.setRuntimeDownscaleSpineTexturesEnabled(host, enabled)
        uiState = uiState.copy(runtimeDownscaleSpineTexturesEnabled = enabled)
    }

    fun onRuntimeDownscaleOffscreenFrameBuffersToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) return
        CompatibilitySettings.setRuntimeDownscaleOffscreenFrameBuffersEnabled(host, enabled)
        uiState = uiState.copy(runtimeDownscaleOffscreenFrameBuffersEnabled = enabled)
    }

    fun onImportDownscaleSpineAtlasPagesToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) return
        CompatibilitySettings.setImportDownscaleSpineAtlasPagesEnabled(host, enabled)
        uiState = uiState.copy(importDownscaleSpineAtlasPagesEnabled = enabled)
    }

    fun onImportDownscaleOrdinaryAtlasPagesToggled(host: Context, enabled: Boolean) {
        if (uiState.busy) return
        CompatibilitySettings.setImportDownscaleOrdinaryAtlasPagesEnabled(host, enabled)
        uiState = uiState.copy(importDownscaleOrdinaryAtlasPagesEnabled = enabled)
    }
}

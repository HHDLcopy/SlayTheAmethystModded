package io.stamethyst.ui.resources

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import io.stamethyst.backend.resources.RuntimeResourceProvider

object RuntimeUiResourcePaths {
    const val BOOT_OVERLAY_BACKGROUND_BRIGHT = "ui/boot_bright.png"
    const val BOOT_OVERLAY_BACKGROUND_DARK = "ui/boot_dark.png"
    const val UPDATE_DOWNLOAD_CHOICE_NOTICE = "ui/update_notice.png"
}

@Composable
fun RuntimeResourceImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1.0f,
    colorFilter: ColorFilter? = null
) {
    val bitmap = rememberRuntimeResourceImageBitmap(path) ?: return
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter
    )
}

@Composable
fun rememberRuntimeResourceImageBitmap(path: String): ImageBitmap? {
    val context = LocalContext.current
    val provider = remember(context) { RuntimeResourceProvider(context) }
    val contentVersion = provider.contentVersion(path)
    return remember(provider, path, contentVersion) {
        if (contentVersion == 0L) {
            null
        } else {
            runCatching {
                provider.open(path).use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
}

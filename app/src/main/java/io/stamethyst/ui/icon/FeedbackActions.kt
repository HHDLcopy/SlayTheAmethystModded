package io.stamethyst.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import io.stamethyst.ui.Icons

val Icons.Refresh: ImageVector
    get() {
        if (_refresh != null) return _refresh!!
        _refresh = ImageVector.Builder("Outlined.Refresh", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(20f, 11f); curveTo(19.5f, 7f, 16.1f, 4f, 12f, 4f); curveTo(7.6f, 4f, 4f, 7.6f, 4f, 12f); curveTo(4f, 16.4f, 7.6f, 20f, 12f, 20f); curveTo(15.1f, 20f, 17.8f, 18.2f, 19.1f, 15.6f); moveTo(20f, 5f); verticalLineTo(11f); horizontalLineTo(14f)
            }
        }.build()
        return _refresh!!
    }
private var _refresh: ImageVector? = null

val Icons.ArrowForward: ImageVector
    get() {
        if (_arrowForward != null) return _arrowForward!!
        _arrowForward = ImageVector.Builder("Filled.ArrowForward", 24.dp, 24.dp, 24f, 24f, autoMirror = true).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 4f); lineTo(10.6f, 5.4f); lineTo(16.2f, 11f); horizontalLineTo(4f); verticalLineTo(13f); horizontalLineTo(16.2f); lineTo(10.6f, 18.6f); lineTo(12f, 20f); lineTo(20f, 12f); close()
            }
        }.build()
        return _arrowForward!!
    }
private var _arrowForward: ImageVector? = null

val Icons.Pending: ImageVector
    get() {
        if (_pending != null) return _pending!!
        _pending = ImageVector.Builder("Outlined.Pending", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f); curveTo(7f, 3f, 3f, 7f, 3f, 12f); curveTo(3f, 17f, 7f, 21f, 12f, 21f); curveTo(17f, 21f, 21f, 17f, 21f, 12f); curveTo(21f, 7f, 17f, 3f, 12f, 3f); moveTo(12f, 7f); verticalLineTo(12f); lineTo(15f, 15f)
            }
        }.build()
        return _pending!!
    }
private var _pending: ImageVector? = null

val Icons.CheckCircle: ImageVector
    get() {
        if (_checkCircle != null) return _checkCircle!!
        _checkCircle = ImageVector.Builder("Outlined.CheckCircle", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f); curveTo(7f, 3f, 3f, 7f, 3f, 12f); curveTo(3f, 17f, 7f, 21f, 12f, 21f); curveTo(17f, 21f, 21f, 17f, 21f, 12f); curveTo(21f, 7f, 17f, 3f, 12f, 3f); moveTo(7.5f, 12f); lineTo(10.5f, 15f); lineTo(16.5f, 9f)
            }
        }.build()
        return _checkCircle!!
    }
private var _checkCircle: ImageVector? = null

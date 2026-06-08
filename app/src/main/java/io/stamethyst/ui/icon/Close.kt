/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.stamethyst.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import io.stamethyst.ui.Icons

val Icons.Close: ImageVector
    get() {
        if (_close != null) {
            return _close!!
        }
        _close = ImageVector.Builder(
            name = "Filled.Close",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                strokeLineWidth = 1f,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
            ) {
                moveTo(18.3f, 5.71f)
                lineTo(12.0f, 12.0f)
                lineToRelative(6.3f, 6.29f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(10.59f, 13.41f)
                lineTo(4.29f, 19.71f)
                lineTo(2.88f, 18.3f)
                lineTo(9.17f, 12.0f)
                lineTo(2.88f, 5.71f)
                lineTo(4.29f, 4.29f)
                lineTo(10.59f, 10.59f)
                lineTo(16.89f, 4.29f)
                lineToRelative(1.41f, 1.42f)
                close()
            }
        }.build()
        return _close!!
    }

private var _close: ImageVector? = null

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import io.stamethyst.ui.Icons

val Icons.Search: ImageVector
    get() {
        if (_search != null) {
            return _search!!
        }
        _search = ImageVector.Builder(
            name = "Outlined.Search",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineMiter = 1f,
            ) {
                moveTo(10.5f, 4.0f)
                curveTo(6.91f, 4.0f, 4.0f, 6.91f, 4.0f, 10.5f)
                curveTo(4.0f, 14.09f, 6.91f, 17.0f, 10.5f, 17.0f)
                curveTo(14.09f, 17.0f, 17.0f, 14.09f, 17.0f, 10.5f)
                curveTo(17.0f, 6.91f, 14.09f, 4.0f, 10.5f, 4.0f)
                close()
                moveTo(15.2f, 15.2f)
                lineTo(20.0f, 20.0f)
            }
        }.build()
        return _search!!
    }

private var _search: ImageVector? = null

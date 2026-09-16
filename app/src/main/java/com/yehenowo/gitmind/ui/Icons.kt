package com.yehenowo.gitmind.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.yehenowo.gitmind.R

// Material Symbols Rounded 图标字体:可变字体(FILL/GRAD/opsz/wght 四轴),
// 单文件适配任意尺寸与密度(替代 material-icons-extended 的整套位图 vector)。
// 码点由 fonts/make_icon_subset.py 从字体 liga 表解析生成,与官方 codepoints 表一致。
// 加图标:脚本 ICONS 列表加名字重跑,再在 MsGlyphs 补一行。

@OptIn(ExperimentalTextApi::class)
private val fontOutline = Font(
    R.font.material_symbols_rounded,
    variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 0f)),
)

@OptIn(ExperimentalTextApi::class)
private val fontFilled = Font(
    R.font.material_symbols_rounded,
    variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 1f)),
)

/** 图标名 -> 码点 */
val MsGlyphs = mapOf(
    "menu" to '\uE5D2',
    "settings" to '\uE8B8',
    "history" to '\uE8B3',
    "send" to '\uE163',
    "stop" to '\uE047',
    "add" to '\uE145',
    "auto_awesome" to '\uE65F',
    "file_upload" to '\uF09B',
    "file_download" to '\uF090',
    "palette" to '\uE40A',
    "folder_open" to '\uE2C8',
    "auto_stories" to '\uE666',
    "commit" to '\uEAF5',
    "close" to '\uE5CD',
    "arrow_back" to '\uE5C4',
    "visibility" to '\uE8F4',
    "visibility_off" to '\uE8F5',
)

/** MD3 标准图标:24sp outline 变体;fill=true 为填充变体(激活/按钮内场景) */
@Composable
fun MsIcon(
    name: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    tint: Color = LocalContentColor.current,
) {
    Text(
        text = MsGlyphs[name]?.toString() ?: "?",
        fontFamily = FontFamily(if (filled) fontFilled else fontOutline),
        fontSize = 24.sp,
        color = tint,
        maxLines = 1,
        modifier = modifier.semantics { contentDescription = name },
    )
}

/** 密钥输入框:默认圆点遮蔽,尾缀眼睛图标切换可见。 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "API 密钥",
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                MsIcon(if (visible) "visibility_off" else "visibility")
            }
        },
    )
}

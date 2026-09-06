package com.yehenowo.gitmind.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Material You 主题:Android 12+ 用系统动态取色(monet),低版本用用户设置的种子色。
// 深浅模式跟随设置(mode = system|light|dark)。

@Composable
fun GitMindTheme(mode: String, seedColor: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> {
            val seed = runCatching { Color(android.graphics.Color.parseColor(seedColor)) }
                .getOrElse { Color(0xFF6750A4) }
            if (dark) darkColorScheme(primary = seed) else lightColorScheme(primary = seed)
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
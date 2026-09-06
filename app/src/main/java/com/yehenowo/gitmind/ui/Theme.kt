package com.yehenowo.gitmind.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.materialkolor.rememberDynamicColorScheme

// Material You 主题(对齐 MD3 规范):
// - Android 12+:系统动态取色(monet),深浅两套 scheme 由系统按壁纸生成
// - 低版本:MaterialKolor 从种子色生成完整 tonal 方案
//   (深色模式 primary 取 tone 80 浅色变体,禁止拿浅色 primary 直接反用)
// - 系统栏图标外观跟随应用深浅模式,不跟系统
// - 窗口背景由 values(-night)/themes.xml 的 DayNight 主题承担,杜绝深色闪白

/** 预览/低配兜底的静态方案(MD3 官方示例基线色) */
private val LightFallback = lightColorScheme()
private val DarkFallback = darkColorScheme()

@Composable
fun GitMindTheme(mode: String, seedColor: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current

    val scheme: ColorScheme = when {
        // Android 12+:动态取色已自带完整深浅方案
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) androidx.compose.material3.dynamicDarkColorScheme(context)
            else androidx.compose.material3.dynamicLightColorScheme(context)
        // 低版本:从种子色生成 tonal 方案(深色自动取 tone 80,对比度合规)
        else -> {
            // 种子解析容错(非组合操作可 try);组合函数不能进 runCatching
            val seed = runCatching { Color(android.graphics.Color.parseColor(seedColor)) }
                .getOrElse { Color(0xFF6750A4) }
            rememberDynamicColorScheme(seedColor = seed, isDark = dark, isAmoled = false)
        }
    }

    // 系统栏图标外观跟随应用模式:深色界面 -> 浅色图标
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
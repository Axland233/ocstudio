package com.yehenowo.gitmind.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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

/** seed_color 的特殊值:跟随系统动态取色(Monet)。旧数据的 #RRGGBB 继续作为自定义色生效。 */
const val THEME_AUTO = "monet"

@Composable
fun GitMindTheme(mode: String, seedColor: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current

    // seed_color = "monet" 表示跟随系统动态取色;其他值为用户选定的种子色
    // (用户选色时所有系统版本都走 MaterialKolor,保证深浅色/取色行为一致)
    val useMonet = seedColor == THEME_AUTO

    val scheme: ColorScheme = when {
        useMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            // Android 12+ 跟随系统:动态取色自带完整深浅方案
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> {
            // 种子色方案:低版本唯一途径;高版本作为"自定义颜色"覆盖 monet
            // (深色自动取 tone 80,对比度合规)
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
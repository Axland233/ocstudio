package com.yehenowo.gitmind.data

import com.yehenowo.gitmind.core.APP_JSON
import com.yehenowo.gitmind.core.AppSettings
import java.io.File

// App 级配置管理(single JSON 文件,与 legacy settings.json 完全同格式)。
// 存于 filesDir/settings.json。

class SettingsStore(private val filesDir: File) {

    private fun settingsFile() = File(filesDir, "settings.json")

    /** 读取配置;文件不存在则返回默认(未引导) */
    fun load(): AppSettings = runCatching {
        APP_JSON.decodeFromString(AppSettings.serializer(), settingsFile().readText())
    }.getOrDefault(AppSettings())

    /** 保存配置(自动建目录) */
    fun save(settings: AppSettings) {
        filesDir.mkdirs()
        settingsFile().writeText(APP_JSON.encodeToString(AppSettings.serializer(), settings))
    }

    /** workspace 根目录:用户未选择时默认 filesDir/workspace */
    fun workspaceRoot(settings: AppSettings): File =
        settings.workspace_dir?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(filesDir, "workspace")
}
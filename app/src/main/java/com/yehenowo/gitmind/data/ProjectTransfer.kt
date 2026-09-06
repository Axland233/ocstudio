package com.yehenowo.gitmind.data

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.yehenowo.gitmind.core.APP_JSON
import com.yehenowo.gitmind.core.PROJECT_META
import com.yehenowo.gitmind.core.ProjectMeta
import java.io.File

// SAF 项目迁移:通过系统文件夹选择器整目录导入/导出工程(含 .git,保留提交历史)。
// 用户故事:朋友 A 把整个工程文件夹传给朋友 B,B 在 GitMind 里"导入工程"选中即可继续共创。
// ponytail: 不做压缩/增量,整目录树流式拷贝,可靠压倒一切。
// upgrade: 出现超大工程需求时再考虑 zip 打包。

/** 导入:treeUri 可以是工程目录本身(含 project.json),或包含多个工程的父目录。返回最后导入的工程名 */
fun importProject(context: Context, treeUri: Uri, workspaceRoot: File): String {
    val resolver = context.contentResolver
    fun readText(df: DocumentFile): String =
        resolver.openInputStream(df.uri)!!.bufferedReader().readText()

    val picked = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("无法访问所选文件夹")

    // 选中"工程目录本身"或"包含多个工程的父目录"都支持
    val candidates = mutableListOf<DocumentFile>()
    if (picked.findFile(PROJECT_META)?.exists() == true) {
        candidates.add(picked)
    } else {
        for (f in picked.listFiles()) {
            if (f.isDirectory && f.findFile(PROJECT_META)?.exists() == true) candidates.add(f)
        }
    }
    if (candidates.isEmpty()) {
        throw IllegalArgumentException("所选文件夹里没有 GitMind 工程(缺少 project.json)")
    }

    var last = ""
    for (src in candidates) {
        val meta = APP_JSON.decodeFromString(
            ProjectMeta.serializer(), readText(src.findFile(PROJECT_META)!!),
        )
        // 工程名以 project.json 为准(SAF 目录名可能不一致)
        val name = meta.name.ifBlank { src.name ?: "导入工程" }
        // 重名自动加后缀
        var dirName = name
        var i = 2
        while (File(workspaceRoot, dirName).exists()) { dirName = "$name-$i"; i++ }
        val dst = File(workspaceRoot, dirName)
        dst.mkdirs()
        copySafToLocal(resolver, src, dst)
        last = name
    }
    return last
}

/** SAF 目录树递归拷贝到本地 File 目录(含 .git 等隐藏目录) */
private fun copySafToLocal(resolver: ContentResolver, src: DocumentFile, dst: File) {
    dst.mkdirs()
    for (f in src.listFiles()) {
        if (f.isDirectory) {
            copySafToLocal(resolver, f, File(dst, f.name ?: "_"))
        } else {
            val name = f.name ?: continue
            resolver.openInputStream(f.uri)!!.use { input ->
                File(dst, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

/** 导出:把本地工程目录(含 .git)整体拷贝到所选 SAF 目标文件夹下的 <工程名>/ */
fun exportProject(context: Context, treeUri: Uri, projectDir: File): String {
    val resolver = context.contentResolver
    val target = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("无法访问所选文件夹")
    val copied = copyLocalToSaf(resolver, projectDir, target)
    return "已导出 $copied 个文件"
}

/** 本地 File 目录递归拷贝到 SAF 目录,返回拷贝的文件数 */
private fun copyLocalToSaf(resolver: ContentResolver, dir: File, parent: DocumentFile): Int {
    val dst = parent.findFile(dir.name) ?: parent.createDirectory(dir.name)
        ?: throw IllegalStateException("无法在目标位置创建目录 ${dir.name}")
    var copied = 0
    for (f in dir.listFiles()) {
        if (f.isDirectory) {
            copied += copyLocalToSaf(resolver, f, dst)
            continue
        }
        val mime = when (f.extension.lowercase()) {
            "md", "txt", "json" -> "text/plain"
            else -> "application/octet-stream"
        }
        val out = dst.createFile(mime, f.name) ?: continue
        resolver.openOutputStream(out.uri)!!.use { os ->
            f.inputStream().use { it.copyTo(os) }
        }
        copied++
    }
    return copied
}
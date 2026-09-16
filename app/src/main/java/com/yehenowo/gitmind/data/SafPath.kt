package com.yehenowo.gitmind.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

// SAF tree URI -> 本地文件系统路径。仅支持真实本地存储(内部/外部存储、SD 卡),
// 不支持云盘类 provider(那些无法给出 File 路径,返回 null 由调用方提示)。
// JGit 需要真实 File 路径才能读写仓库,所以项目文件夹功能必须落到本地路径。

/** 把 SAF tree URI 解析成本地目录路径;非本地存储返回 null */
fun safToRealPath(context: Context, treeUri: Uri, documentUri: Uri? = null): String? = runCatching {
    val docUri = documentUri ?: treeUri
    val docId = DocumentsContract.getTreeDocumentId(docUri)   // 形如 "primary:Docs/OC" 或 "XXXX-XXXX:Dir"
    val split = docId.split(':', limit = 2)
    if (split.size != 2) return@runCatching null
    val volume = split[0]
    val rel = split[1]

    val rootFile: File = when {
        volume == "primary" -> Environment.getExternalStorageDirectory()
        // 外置 SD 卡等二级卷:通过 StorageManager 反查真实挂载点
        else -> secondaryVolumeRoot(context, volume) ?: return@runCatching null
    }
    // "root" 表示选中的就是卷根
    val dir = if (rel.isBlank() || rel == "root") rootFile else File(rootFile, rel)
    if (!dir.isDirectory) null else dir.absolutePath
}.getOrNull()

/** 查询二级存储卷(如 SD 卡)的挂载根目录 */
private fun secondaryVolumeRoot(context: Context, volume: String): File? {
    val sm = context.getSystemService(android.os.storage.StorageManager::class.java) ?: return null
    for (storage in sm.storageVolumes) {
        if (!storage.isPrimary && storage.uuid != null && storage.uuid.equals(volume, ignoreCase = true)) {
            val f = storage.directory ?: return null
            return f
        }
    }
    return null
}

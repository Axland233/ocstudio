package com.yehenowo.gitmind.data

import com.yehenowo.gitmind.core.APP_JSON
import com.yehenowo.gitmind.core.ProjectInfo
import com.yehenowo.gitmind.core.ProjectMeta
import com.yehenowo.gitmind.core.SETTING_FILES
import com.yehenowo.gitmind.core.PROJECT_META
import java.io.File

// 工程(设定集)管理:每个工程 = workspace 下一个目录,
// 内含 5 个设定 md + project.json 元数据,目录本身是 git 仓库。
// 与 legacy projects.rs 同语义。

// ---------- 模板(与 legacy 逐字一致) ----------

private const val CORE_CARD_TPL = """# 核心卡

## 一句话世界观
(待定)

## 当前进度
(待定)

## 硬规则区(HARD RULES)
(本工程的非常规设定。AI 必须无条件遵守:禁止合理化、禁止遗忘、禁止软化)

## 最近更新记录
- (日期) 初始化工程
"""
private const val CHARACTERS_TPL = "# 人设\n\n(角色详细设定。每个角色一个 `## 角色名` 小节)\n"
private const val WORLD_TPL = "# 世界观\n\n(世界规则。**HARD RULES** 标注的规则最高优先级)\n"
private const val STORY_TPL = "# 剧情线\n\n## 主线进度\n\n## 已发生事件\n\n## 碎片剧情片段\n(未定型想法也记这里)\n"
private const val BRAINSTORM_TPL = "# 脑洞池\n\n(未定型想法缓存区,可\"转正\"合并到其他文件)\n"

fun templateFor(name: String): String = when (name) {
    "核心卡.md" -> CORE_CARD_TPL
    "人设.md" -> CHARACTERS_TPL
    "世界观.md" -> WORLD_TPL
    "剧情线.md" -> STORY_TPL
    "脑洞池.md" -> BRAINSTORM_TPL
    else -> ""
}

/** 进入 git 跟踪的全部文件 = 5 个设定文件 + project.json */
fun gitFiles(): List<String> = SETTING_FILES + PROJECT_META

private fun nowStr(): String = (System.currentTimeMillis() / 1000).toString()

/** 工程名合法性:长度 1..=60,禁止路径分隔符与 Windows 非法字符 */
fun validateName(name: String) {
    val n = name.trim()
    if (n.isEmpty()) throw IllegalArgumentException("项目名称不能为空")
    if (n.length > 60) throw IllegalArgumentException("项目名称过长(≤60 字符)")
    if (n.any { it in "/\\:*?\"<>|" }) throw IllegalArgumentException("项目名称包含非法字符")
}

fun readMeta(projectDir: File): ProjectInfo {
    val raw = File(projectDir, PROJECT_META).readText()
    val meta = APP_JSON.decodeFromString(ProjectMeta.serializer(), raw)
    return ProjectInfo(
        name = meta.name, desc = meta.desc, author = meta.author,
        created_at = meta.created_at, updated_at = meta.updated_at,
        path = projectDir.absolutePath,
        remote_url = meta.github.remote_url,
    )
}

private fun writeMeta(projectDir: File, meta: ProjectMeta) {
    File(projectDir, PROJECT_META).writeText(APP_JSON.encodeToString(ProjectMeta.serializer(), meta))
}

fun touchUpdatedAt(projectDir: File) {
    val raw = File(projectDir, PROJECT_META).readText()
    val meta = APP_JSON.decodeFromString(ProjectMeta.serializer(), raw)
    writeMeta(projectDir, meta.copy(updated_at = nowStr()))
}

/** 收集进入 git 的全部文件(5 个设定文件 + project.json)当前内容。commit_all 是全量快照语义。 */
fun collectGitFiles(dir: File): List<Pair<String, String>> =
    gitFiles().map { it to File(dir, it).readText() }

/** 新建工程:建目录 + 5 个模板 md + project.json + git init + 首 commit */
fun createProject(workspaceRoot: File, name: String, desc: String, author: String, git: JGitRepo): ProjectInfo {
    val n = name.trim()
    validateName(n)
    val projectDir = File(workspaceRoot, n)
    if (projectDir.exists()) throw IllegalArgumentException("项目「$n」已存在,请换一个名称或直接打开它")
    projectDir.mkdirs()

    for (file in SETTING_FILES) {
        File(projectDir, file).writeText(templateFor(file))
    }
    val now = nowStr()
    writeMeta(projectDir, ProjectMeta(
        name = n, desc = desc.trim(), author = author.trim(),
        created_at = now, updated_at = now,
    ))
    git.initRepo(projectDir)
    git.commitAll(projectDir, collectGitFiles(projectDir), "chore: 初始化设定集「$n」", author.trim())
    return readMeta(projectDir)
}

/** 列出 workspace 下所有工程(最近更新在前) */
fun listProjects(workspaceRoot: File): List<ProjectInfo> {
    if (!workspaceRoot.exists()) return emptyList()
    return workspaceRoot.listFiles { f -> f.isDirectory && File(f, PROJECT_META).exists() }
        ?.mapNotNull { runCatching { readMeta(it) }.getOrNull() }
        ?.sortedByDescending { it.updated_at }
        ?: emptyList()
}

/** 按名称打开工程 */
fun openProject(workspaceRoot: File, name: String): ProjectInfo {
    val dir = File(workspaceRoot, name)
    if (!File(dir, PROJECT_META).exists()) throw IllegalArgumentException("工程「$name」不存在")
    return readMeta(dir)
}

/** 读取工程全部设定文件 */
fun readFiles(workspaceRoot: File, name: String): List<Pair<String, String>> =
    SETTING_FILES.map { it to runCatching { File(File(workspaceRoot, name), it).readText() }.getOrDefault("") }

/** 设置工程的 GitHub 远程仓库地址(写入 project.json 并 commit)。token 不进工程文件。 */
fun setRemote(workspaceRoot: File, name: String, remoteUrl: String, author: String, git: JGitRepo): ProjectInfo {
    val dir = File(workspaceRoot, name)
    val raw = File(dir, PROJECT_META).readText()
    val meta = APP_JSON.decodeFromString(ProjectMeta.serializer(), raw)
    writeMeta(dir, meta.copy(
        github = meta.github.copy(remote_url = remoteUrl.trim()),
        updated_at = nowStr(),
    ))
    val msg = if (remoteUrl.trim().isEmpty()) "chore: 移除远程仓库配置" else "chore: 配置远程仓库"
    git.commitAll(dir, collectGitFiles(dir), msg, author)
    return readMeta(dir)
}
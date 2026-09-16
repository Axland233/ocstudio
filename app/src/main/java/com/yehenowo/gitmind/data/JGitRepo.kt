package com.yehenowo.gitmind.data

import com.yehenowo.gitmind.core.CommitInfo
import com.yehenowo.gitmind.core.DiffLine
import com.yehenowo.gitmind.core.FileDiff
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File

// git 引擎封装(JGit)。
// 与 legacy gitmod.rs 同语义:commit_all = 全量快照提交(blob->tree->commit->更新 HEAD),
// log / diff(含逐行)供 UI 展示。仓库结构扁平(根目录 5 md + project.json)。

class JGitRepo {

    /** 在 path 初始化一个非 bare 仓库 */
    fun initRepo(path: File) {
        Git.init().setDirectory(path).call()
    }

    /**
     * 把一批文件(文件名 + 内容)整体提交为一个 commit。
     * files 必须覆盖仓库内"应被跟踪"的全部文件(扁平、无目录)。
     * 返回新 commit 的短 hash(8 位,与 legacy 一致)。
     */
    fun commitAll(repoPath: File, files: List<Pair<String, String>>, message: String, authorName: String): String {
        Git.open(repoPath).use { git ->
            val repo = git.repository
            val inserter = repo.newObjectInserter()
            // 1) 每个文件写为 blob,组装 in-core DirCache(即 index)
            val builder = DirCache.newInCore()
            val dcBuilder = builder.builder()
            for ((name, content) in files) {
                val blobId = inserter.insert(Constants.OBJ_BLOB, content.toByteArray(Charsets.UTF_8))
                val entry = DirCacheEntry(name)
                entry.fileMode = FileMode.REGULAR_FILE
                entry.setObjectId(blobId)
                dcBuilder.add(entry)
            }
            dcBuilder.finish()
            // 2) 写 tree
            val treeId = builder.writeTree(inserter)
            // 3) 父提交 = 当前 HEAD(首 commit 无父)
            val headId = runCatching { repo.resolve("HEAD") }.getOrNull()
            // 4) 写 commit 并更新 HEAD(身份显式传入,不依赖 config/环境)
            val ident = PersonIdent(authorName.trim(), "${authorName.trim()}@gitmind.local")
            val commit = CommitBuilder()
            commit.author = ident
            commit.committer = ident
            commit.message = message
            commit.setTreeId(treeId)
            headId?.let { commit.setParentId(it) }
            val commitId = inserter.insert(commit)
            inserter.flush()
            // HEAD 是 symref:未出生分支(首个 commit 前)下直接 update HEAD 是静默 no-op,
            // 必须写 symref 指向的叶子 ref(refs/heads/master|main);分支已存在时等价
            val headRef = repo.exactRef("HEAD")
            val leaf = headRef?.takeIf { it.isSymbolic }?.target?.name ?: "HEAD"
            val refUpdate = repo.updateRef(leaf)
            refUpdate.setNewObjectId(commitId)
            // RefUpdate.Result 没有 isSuccessful():成功态 = NEW/FAST_FORWARD/FORCED(NO_CHANGE=指向未变,无害)
            val st = refUpdate.update()
            when (st) {
                org.eclipse.jgit.lib.RefUpdate.Result.NEW,
                org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD,
                org.eclipse.jgit.lib.RefUpdate.Result.FORCED,
                org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE -> Unit
                else -> throw IllegalStateException("git ref 更新失败: $st")
            }
            return commitId.abbreviate(8).name()
        }
    }

    /** 提交历史(新 -> 旧),limit 条 */
    fun log(repoPath: File, limit: Int = 50): List<CommitInfo> {
        Git.open(repoPath).use { git ->
            val head = runCatching { git.repository.resolve("HEAD") }.getOrNull() ?: return emptyList()
            val out = mutableListOf<CommitInfo>()
            for (c in git.log().add(head).call()) {
                if (out.size >= limit) break
                out.add(CommitInfo(
                    id = c.id.abbreviate(8).name(),
                    message = c.shortMessage.ifBlank { c.fullMessage.lines().firstOrNull() ?: "" },
                    author = c.authorIdent.name,
                    time_secs = c.commitTime.toLong(),
                ))
            }
            return out
        }
    }

    /**
     * 两个提交之间的完整 diff(文件级 + 逐行)。
     * old: 旧提交短 hash;null = 空树(查看首个提交的全部内容);new: 新提交短 hash。
     */
    fun diff(repoPath: File, old: String?, new: String): List<FileDiff> {
        Git.open(repoPath).use { git ->
            val repo = git.repository
            val walk = RevWalk(repo)
            val newCommit = walk.parseCommit(resolveId(repo, new))
            val oldCommit = old?.let { walk.parseCommit(resolveId(repo, it)) }

            val reader = walk.objectReader
            val tw = TreeWalk(reader)
            val oldTreeId = oldCommit?.tree?.id
            if (oldTreeId != null) tw.addTree(oldTreeId) else tw.addTree(EmptyTreeIterator())
            tw.addTree(newCommit.tree.id)
            tw.isRecursive = true

            val out = mutableListOf<FileDiff>()
            while (tw.next()) {
                val path = tw.pathString
                val oldOid = tw.getObjectId(0).takeIf { it != ObjectId.zeroId() }
                val newOid = tw.getObjectId(1).takeIf { it != ObjectId.zeroId() }
                if (oldOid == newOid) continue // 无变化
                val kind = when {
                    oldOid == null -> "A"
                    newOid == null -> "D"
                    else -> "M"
                }
                val oldText = oldOid?.let { String(repo.open(it).bytes, Charsets.UTF_8) } ?: ""
                val newText = newOid?.let { String(repo.open(it).bytes, Charsets.UTF_8) } ?: ""
                out.add(FileDiff(path, kind, lineDiff(oldText, newText)))
            }
            tw.close()
            reader.close()
            walk.dispose()
            return out
        }
    }

    /** 逐行 diff(与 legacy similar::TextDiff 同语义:逐行对照,带行号) */
    private fun lineDiff(oldText: String, newText: String): List<DiffLine> {
        val a = RawText(oldText.toByteArray(Charsets.UTF_8))
        val b = RawText(newText.toByteArray(Charsets.UTF_8))
        val algo = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
        val edits = algo.diff(RawTextComparator.DEFAULT, a, b)
        val lines = mutableListOf<DiffLine>()
        var ai = 0
        var bi = 0
        for (e in edits) {
            // edit 之前的相等区
            while (ai < e.beginA && bi < e.beginB) {
                lines.add(DiffLine("ctx", ai + 1, bi + 1, a.getString(ai)))
                ai++; bi++
            }
            // 删除区
            for (i in e.beginA until e.endA) {
                lines.add(DiffLine("del", i + 1, null, a.getString(i)))
            }
            // 插入区
            for (i in e.beginB until e.endB) {
                lines.add(DiffLine("add", null, i + 1, b.getString(i)))
            }
            ai = e.endA; bi = e.endB
        }
        while (ai < a.size() && bi < b.size()) {
            lines.add(DiffLine("ctx", ai + 1, bi + 1, a.getString(ai)))
            ai++; bi++
        }
        return lines
    }

    /** 解析短 hash 或完整 hash */
    private fun resolveId(repo: Repository, idStr: String): ObjectId {
        runCatching { return ObjectId.fromString(idStr) }
        // 前缀匹配:遍历 HEAD 历史
        Git(repo).use { git ->
            for (c in git.log().call()) {
                if (c.id.name.startsWith(idStr)) return c.id
            }
        }
        throw IllegalArgumentException("找不到提交: $idStr")
    }
}
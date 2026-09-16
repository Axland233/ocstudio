package com.yehenowo.gitmind

import com.yehenowo.gitmind.data.JGitRepo
import com.yehenowo.gitmind.data.createProject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// JGit 首提交回归测试:未出生分支下 commitAll 必须真正写出 ref,
// log() 要能读到历史(曾因 updateRef("HEAD") 静默 no-op 导致 refs/heads 空、UI 永远"暂无提交")。
class JGitRepoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `createProject writes initial commit and log reads it back`() {
        val workspace = tmp.newFolder("workspace")
        val git = JGitRepo()
        val info = createProject(workspace, "t1", "desc", "tester", git)
        assertTrue("created_at empty", info.created_at.isNotBlank())

        val log = git.log(java.io.File(workspace, "t1"))
        assertTrue("log should contain initial commit, got ${log.size}", log.isNotEmpty())
        assertTrue("message", log.first().message.contains("初始化"))
    }

    @Test
    fun `second commit has parent and log keeps order`() {
        val workspace = tmp.newFolder("workspace")
        val git = JGitRepo()
        createProject(workspace, "t2", "", "tester", git)
        val dir = java.io.File(workspace, "t2")
        val first = git.log(dir).first()

        val files = com.yehenowo.gitmind.data.collectGitFiles(dir)
            .map { (n, c) -> n to if (n == "核心卡.md") c + "\n追加行" else c }
        val second = git.commitAll(dir, files, "feat(核心卡): 追加", "tester")
        assertTrue(second != first.id)

        val log = git.log(dir)
        assertTrue(log.size >= 2)
        assertTrue("newest first", log.first().id == second)
    }

    @Test
    fun `empty author and odd name do not break create (device regression)`() {
        val workspace = tmp.newFolder("workspace")
        val git = JGitRepo()
        // 设备上曾复现:作者留空 + 名称含特殊字符时创建中途失败留下残目录
        val info = createProject(workspace, "%", "", "", git)
        assertTrue(git.log(java.io.File(workspace, "%")).isNotEmpty())
        assertTrue(info.name == "%")
    }
}

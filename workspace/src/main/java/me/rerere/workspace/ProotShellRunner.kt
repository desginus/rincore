package me.rerere.workspace


/* ───【域 D·工作区沙箱】ProotShellRunner.kt
 * 职责: proot 命令构造/常驻进程/MCP stdio 桥启动
 * 常用改动: 启动 → launchProcess; 命令构造 → buildCommand (env -i 真空)
 * 问题定位: 沙箱启动失败/proot 报错 → 本文件
 * 基线: 原版移植 + 自研 (常驻进程) | 地图: docs/APP_MAP.md §D | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/
import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val process = launchProcess(context)
            ?: return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${File(nativeLibraryDir, PROOT_EXEC).absolutePath}",
            )
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    /** 启动 proot 常驻进程 (不等待) — 供 MCP stdio 桥接: workspace 内有 Python/Node 运行时 */
    override fun launchProcess(context: WorkspaceShellContext): Process? {
        if (!context.linuxDir.hasUsableRootfs()) return null

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) return null
        if (!loader.isFile) return null

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                if (context.shellCompatibilityMode) {
                    environment()["PROOT_NO_SECCOMP"] = "1"
                } else {
                    environment().remove("PROOT_NO_SECCOMP")
                }
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        // v4.5.27: CWD 专一空间 — cwd 非空时把 /workspace 直接挂到该子目录,
        // 沙箱内根本看不到兄弟目录 (浏览与读写同时物理受限);
        // cwd 为空维持整区挂载 (用户终端 / 无 CWD 助手行为不变)。
        val scopedCwd = context.cwd.trim().trim('/')
        val mountSource = if (scopedCwd.isEmpty()) {
            context.filesDir
        } else {
            File(context.filesDir, scopedCwd)
        }
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            WORKSPACE_DIR,
            "-b",
            "${mountSource.absolutePath}:$WORKSPACE_DIR",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/bin/bash",
            "-l",
            "-c",
            // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
            "cd -- \"\$1\" && eval \"\$2\"",
            "rikkahub",
            WORKSPACE_DIR,
            context.command,
        )
        return command
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    }
}

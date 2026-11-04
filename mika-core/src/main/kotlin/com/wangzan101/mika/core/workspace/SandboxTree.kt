package com.wangzan101.mika.core.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * `files/sandbox/` 这棵树的主人:谁要往树里写,都先在这里拿到许可。进程里一份,由 App 给出,经构造参数交给要写的地方。
 *
 * - [exclusive]:改树的结构、或删整片的(安装/解包/换根文件系统、重置、清空或删掉工作区;App 里经 `SandboxDirs.exclusiveWrite`)。拿到时没有别人在写。
 * - [shared]:往已有的树里添东西的(技能同步、跑命令、网页下载、附件导入)。可以同时有很多个,
 *   但和 [exclusive] 互斥:重置删到一半时,不会有人又在里面建出目录(那样最后的 rmdir 会 ENOTEMPTY)。
 *
 * 有人在等 [exclusive] 时,新来的 [shared] 也排在它后面,重置不会被源源不断的命令饿死。
 * 同一条协程链里已经拿着(任一种)时再拿直接放行,嵌套调用不会自己锁死自己。
 *
 * - [lease]:长驻进程(沙箱里的 stdio MCP server)用的 shared,活多久拿多久,但可以被收回:
 *   [exclusive] 一拿到入口就收回所有租约(通知持有者停掉进程,许可当场放掉),不会被一个长驻进程挡上几十分钟;
 *   漏关的租约也挡不住重置。换根文件系统、清工作区之后进程本来也跑不下去,停掉比让它在删掉的目录上接着跑干净。
 */
class SandboxTree {
    /** 拿到它才能开始:[shared] 只短暂持有,[exclusive] 全程持有,所以写者排队时新读者也等着。 */
    private val entry = Mutex()
    /** 有 [shared] 在用时锁着。 */
    private val noShared = Mutex()
    private val count = Mutex()
    private var holders = 0

    /** 这条协程链此刻拿着哪几棵树的许可。 */
    private class Held(val trees: Set<SandboxTree>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private suspend fun heldTrees() = currentCoroutineContext()[Held]?.trees.orEmpty()

    suspend fun <T> exclusive(block: suspend () -> T): T {
        val held = heldTrees()
        if (this in held) return block()
        return entry.withLock {
            revokeLeases()
            noShared.withLock { withContext(Held(held + this)) { block() } }
        }
    }

    /** 登记着的租约(含还在排队的);[exclusive] 拿着 [entry] 时逐个收回。 */
    private val leases = LinkedHashSet<Lease>()

    /**
     * 长期持有的 shared 许可,[Lease.close] 时放掉。[exclusive] 要用时先调 [onRevoke](在 IO 线程上,可以阻塞着等进程退出)
     * 再放掉许可;之后持有者照常 close 不出错。排队期间被收回的,排到那次 [exclusive] 之后重新拿,交回的租约拿到时没被收回过。
     * 持有者在拿到租约之后启动的东西要自己核一次 [Lease.revoked]:收回可能正好落在拿到和启动之间。
     */
    suspend fun lease(onRevoke: () -> Unit): Lease {
        check(this !in heldTrees()) { "lease() must not be called while holding the tree" }
        while (true) {
            val lease = Lease(onRevoke)
            // 先登记再排队:排在入口上的租约也收得到,不会出现拿到了许可却没登记、挡住重置的空档
            synchronized(leases) { leases += lease }
            val acquired = CompletableDeferred<Boolean>()
            val holder = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    shared {
                        acquired.complete(lease.markHeld())
                        lease.released.await()
                    }
                } finally {
                    synchronized(leases) { leases -= lease }
                    acquired.complete(false)
                }
            }
            val ok = try {
                acquired.await()
            } catch (e: Throwable) {
                lease.close()
                holder.cancel()
                throw e
            }
            if (ok) return lease
        }
    }

    private suspend fun revokeLeases() {
        val all = synchronized(leases) { leases.toList() }
        if (all.isEmpty()) return
        withContext(Dispatchers.IO + NonCancellable) { for (l in all) l.revoke() }
    }

    /** 见 [lease]。 */
    class Lease internal constructor(private val onRevoke: () -> Unit) : java.io.Closeable {
        internal val released = CompletableDeferred<Unit>()
        private var held = false

        /** 被 [exclusive] 收回过;收回后许可已经放掉,持有者不该再往树里写。 */
        @Volatile var revoked = false
            private set

        /** 拿到许可时调:这之前已被收回的返回 false。 */
        internal fun markHeld(): Boolean = synchronized(this) { if (revoked) { released.complete(Unit); false } else { held = true; true } }

        internal fun revoke() {
            val wasHeld = synchronized(this) {
                if (revoked || released.isCompleted) return
                revoked = true
                held
            }
            // 还在排队的不用停什么:它会排到这次之后重新拿
            if (wasHeld) runCatching { onRevoke() }
            released.complete(Unit)
        }

        override fun close() { released.complete(Unit) }
    }

    suspend fun <T> shared(block: suspend () -> T): T {
        val held = heldTrees()
        if (this in held) return block()
        entry.withLock { count.withLock { if (holders++ == 0) noShared.lock() } }
        try {
            return withContext(Held(held + this)) { block() }
        } finally {
            withContext(NonCancellable) { count.withLock { if (--holders == 0) noShared.unlock() } }
        }
    }
}

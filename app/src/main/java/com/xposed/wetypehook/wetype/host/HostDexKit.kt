package com.xposed.wetypehook.wetype.host

import com.xposed.wetypehook.xposed.Log
import org.luckypray.dexkit.DexKitBridge

/**
 * 进程内共享的 DexKit bridge。
 *
 * 微信输入法 4.0.0 起安装包约 216MB，每次 [DexKitBridge.create] 都要把整包 dex 解析进
 * native 缓存。各 hook 组原本各自 create/close，输入法进程启动时会重复解析七八次；
 * 实测 Android 16 开机内存紧张时 `:hld` 会在 libdexkit 里 abort，连续崩溃重启 5 次。
 * 这里按 sourceDir 复用同一份 bridge，把启动期解析次数降到 1 次。
 *
 * 移植自 Costben/WeType-Enhance ade48de。
 */
internal object HostDexKit {
    private var sourceDir: String? = null
    private var bridge: DexKitBridge? = null
    private var failedDir: String? = null

    @Synchronized
    fun get(sourceDir: String?): DexKitBridge? {
        if (sourceDir.isNullOrEmpty()) return null
        bridge?.let { existing ->
            if (this.sourceDir == sourceDir) return existing
            closeLocked()
        }
        if (failedDir == sourceDir) return null
        val created = runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(sourceDir)
        }.onFailure {
            Log.e("Failed: create DexKit bridge: ${it.message}")
            Log.i(it)
        }.getOrNull()
        if (created == null) {
            failedDir = sourceDir
            return null
        }
        this.sourceDir = sourceDir
        bridge = created
        Log.i("HostDexKit: bridge created for $sourceDir")
        return created
    }

    @Synchronized
    fun close() {
        closeLocked()
        failedDir = null
    }

    /**
     * 后台预热：只负责在专用线程里完成 216MB 宿主包的首次解析。
     * 真正的查询仍走 [get]，调用方可以安全地在任意线程复用同一个 bridge。
     */
    fun prewarm(sourceDir: String?) {
        if (sourceDir.isNullOrEmpty()) return
        get(sourceDir)
    }

    private fun closeLocked() {
        runCatching { bridge?.close() }
        bridge = null
        sourceDir = null
    }
}

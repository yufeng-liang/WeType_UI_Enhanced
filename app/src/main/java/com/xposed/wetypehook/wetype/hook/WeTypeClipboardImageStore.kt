package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.util.Log as AndroidLog
import com.xposed.wetypehook.wetype.clipboard.imageStoreFileName
import com.xposed.wetypehook.wetype.clipboard.imageStoreItemId
import java.io.File
import java.util.HashMap

/**
 * 跨设备图片的模块私有副本（ADR-0007）。
 *
 * 宿主机把解密后的图片持久化在自管缓存目录（`MicroMsg/wxime/cache/network_res/image/`），
 * 该目录会被宿主定期清理；清理后记录仍是 `pathType=0`，而远程链接已在持久化时被覆盖，
 * 图片从此永久失效（行只剩“来自关联设备的图片”文案）。
 *
 * 下载成功后把图片额外存一份到 `files/wetypehook_clip_images/<id>.<ext>`：
 * - 宿主清理自己的缓存目录不影响本副本；
 * - 渲染/预览读的是同进程私有目录，宿主与模块都能直接访问；
 * - 宿主路径丢失但副本还在时自动回写副本路径（自愈）；
 * - 记录删除（用户删除/留存裁剪/死记录清理）时同步删除副本。
 */
internal object WeTypeClipboardImageStore {

    private const val TAG = WeTypeClipboardImageHost.TAG
    private const val DIR_NAME = "wetypehook_clip_images"

    /**
     * id → 副本文件的内存索引。
     *
     * 旧实现每次 find/delete 都 `listFiles()` 全目录扫描；图片条数多时
     * refreshCacheFromDao 每条记录调用 find 两次，整体退化为 O(N²) 文件枚举。
     * 本对象是模块私有目录的唯一写入方，索引只在首次访问时构建一次，
     * 之后 import/delete 同步维护。
     */
    private val indexLock = Any()
    private val idIndex = HashMap<Long, File>()
    private var indexedDirPath: String? = null

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun ensureIndexLocked(d: File) {
        val path = d.absolutePath
        if (indexedDirPath == path) return
        idIndex.clear()
        d.listFiles()?.forEach { file ->
            if (file.isFile && file.length() > 0L) {
                imageStoreItemId(file.name)?.let { id -> idIndex.putIfAbsent(id, file) }
            }
        }
        indexedDirPath = path
    }

    private fun rememberFile(d: File, id: Long, file: File) {
        synchronized(indexLock) {
            ensureIndexLocked(d)
            idIndex[id] = file
        }
    }

    /** 下载产物复制进私有目录；返回副本文件，失败返回 null（调用方回退宿主路径）。 */
    fun importFromFile(context: Context, id: Long, source: File): File? {
        return try {
            if (!source.isFile || source.length() <= 0L) return null
            val d = dir(context)
            if (!d.isDirectory && !d.mkdirs()) return null
            val dest = File(d, imageStoreFileName(id, source.extension))
            if (dest.isFile && dest.length() == source.length()) {
                rememberFile(d, id, dest)
                return dest
            }
            source.inputStream().use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            if (dest.isFile && dest.length() > 0L) {
                rememberFile(d, id, dest)
                dest
            } else {
                null
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "image store import failed id=$id: ${t.message}")
            null
        }
    }

    /** 按 id 找现有副本（文件名 `<id>.<ext>`）。 */
    fun find(context: Context, id: Long): File? {
        return try {
            val d = dir(context)
            if (!d.isDirectory) return null
            val candidate = synchronized(indexLock) {
                ensureIndexLocked(d)
                idIndex[id]
            }
            if (candidate != null && candidate.isFile && candidate.length() > 0L) return candidate
            if (candidate != null) {
                synchronized(indexLock) {
                    if (idIndex[id] === candidate) idIndex.remove(id)
                }
            }
            null
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "image store lookup failed id=$id: ${t.message}")
            null
        }
    }

    fun delete(context: Context, id: Long) {
        try {
            val d = dir(context)
            if (!d.isDirectory) return
            val candidate = synchronized(indexLock) {
                ensureIndexLocked(d)
                idIndex[id]
            }
            candidate?.delete()
            synchronized(indexLock) { idIndex.remove(id) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "image store delete failed id=$id: ${t.message}")
        }
    }
}

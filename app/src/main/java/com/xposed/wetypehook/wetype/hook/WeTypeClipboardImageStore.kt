package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.util.Log as AndroidLog
import com.xposed.wetypehook.wetype.clipboard.imageStoreFileName
import com.xposed.wetypehook.wetype.clipboard.imageStoreItemId
import java.io.File

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

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 下载产物复制进私有目录；返回副本文件，失败返回 null（调用方回退宿主路径）。 */
    fun importFromFile(context: Context, id: Long, source: File): File? {
        return try {
            if (!source.isFile || source.length() <= 0L) return null
            val d = dir(context)
            if (!d.isDirectory && !d.mkdirs()) return null
            val dest = File(d, imageStoreFileName(id, source.extension))
            if (dest.isFile && dest.length() == source.length()) return dest
            source.inputStream().use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            if (dest.isFile && dest.length() > 0L) dest else null
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
            d.listFiles()?.firstOrNull { it.isFile && it.length() > 0L && imageStoreItemId(it.name) == id }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "image store lookup failed id=$id: ${t.message}")
            null
        }
    }

    fun delete(context: Context, id: Long) {
        try {
            val d = dir(context)
            if (!d.isDirectory) return
            d.listFiles()?.forEach { f ->
                if (f.isFile && imageStoreItemId(f.name) == id) f.delete()
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "image store delete failed id=$id: ${t.message}")
        }
    }
}

package com.xposed.wetypehook.wetype.clipboard

import com.github.promeg.pinyinhelper.Pinyin
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * 剪贴板搜索内核（Slice 2）。
 *
 * 纯 Kotlin + TinyPinyin，不碰 Xposed/Adapter/View。
 * 上游语义见 S1 证据：文本条目即 `C.getType()==0`，[getContent()][String] 为搜索唯一范围；
 * 主过滤点 `ImeClipboardScrollView.setList`（S4/S5 消费本引擎结果）。
 *
 * 已知口径限制（按「召回优先」取舍，暂不修复）：
 * 1. 全拼串是无分隔拼接：跨音节误命中不可避免，如 `xian` 会同时命中「西安」与「先」。
 * 2. TinyPinyin 是单音字典：多音字组合可能匹配不到，如 `chongqing` ↔「重庆」（重取 zhòng 音）。
 */
data class MatchResult(val index: Int, val ranges: List<IntRange>)

/**
 * 带缓存 key 的搜索候选。
 *
 * [key] 用剪贴板条目对象本身：同一份宿主快照里条目对象稳定，引擎按它缓存 lowercase/拼音索引；
 * 条目内容若被原地修改，缓存会因内容比对不相等而自动重建。[key] 为空时退化为一次性索引
 * （高亮路径只搜单条内容，不需要跨次复用）。
 */
class IndexedContent(val key: Any?, val content: String)

object ClipboardSearchEngine {
    /**
     * 单条内容的索引。content 只持引用不复制；WeakHashMap 的 key 是剪贴板条目对象，
     * 条目被宿主列表回收后整条缓存会自动消失，不会把整份剪贴板内容钉在内存里。
     */
    private class ContentIndex(
        val content: String,
        val lower: String,
        var fullPinyin: String? = null,
        var initials: String? = null,
        var hasChinese: Boolean? = null
    )

    private val indexCache: MutableMap<Any, ContentIndex> =
        Collections.synchronizedMap(WeakHashMap())

    fun normalize(s: String): String = s.trim().lowercase(Locale.ROOT)

    fun matches(content: String, keyword: String): Boolean {
        val kw = normalize(keyword)
        if (kw.isEmpty()) return true
        return matchRanges(content, kw) != null
    }

    fun search(contents: List<String>, keyword: String, limit: Int = 50): List<MatchResult> =
        searchDocuments(contents.map { IndexedContent(null, it) }, keyword, limit)

    /**
     * 带索引缓存的搜索入口。相同条目对象 + 相同内容在多次按键间复用 lowercase/拼音索引，
     * 避免每次防抖后都为整份剪贴板重建字符串。
     *
     * [isCancelled] 由调用方注入代际/线程中断检查：快速连续输入时旧任务在循环里就能停下，
     * 不必等整份列表扫完再被主线程丢弃。
     */
    fun searchDocuments(
        documents: List<IndexedContent>,
        keyword: String,
        limit: Int = 50,
        isCancelled: (() -> Boolean)? = null
    ): List<MatchResult> {
        if (limit <= 0) return emptyList()
        val kw = normalize(keyword)
        if (kw.isEmpty()) {
            val n = minOf(limit, documents.size)
            val out = ArrayList<MatchResult>(n)
            for (i in 0 until n) out.add(MatchResult(i, emptyList()))
            return out
        }
        val out = ArrayList<MatchResult>(minOf(limit, documents.size))
        for (i in documents.indices) {
            // 每 32 条检查一次取消：单条内容可能很长，检查太频繁反而浪费；32 条足够让旧任务
            // 在快速输入时及时退出。
            if ((i and 31) == 0 && (isCancelled?.invoke() == true || Thread.currentThread().isInterrupted)) {
                return emptyList()
            }
            val item = documents[i]
            val index = indexFor(item)
            val ranges = matchRanges(item.content, kw, index) ?: continue
            out.add(MatchResult(i, ranges))
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * 取（或补建）条目索引。内容被原地修改时，旧缓存里的 content 与新内容不相等，直接重建；
     * 这样无需依赖条目 id 或额外的失效回调，也不会把过期拼音串用在编辑后的内容上。
     */
    private fun indexFor(item: IndexedContent): ContentIndex {
        val key = item.key
        if (key != null) {
            synchronized(indexCache) {
                val cached = indexCache[key]
                if (cached != null && cached.content == item.content) return cached
            }
            val built = ContentIndex(item.content, item.content.lowercase(Locale.ROOT))
            synchronized(indexCache) { indexCache[key] = built }
            return built
        }
        return ContentIndex(item.content, item.content.lowercase(Locale.ROOT))
    }

    /**
     * @param kw 已 normalize（trim + 小写）的关键词，非空。
     * @return 命中时返回 content 下标区间（供 S5 高亮），未命中返回 null。
     */
    private fun matchRanges(content: String, kw: String, index: ContentIndex? = null): List<IntRange>? {
        if (content.isEmpty()) return null
        // 1. 不分大小写包含命中：**全部**命中都要返回，不能只回第一处。
        //    调用方（卡片高亮）是逐段染色的；只回第一处的话，一张卡片里同一个词的第 2 处起永远不标红
        //    （实机：搜 `SS` 只有开头那一处变色，用户报「有些没有标红」）。区间互不重叠、按顺序给出。
        //    未命中时不分配任何对象：这条路径在「搜一个不存在的词」时会被 10 万条内容各走一遍。
        val idx = index ?: ContentIndex(content, content.lowercase(Locale.ROOT))
        val lower = idx.lower
        if (lower.length != content.length) {
            // 保守路径（罕见）：lowercase 副本与原文不等长（如土耳其语 'İ' U+0130 小写成两个字符），
            // 副本下标无法一一映射回原文，高亮区间会错位；改为对原文逐字符忽略大小写扫描，
            // 命中区间天然落在原文下标上。此路径性能不敏感（正常中英文内容不会走到）。
            val hits = ArrayList<IntRange>(2)
            var from = 0
            while (from <= content.length - kw.length) {
                val idx = indexOfIgnoreCase(content, kw, from) ?: break
                hits.add(idx until idx + kw.length)
                from = idx + kw.length
            }
            // 等长路径语义一致：字面未命中才继续尝试拼音路径。
            if (hits.isNotEmpty()) return hits
        } else {
            val first = lower.indexOf(kw)
            if (first >= 0) {
                val hits = ArrayList<IntRange>(2)
                var from = first
                while (from <= lower.length - kw.length) {
                    val idx = lower.indexOf(kw, from)
                    if (idx < 0) break
                    val end = (idx + kw.length - 1).coerceAtMost(content.length - 1)
                    if (idx < content.length && end >= idx) hits.add(idx..end)
                    from = idx + kw.length
                }
                if (hits.isNotEmpty()) return hits
                return null
            }
        }
        // 2. 拼音命中：关键词须为 ASCII 词（拼音/首字母），含汉字则字面已判负，直接返回。
        //    口径限制：全拼串无分隔拼接，存在跨音节误命中（xian 命中西安/先，召回优先不切音节）；
        //    TinyPinyin 为单音字典，多音字组合（如 chongqing↔重庆）可能匹配不到。详见类 KDoc。
        if (!isAsciiWord(kw)) return null
        val chinese = idx.hasChinese ?: hasChinese(content).also { idx.hasChinese = it }
        if (!chinese) return null
        // 拼音串按条目缓存：第一次搜中文内容时构建一次，之后同一份内容换关键词直接复用。
        ensurePinyin(idx, content)
        val full = idx.fullPinyin ?: return null
        val initials = idx.initials ?: return null
        val pIdx = full.indexOf(kw)
        if (pIdx >= 0) {
            val range = mapFullPinyinToRange(content, pIdx, pIdx + kw.length)
            if (range != null) return listOf(range)
        }
        val iIdx = initials.indexOf(kw)
        if (iIdx >= 0 && iIdx + kw.length <= content.length) {
            return listOf(iIdx until iIdx + kw.length)
        }
        return null
    }

    /** 为 [index] 补建全拼串与首字母串（单遍；未命中路径不做下标回填分配）。 */
    private fun ensurePinyin(index: ContentIndex?, content: String) {
        if (index == null || (index.fullPinyin != null && index.initials != null)) return
        val full = StringBuilder(content.length * 4)
        val initials = StringBuilder(content.length)
        for (c in content) {
            if (Pinyin.isChinese(c)) {
                val p = Pinyin.toPinyin(c)
                var first = true
                for (pc in p) {
                    val lc = if (pc in 'A'..'Z') (pc + 32) else pc
                    full.append(lc)
                    if (first) {
                        initials.append(lc)
                        first = false
                    }
                }
            } else {
                val lc = if (c in 'A'..'Z') (c + 32) else c.lowercaseChar()
                full.append(lc)
                initials.append(lc)
            }
        }
        index.fullPinyin = full.toString()
        index.initials = initials.toString()
    }

    private fun isAsciiWord(s: String): Boolean {
        for (c in s) {
            if ((c in 'a'..'z') || (c in '0'..'9')) continue
            return false
        }
        return true
    }

    /**
     * 保守路径用的忽略大小写 indexOf：从 [from] 起在原文里逐字符找 kw。
     * kw 已 normalize（小写），只需把原文侧字符 [lowercaseChar] 后比较；
     * 返回的即是原文下标，未命中返回 null。
     */
    private fun indexOfIgnoreCase(content: String, kw: String, from: Int): Int? {
        val lastStart = content.length - kw.length
        var i = from
        while (i <= lastStart) {
            var j = 0
            while (j < kw.length) {
                if (content[i + j].lowercaseChar() != kw[j]) break
                j++
            }
            if (j == kw.length) return i
            i++
        }
        return null
    }

    private fun hasChinese(s: String): Boolean {
        for (c in s) {
            if (Pinyin.isChinese(c)) return true
        }
        return false
    }

    /** 把全拼串上的 [pStart, pEnd) 回填为 content 的汉字区间。 */
    private fun mapFullPinyinToRange(content: String, pStart: Int, pEnd: Int): IntRange? {
        var pos = 0
        var s = -1
        var e = -1
        for (i in content.indices) {
            val piece = if (Pinyin.isChinese(content[i])) Pinyin.toPinyin(content[i]).length else 1
            val start = pos
            pos += piece
            if (s < 0 && pos > pStart) s = i
            if (start < pEnd) e = i
        }
        if (s < 0 || e < 0 || e < s) return null
        return s..e
    }
}

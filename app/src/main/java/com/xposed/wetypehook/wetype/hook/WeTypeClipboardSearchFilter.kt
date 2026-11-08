package com.xposed.wetypehook.wetype.hook

import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log as AndroidLog
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.xposed.wetypehook.wetype.clipboard.ClipboardSearchEngine
import com.xposed.wetypehook.wetype.clipboard.ClipboardSnippetLogic
import com.xposed.wetypehook.wetype.clipboard.IndexedContent
import com.xposed.wetypehook.wetype.host.HostContractId
import com.xposed.wetypehook.wetype.host.WeTypeHostContracts
import com.xposed.wetypehook.wetype.host.pickHostMethod
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.hookAfter
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Slice 5：剪贴板搜索过滤渲染（防抖 + 后台搜 + 前50分页 + 高亮）。
 *
 * 挂点（S1 证据）：
 * - 核心过滤点 `ImeClipboardScrollView.setList(List<C>)`（证据 19 行）：只做全量快照，
 *   实际过滤在后台线程跑完后经原生链路 `setList(过滤后)` + `getListAdapter().y()` 回放
 *   （`y()` 刷新点见证据 23 行，`S15.a1()` 统一刷新见 13 行）。禁绕过 Adapter 自建列表。
 * - 高亮次选点 `Adapter v.onBindViewHolder`（证据 22 行）：后处理加 [ForegroundColorSpan]。
 * - 文本判定 `C.getType()==0` + `getContent()` 为搜索唯一范围（证据 24/25 行）；
 *   图片/文件/远程条目（type != 0）原样保留不参搜。
 * - S4 扩展点：只消费 `WeTypeClipboardSearchUi.setKeywordListener`（`clearSearch`
 *   由 Ui 侧清空搜索框文本并透传空关键词，本片经监听空关键词执行恢复）。
 * - S2 引擎冻结 API：只引用 `search(contents, keyword, limit=50)`。
 */
internal object WeTypeClipboardSearchFilter {

    private const val TAG = "WeTypeClipboardSearch"
    private const val SCROLLVIEW_CLASS =
        "com.tencent.wetype.plugin.hld.clipboard.ImeClipboardScrollView"
    private const val ADAPTER_CLASS = "com.tencent.wetype.plugin.hld.clipboard.v"

    private const val DEBOUNCE_MS = 300L
    private const val PAGE_LIMIT = 50

    // 高亮色来源：README 主题色 #FB7299（模块品牌强调色）。
    // 用前景色（文字色）而非底色，避免与深浅主题底色冲突；清空时移除同色 span 恢复原样。
    private const val HIGHLIGHT_COLOR = 0xFFFB7299.toInt()

    /** 片段起点相对命中往前留的字符数：留一点上下文，又保证命中落在第一行（半列一行约 7 个全角字）。 */
    private const val SNIPPET_LEAD_CHARS = 3

    /** 片段最多取多少字符：卡片只排几行，不必把几万字的原文整段塞给 `TextView`。 */
    private const val SNIPPET_MAX_CHARS = 400

    /** 片段验证没通过（命中被截掉）时最多再收窄几轮窗口，仍不行才退回紧凑片段。 */
    private const val SNIPPET_MAX_ROUNDS = 3

    /** 每轮收窄时在验证量到的可见字数上再扣多少字，留出换行时的误差。 */
    private const val SNIPPET_SHRINK_MARGIN = 3

    /** 卡片文本至少这么长才去找「它是某条完整内容的前缀」（宿主截断显示）：短文本是标签 / 按钮文字。 */
    private const val TRUNCATED_TEXT_MIN_CHARS = 256

    /** 命中片段前面补的省略号（片段不是从原文开头开始时才有）。 */
    private const val SNIPPET_ELLIPSIS = "…"

    /** 被换成「命中片段」的正文 → 完整原文，清空关键词或重新绑定时还原。 */
    private val snippetOriginals: MutableMap<TextView, CharSequence> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * 高亮区间缓存：全量 rebind 会对同一批 (content, keyword) 反复绑定，旧实现每行都重跑
     * 引擎（lowercase + 拼音构建）。LinkedHashMap 访问序 + 上限 16 条，避免长期持有大文本。
     * key 里包含完整内容与关键词，内容/关键词任一变化都会自然 miss，不存在失效问题。
     */
    private val highlightRangeCache = object :
        LinkedHashMap<Pair<String, String>, List<IntRange>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<String, String>, List<IntRange>>
        ): Boolean = size > 16
    }

    @Volatile
    private var hooked = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WeTypeClipboardSearch").apply { isDaemon = true }
    }

    // 版本号 guards：新关键词/新数据到达即 +1，防后台乱序回写。
    private val version = AtomicLong(0L)

    // 快速连续输入时上一轮全量扫描可能还在单线程 executor 里排队/执行。保留 Future 并在新
    // 调度时取消（不能中断线程：宿主反射调用不响应中断，靠 version 代际检查在循环里退出）。
    @Volatile
    private var activeFilterFuture: Future<*>? = null

    // 原生 setList 回放时的重入 guard：我们自己调 setList 时跳过快照与重排，避免循环。
    private val applyingFilter = AtomicBoolean(false)

    @Volatile
    private var currentKeyword = ""

    /** 最近一次回放给列表的文本条目的完整内容（只在关键词非空时有），用来找回被宿主截断的卡片原文。 */
    @Volatile
    private var shownContents: List<String> = emptyList()

    /** 供图片注入器判断是否正在回放过滤结果（避免把图片塞回搜索结果）。 */
    internal fun isReplaying(): Boolean = applyingFilter.get()

    /** 供图片注入器判断当前搜索态。 */
    internal fun currentKeywordValue(): String = currentKeyword

    private val lock = Any()
    private val fullItems: MutableList<Any> = ArrayList()
    private val scrollViews: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    // C.getType()/getContent() 反射缓存（同类加载器下同类，main/后台线程只读调用）。
    @Volatile
    private var getTypeMethod: Method? = null
    @Volatile
    private var getContentMethod: Method? = null

    // ---- 反射缓存（owner 类失配才重扫，仿 getTypeMethod 的模式） ----
    // onBindViewHolder 每行绑定都会取 holder 的 itemView：declaredFields 双层遍历不能进热路径。
    // 按 holder 具体类缓存 View 类型字段列表，空列表（= 没找到，null 也缓存）不再重扫。
    @Volatile
    private var holderViewFields: List<Field>? = null
    @Volatile
    private var holderViewFieldsOwner: Class<*>? = null

    // setList / getListAdapter / notifyDataSetChanged 的方法缓存：调用点受 300ms 防抖保护（优先级低），
    // 但同名宿主类每次过滤都会重扫 declaredMethods 刷日志，顺手按 owner 类缓存；owner 已记录而
    // Method 为空 = 已确认不存在，不再重扫。
    @Volatile
    private var setListMethod: Method? = null
    @Volatile
    private var setListMethodOwner: Class<*>? = null
    @Volatile
    private var getListAdapterMethod: Method? = null
    @Volatile
    private var getListAdapterMethodOwner: Class<*>? = null
    @Volatile
    private var notifyDataSetChangeMethod: Method? = null
    @Volatile
    private var notifyDataSetChangeMethodOwner: Class<*>? = null

    private val debounceTask = Runnable {
        try {
            val v = version.get()
            val kw = currentKeyword
            val snapshot: List<Any>
            val targets: List<Any>
            synchronized(lock) {
                snapshot = ArrayList(fullItems)
                targets = ArrayList(scrollViews)
            }
            if (targets.isEmpty()) return@Runnable
            // S6：快照为空时仍走后台/回放，使空态文案能覆盖原生空标题；
            // 后台对空快照直接产出空列表，开销可忽略。
            activeFilterFuture = executor.submit { runFilterInBackground(v, kw, snapshot, targets) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "debounce dispatch failed: ${t.message}")
        }
    }

    fun install(classLoader: ClassLoader) {
        if (hooked) return
        try {
            hookSetList(classLoader)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "hook setList failed: ${t.message}")
        }
        try {
            hookBind(classLoader)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "hook onBindViewHolder failed: ${t.message}")
        }
        try {
            // S4 扩展点消费：关键词变化只做防抖调度，实际搜索在后台线程。
            WeTypeClipboardSearchUi.setKeywordListener { kw -> onKeyword(kw) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "register keyword listener failed: ${t.message}")
        }
        hooked = true
        AndroidLog.i(TAG, "search filter installed (debounce=${DEBOUNCE_MS}ms, limit=$PAGE_LIMIT)")
    }

    // ---- 关键词入口（主线程防抖） ----

    private fun onKeyword(raw: String?) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { onKeyword(raw) }
                return
            }
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) {
                // 开关关闭：取消 pending 并抬升版本作废在途后台任务，不碰列表；
                // S6：空态文案一并恢复原生。
                currentKeyword = ""
                version.incrementAndGet()
                mainHandler.removeCallbacks(debounceTask)
                try {
                    val targets: List<Any> = synchronized(lock) { ArrayList(scrollViews) }
                    WeTypeClipboardSearchEmpty.onSwitchOff(targets)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "empty restore on switch-off failed: ${t.message}")
                }
                return
            }
            // 空关键词且当前已经是空态：没有需要恢复的过滤结果，直接返回，避免无意义地
            // 再走一遍快照复制/后台空过滤/全量 rebind。
            if (raw.isNullOrEmpty() && currentKeyword.isEmpty()) return
            currentKeyword = raw.orEmpty()
            scheduleLocked()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "onKeyword failed: ${t.message}")
        }
    }

    private fun onNativeSetList(scrollView: Any, listArg: Any?) {
        try {
            if (applyingFilter.get()) return
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) return
            val list = listArg as? List<*> ?: return
            val runSchedule: Boolean
            synchronized(lock) {
                scrollViews.add(scrollView)
                // 空关键词时仍刷新全量快照（恢复/首挂载路径），不额外调度。
                fullItems.clear()
                for (item in list) {
                    if (item != null) fullItems.add(item)
                }
                // 宿主列表默认排除 type==1 图片；合入模块注入缓存，保证搜索恢复时可见。
                WeTypeClipboardImageList.appendCachedUnique(fullItems)
                runSchedule = currentKeyword.isNotEmpty()
            }
            WeTypeClipboardImageList.onNativeSetList(scrollView, list.filterNotNull())
            // 主线程调度；hookAfter 大概率已在主线程，非主则抛回主线程。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (runSchedule) scheduleLocked()
            } else {
                mainHandler.post {
                    try {
                        if (runSchedule &&
                            WeTypeSettings.isClipboardSearchEnabledXposed() &&
                            !applyingFilter.get()
                        ) {
                            scheduleLocked()
                        }
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "native setList reschedule failed: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "onNativeSetList failed: ${t.message}")
        }
    }

    private fun scheduleLocked() {
        try {
            version.incrementAndGet()
            // 旧的排队任务直接作废；正在跑的旧任务靠 version 代际检查在搜索循环里退出。
            activeFilterFuture?.cancel(false)
            activeFilterFuture = null
            mainHandler.removeCallbacks(debounceTask)
            mainHandler.postDelayed(debounceTask, DEBOUNCE_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "schedule filter failed: ${t.message}")
        }
    }

    // ---- 后台搜索（禁碰 View） ----

    /** 一次过滤的产物：回放的条目 + 它们对应的完整文本（只在这里读一次 getItemContent）。 */
    private class FilterResult(val items: List<Any>, val contents: List<String>)

    private fun runFilterInBackground(
        v: Long,
        keyword: String,
        snapshot: List<Any>,
        targets: List<Any>
    ) {
        try {
            // 排队期间已经有更新的关键词/数据：直接退出，不占 executor。
            if (v != version.get()) return
            val result: FilterResult = if (keyword.isEmpty()) {
                FilterResult(ArrayList(snapshot), emptyList())
            } else {
                filterSnapshot(snapshot, keyword, v)
            }
            if (v != version.get()) return
            mainHandler.post { applyResultOnMain(v, keyword, targets, result.items, result.contents) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "background filter failed: ${t.message}")
        }
    }

    private fun filterSnapshot(snapshot: List<Any>, keyword: String, generation: Long): FilterResult {
        // 文本条目逐条抽 content 走引擎；非文本原样保留不参搜（S1 证据 B:988 语义）。
        // content 读不到的条目判不准→保留（宁可多留不断数据）。
        // 类型与内容各只读一次并缓存到数组：旧实现 filter 内读一次、回放前又 mapNotNull 读一次，
        // 同一批条目要付两遍反射成本。
        val types = LongArray(snapshot.size)
        val contentsByIndex = arrayOfNulls<String>(snapshot.size)
        val documents = ArrayList<IndexedContent>(snapshot.size)
        val textToOrig = ArrayList<Int>(snapshot.size)
        val unknownContent = HashSet<Int>()
        for (i in snapshot.indices) {
            val item = snapshot[i]
            try {
                val type = getItemType(item)
                types[i] = type
                if (type != 0L) continue
                val content = getItemContent(item)
                if (content == null) {
                    unknownContent.add(i)
                    continue
                }
                contentsByIndex[i] = content
                documents.add(IndexedContent(item, content))
                textToOrig.add(i)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "snapshot item read failed: ${t.message}")
            }
        }
        val matchedOrig = HashSet<Int>()
        try {
            val hits = ClipboardSearchEngine.searchDocuments(
                documents, keyword, PAGE_LIMIT
            ) { generation != version.get() || Thread.currentThread().isInterrupted }
            if (generation != version.get() || Thread.currentThread().isInterrupted) {
                return FilterResult(emptyList(), emptyList())
            }
            for (hit in hits) {
                val pos = hit.index
                if (pos in textToOrig.indices) matchedOrig.add(textToOrig[pos])
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "engine search failed: ${t.message}")
            val allContents = ArrayList<String>(documents.size)
            for (i in snapshot.indices) if (types[i] == 0L) contentsByIndex[i]?.let(allContents::add)
            return FilterResult(ArrayList(snapshot), allContents)
        }
        val out = ArrayList<Any>(snapshot.size.coerceAtMost(PAGE_LIMIT + 16))
        val keptContents = ArrayList<String>()
        for (i in snapshot.indices) {
            val item = snapshot[i]
            try {
                val type = types[i]
                if (type != 0L) {
                    // ADR-0002：搜索关键词非空时图片条目全部隐藏；其余非文本保持原样。
                    if (type != 1L) out.add(item)
                } else if (matchedOrig.contains(i) || unknownContent.contains(i)) {
                    out.add(item)
                    contentsByIndex[i]?.let(keptContents::add)
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "filter item failed: ${t.message}")
                out.add(item)
            }
        }
        return FilterResult(out, keptContents)
    }

    // ---- 主线程回放（原生链路刷新） ----

    private fun applyResultOnMain(
        v: Long,
        keyword: String,
        targets: List<Any>,
        filtered: List<Any>,
        contents: List<String>
    ) {
        try {
            if (v != version.get()) return
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) return
            if (targets.isEmpty()) return
            // 必须在回放之前换好：回放会立刻触发重绑，绑定后的高亮要用它。
            shownContents = contents
            for (scrollView in targets) {
                try {
                    applyingFilter.set(true)
                    invokeSetList(scrollView, filtered)
                    invokeAdapterRefresh(scrollView)
                    // S8：宿主 y() 只重算折叠分区并 notifyItemChanged(0)，可视区其余行
                    // 保持旧绑定（滑出屏幕回收后才重绑，表现为第 2/3 行短暂显示旧内容）。
                    // 这里补一次全量 notifyDataSetChanged 强制重绑所有行。
                    invokeAdapterFullRebind(scrollView)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "apply filtered list failed: ${t.message}")
                } finally {
                    applyingFilter.set(false)
                }
            }
            // S6：空态（无结果文案/恢复原生）复用原生空视图链路，主线程 UI。
            try {
                WeTypeClipboardSearchEmpty.onFilterResult(targets, keyword, filtered.isEmpty())
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "empty state dispatch failed: ${t.message}")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "applyResult failed: ${t.message}")
        }
    }

    private fun invokeSetList(scrollView: Any, filtered: List<Any>) {
        try {
            // setList 反射结果按宿主类缓存：owner 相同直接复用（owner 已记录而方法为空 = 已确认无）。
            val scrollViewClass = scrollView.javaClass
            if (setListMethodOwner != scrollViewClass) {
                setListMethod = scrollViewClass.declaredMethods.firstOrNull { m ->
                    m.name == "setList" &&
                        m.parameterTypes.size == 1 &&
                        List::class.java.isAssignableFrom(m.parameterTypes[0])
                }
                setListMethodOwner = scrollViewClass
                setListMethod?.isAccessible = true
            }
            val method = setListMethod ?: run {
                // 仅在首次确认不存在时记日志，避免每次过滤重扫 + 刷屏。
                AndroidLog.e(TAG, "setList(List) not found on ${scrollViewClass.name}")
                return
            }
            // 原生 setList 期望 ArrayList<C>；传 ArrayList 保持与原生一致。
            method.invoke(scrollView, ArrayList(filtered))
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "invoke setList failed: ${t.message}")
        }
    }

    private fun invokeAdapterRefresh(scrollView: Any) {
        try {
            val adapter = resolveAdapter(scrollView) ?: return
            val refresh = pickHostMethod(adapter.javaClass, "y") { m ->
                m.parameterTypes.isEmpty() && m.returnType == Void.TYPE
            } ?: run {
                AndroidLog.e(TAG, "adapter y() not found on ${adapter.javaClass.name}")
                return
            }
            refresh.isAccessible = true
            refresh.invoke(adapter)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "adapter refresh y() failed: ${t.message}")
        }
    }

    /** 全量重绑：宿主 `y()` 不覆盖可视区旧行，需显式 `notifyDataSetChanged`。 */
    private fun invokeAdapterFullRebind(scrollView: Any) {
        try {
            val adapter = resolveAdapter(scrollView) ?: return
            // notifyDataSetChanged 可能声明在父类上：按 adapter 具体类（owner）缓存查找结果，
            // owner 相同直接复用（owner 已记录而方法为空 = 已确认无）。
            val adapterClass = adapter.javaClass
            if (notifyDataSetChangeMethodOwner != adapterClass) {
                var found: Method? = null
                var clazz: Class<*>? = adapterClass
                while (clazz != null && clazz != Any::class.java && found == null) {
                    for (method in clazz.declaredMethods) {
                        if (method.name == "notifyDataSetChanged" && method.parameterTypes.isEmpty()) {
                            found = method
                            break
                        }
                    }
                    clazz = clazz.superclass
                }
                notifyDataSetChangeMethod = found
                notifyDataSetChangeMethodOwner = adapterClass
                found?.isAccessible = true
            }
            val method = notifyDataSetChangeMethod ?: run {
                // 仅在首次确认不存在时记日志，避免每次过滤重扫 + 刷屏。
                AndroidLog.e(TAG, "notifyDataSetChanged not found on ${adapterClass.name}")
                return
            }
            method.invoke(adapter)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "adapter full rebind failed: ${t.message}")
        }
    }

    private fun resolveAdapter(scrollView: Any): Any? = try {
        // getListAdapter 反射结果按宿主类缓存：owner 相同直接复用（owner 已记录而方法为空 = 已确认无）。
        val scrollViewClass = scrollView.javaClass
        if (getListAdapterMethodOwner != scrollViewClass) {
            getListAdapterMethod = scrollViewClass.declaredMethods.firstOrNull { m ->
                m.name == "getListAdapter" && m.parameterTypes.isEmpty()
            }
            getListAdapterMethodOwner = scrollViewClass
            getListAdapterMethod?.isAccessible = true
        }
        val getter = getListAdapterMethod
        if (getter == null) {
            // 仅在首次确认不存在时记日志，避免每次过滤重扫 + 刷屏。
            AndroidLog.e(TAG, "getListAdapter() not found")
            null
        } else {
            getter.invoke(scrollView) ?: run {
                AndroidLog.e(TAG, "getListAdapter() returned null")
                null
            }
        }
    } catch (t: Throwable) {
        AndroidLog.e(TAG, "resolve adapter failed: ${t.message}")
        null
    }

    // ---- 高亮（onBindViewHolder 后处理，主线程碰 View） ----

    private fun hookSetList(classLoader: ClassLoader) {
        val scrollClass = resolveClipboardScrollView(classLoader)
            ?: throw ClassNotFoundException(SCROLLVIEW_CLASS)
        var count = 0
        for (method in scrollClass.declaredMethods) {
            if (method.name != "setList") continue
            if (method.parameterTypes.size != 1) continue
            if (!List::class.java.isAssignableFrom(method.parameterTypes[0])) continue
            try {
                method.isAccessible = true
                method.hookAfter { param ->
                    try {
                        val arg = param.args.getOrNull(0)
                        onNativeSetList(param.thisObject, arg)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "setList hook dispatch failed: ${t.message}")
                    }
                }
                count++
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "hook setList failed: ${t.message}")
            }
        }
        AndroidLog.i(TAG, "hooked setList overloads: $count")
    }

    private fun hookBind(classLoader: ClassLoader) {
        val adapterClass = resolveClipboardAdapter(classLoader) ?: run {
            AndroidLog.e(TAG, "clipboard adapter class not found, skip highlight hook")
            return
        }
        var count = 0
        for (method in adapterClass.declaredMethods) {
            if (method.name != "onBindViewHolder") continue
            if (method.returnType != Void.TYPE) continue
            val params = method.parameterTypes
            val isPayload = params.size == 3 &&
                params[1] == Int::class.javaPrimitiveType &&
                List::class.java.isAssignableFrom(params[2])
            if (params.size == 2 && params[1] != Int::class.javaPrimitiveType) continue
            if (params.size != 2 && !isPayload) continue
            try {
                method.isAccessible = true
                method.hookAfter { param ->
                    try {
                        if (isPayload) {
                            val payloads = param.argumentOrNull(2) as? Collection<*>
                            if (payloads.isNullOrEmpty()) return@hookAfter
                        }
                        val holder = param.args.getOrNull(0) ?: return@hookAfter
                        // 图片条目 onBind 已由 WeTypeClipboardHooks 的统一 dispatcher 调用；
                        // 这里只负责搜索高亮，避免同一次绑定重复执行图片后处理。
                        if (WeTypeSettings.isClipboardSearchEnabledXposed()) {
                            applyHighlightToHolder(holder, currentKeyword)
                        }
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "bind highlight dispatch failed: ${t.message}")
                    }
                }
                count++
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "hook onBindViewHolder failed: ${t.message}")
            }
        }
        AndroidLog.i(TAG, "hooked onBindViewHolder overloads: $count")
    }

    private fun applyHighlightToHolder(holder: Any, keyword: String) {
        try {
            val itemView = getHolderItemView(holder) ?: return
            forEachTextView(itemView, keyword)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "applyHighlight failed: ${t.message}")
        }
    }

    private fun getHolderItemView(holder: Any): View? {
        try {
            // 热路径（每行绑定一次）：优先读缓存字段列表；owner 失配才沿父类链重扫一次。
            val holderClass = holder.javaClass
            if (holderViewFieldsOwner != holderClass) {
                holderViewFields = collectHolderViewFields(holderClass)
                holderViewFieldsOwner = holderClass
            }
            val fields = holderViewFields ?: return null
            for (field in fields) {
                try {
                    // 声明顺序与原「逐层扫描取第一个非 null View」一致，取第一个有值的。
                    val view = field.get(holder) as? View ?: continue
                    return view
                } catch (_: Throwable) {
                    continue
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "get holder itemView failed: ${t.message}")
        }
        return null
    }

    /**
     * 沿父类链（到 Object 为止）收集 View 类型的 declared 字段，供 [getHolderItemView] 按
     * holder 类缓存。空列表表示该类没有 View 字段——同样缓存（null 也缓存），不再重扫。
     */
    private fun collectHolderViewFields(holderClass: Class<*>): List<Field> {
        val out = ArrayList<Field>(4)
        var clazz: Class<*>? = holderClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                try {
                    if (!View::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    out.add(field)
                } catch (_: Throwable) {
                    // isAccessible 失败的字段跳过，与原逻辑容错一致。
                }
            }
            clazz = clazz.superclass
        }
        return out
    }

    private fun forEachTextView(view: View, keyword: String) {
        try {
            if (view is TextView) {
                highlightTextView(view, keyword)
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    try {
                        forEachTextView(view.getChildAt(i), keyword)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "traverse child failed: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "forEachTextView failed: ${t.message}")
        }
    }

    private fun highlightTextView(tv: TextView, keyword: String) {
        try {
            // 上一轮可能把正文换成了「命中片段」，先还原成完整原文再重算。
            restoreSnippet(tv)
            pendingSnippets.remove(tv)
            // 重绑作废旧片段：顺手摘掉排版监听，命中不可见时下方 schedule 会重新挂上。
            unwatchSnippet(tv)
            // 先清除上一轮同色高亮，恢复原样后再按当前关键词重打。
            clearOurSpans(tv)
            if (keyword.isEmpty()) return
            val original = tv.text ?: return
            val text = original.toString()
            if (text.isEmpty()) return
            // 宿主只把正文的前一段放进卡片（实机：13415 字的条目卡片里只有前 3000 字），命中落在
            // 被截掉的部分时，卡片文本里压根没有它 —— 只搜卡片文本就永远标不出来。
            val source = resolveFullContent(text)
            val ranges = try {
                synchronized(highlightRangeCache) {
                    highlightRangeCache.getOrPut(keyword to source) {
                        val hits = ClipboardSearchEngine.search(listOf(source), keyword, 1)
                        if (hits.isEmpty()) emptyList() else hits[0].ranges
                    }
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "highlight search failed: ${t.message}")
                return
            }
            if (ranges.isEmpty()) return
            val firstStart = colorRanges(tv, original, ranges)
            if (firstStart >= 0) scrollContentToHighlight(tv, firstStart)
            scheduleSnippetIfMatchOffscreen(tv, original, source, ranges)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "highlightTextView failed: ${t.message}")
        }
    }

    /**
     * 卡片文本是某条完整内容的前缀（宿主截断了显示）时返回那条完整内容，否则就是卡片文本本身。
     *
     * 只认够长的文本：药丸标签、操作栏按钮这类几个字的控件，其文字完全可能恰好是某条内容的开头，
     * 认成前缀会把它们的文字换成别的条目的内容。
     */
    private fun resolveFullContent(text: String): String {
        if (text.length < TRUNCATED_TEXT_MIN_CHARS) return text
        for (content in shownContents) {
            if (content.length > text.length && content.startsWith(text)) return content
        }
        return text
    }

    /** 把 [ranges] 逐个染上高亮色并写回，返回首个命中区间的起点（没染上返回 -1）。 */
    private fun colorRanges(tv: TextView, source: CharSequence, ranges: List<IntRange>): Int {
        val spannable = SpannableString(source)
        var firstStart = -1
        for (range in ranges) {
            try {
                val start = range.first.coerceIn(0, spannable.length)
                val endExclusive = (range.last + 1).coerceIn(0, spannable.length)
                if (start >= endExclusive) continue
                spannable.setSpan(
                    ForegroundColorSpan(HIGHLIGHT_COLOR),
                    start,
                    endExclusive,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (firstStart < 0) firstStart = start
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "apply span range failed: ${t.message}")
            }
        }
        // 必须是 NORMAL：SPANNABLE 会让 TextView 改用 DynamicLayout，它不按 maxLines 封顶、也没有省略号，
        // 宿主瀑布流的 rebuildTextLayout（判据是 lineCount > maxLines）就永远收敛不了，实机表现为一张长卡片
        // 每帧都在重新排版（空闲 4 秒 255 次）。NORMAL 会把 Spanned 包成 SpannedString，区间保留、走 StaticLayout。
        tv.setText(spannable, TextView.BufferType.NORMAL)
        return firstStart
    }

    /**
     * 「命中片段」待办。[original] 是卡片当前文本，[source] 是完整内容。
     * [applied] 非空表示片段已经换上去了，正在等它自己的排版来验证命中看得见（[appliedMatchEnd] 是命中在片段里的终点）。
     */
    private class PendingSnippet(
        val original: CharSequence,
        val source: String,
        val ranges: List<IntRange>,
        val applied: CharSequence? = null,
        val appliedMatchEnd: Int = -1,
        val round: Int = 0
    )

    /** 还在等排版、可能要换成命中片段的卡片正文。重新绑定或清空关键词时作废。 */
    private val pendingSnippets: MutableMap<TextView, PendingSnippet> =
        Collections.synchronizedMap(WeakHashMap())

    /** 已挂上排版监听的正文（监听只挂一次，`addOnLayoutChangeListener` 自己不去重）。 */
    private val snippetWatched: MutableSet<TextView> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<TextView, Boolean>()))

    private val snippetLayoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        (v as? TextView)?.let { evaluateSnippet(it) }
    }

    /**
     * 片段流程终结时摘掉排版监听：listener 挂上后从不 remove 的话，View 存活期间每次布局
     * 都会回调一次 evaluateSnippet（空跑早退）。所有「这条正文不再有待办片段」的收口点都要调用；
     * 摘掉后如果再次命中不可见，[scheduleSnippetIfMatchOffscreen] 会经 [snippetWatched] 重新挂上。
     */
    private fun unwatchSnippet(tv: TextView) {
        try {
            // 只有确实挂着的才 remove，避免对未监听的 View 空操作。
            if (snippetWatched.remove(tv)) {
                tv.removeOnLayoutChangeListener(snippetLayoutListener)
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "unwatch snippet failed: ${t.message}")
        }
    }

    /**
     * 命中落在卡片可见行之外时，把正文换成「命中附近的片段」，前面加省略号。
     *
     * 瀑布流会把正文按「卡片最大高度」压成几行（`maxLines` + `ellipsize`），而搜索是按**整条内容**
     * 匹配的 —— 只搜两个拉丁字母时走首字母拼音路径（例如「在吗」「怎么」的 z…m），命中的汉字可能在
     * 很后面。这时 span 确实打上去了，却落在被 `maxLines` 裁掉的那几行里，用户看到的就是「这条没标红」。
     *
     * **判断必须挂在排版上，不能算一次就收工**：绑定当下的排版是上一条内容留下的，`maxLines` 随后又会被
     * 估算、被布局后纠偏改小（而且改小之后排版不一定重建），宽度也要等条目量到才定。实机第一版是 `post`
     * 之后算一次，那一刻 `maxLines=4` 而排版还有 13 行、没有省略号，于是「看不见」被判成「看得见」，
     * 片段一次都没触发过。现在挂一个排版监听，每次排版后重判，直到换成片段或重新绑定。
     *
     * 片段按原卡片能显示的字数取窗口、命中放中间（[ClipboardSnippetLogic.snippetWindowStart]），
     * 换上去后再用片段自己的排版验证：命中被截掉就按量到的可见字数收窄窗口重来（最多 [SNIPPET_MAX_ROUNDS] 轮），
     * 实在不行才退回紧凑片段（命中必在第一行）。
     */
    private fun scheduleSnippetIfMatchOffscreen(
        tv: TextView,
        original: CharSequence,
        source: String,
        ranges: List<IntRange>
    ) {
        if (ranges.first().first <= 0) return
        pendingSnippets[tv] = PendingSnippet(original, source, ranges)
        if (snippetWatched.add(tv)) tv.addOnLayoutChangeListener(snippetLayoutListener)
        tv.post { evaluateSnippet(tv) }
    }

    private fun evaluateSnippet(tv: TextView) {
        try {
            val pending = pendingSnippets[tv] ?: return
            val applied = pending.applied
            // 这一帧已经被重绑/改写，不是我们写进去的那份文本就作废。
            if (!TextUtils.equals(tv.text, applied ?: pending.original)) {
                pendingSnippets.remove(tv)
                unwatchSnippet(tv) // 作废即终结：摘掉排版监听，别让它空跑到 View 回收。
                return
            }
            val layout = tv.layout
            // 排版还是别的文本的（长度对不上）或宽度没量出来，等下一次排版。
            val expectedLength = (applied ?: pending.original).length
            if (layout == null || layout.text.length != expectedLength || tv.width <= 0 || layout.lineCount <= 0) return
            val visibleEnd = visibleEnd(layout, tv.maxLines)
            if (applied != null) {
                // 片段自己的排版出来了：命中还被截掉（片段里的字比原文宽）就退回紧凑片段，只验证这一次。
                pendingSnippets.remove(tv)
                unwatchSnippet(tv) // 片段验证流程终结，摘掉排版监听（下方续轮如需要会重新挂上）。
                if (ClipboardSnippetLogic.isMatchHidden(pending.appliedMatchEnd, visibleEnd)) {
                    // 片段里的字比原文宽，窗口取大了：按这次量到的可见字数收窄再来，别一下子退成只剩一行。
                    if (pending.round < SNIPPET_MAX_ROUNDS) {
                        applySnippet(
                            tv, pending, (visibleEnd - SNIPPET_SHRINK_MARGIN).coerceAtLeast(1),
                            compact = false, round = pending.round + 1
                        )
                    } else {
                        applySnippet(tv, pending, visibleEnd = 0, compact = true, round = pending.round)
                    }
                }
                return
            }
            val first = pending.ranges.first()
            // 命中整个落在卡片文本之外（宿主截断了）一定看不见；否则按排版判断。
            val hidden = first.last >= pending.original.length ||
                ClipboardSnippetLogic.isMatchHidden(first.last, visibleEnd)
            if (hidden) applySnippet(tv, pending, visibleEnd, compact = false, round = 0)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "evaluate highlight snippet failed: ${t.message}")
        }
    }

    /** 把排版量出来的数交给纯算术：卡片上看得见的文字到哪个下标为止，规则见 [ClipboardSnippetLogic.visibleEnd]。 */
    private fun visibleEnd(layout: android.text.Layout, maxLines: Int): Int {
        val last = layout.lineCount - 1
        val capLine = (maxLines - 1).coerceIn(0, last)
        return ClipboardSnippetLogic.visibleEnd(
            lineCount = layout.lineCount,
            maxLines = maxLines,
            capLineEnd = layout.getLineEnd(capLine),
            lastLineStart = layout.getLineStart(last),
            lastLineEnd = layout.getLineEnd(last),
            ellipsisStart = layout.getEllipsisStart(last),
            ellipsisCount = layout.getEllipsisCount(last)
        )
    }

    /**
     * 把正文换成命中附近的片段（前面补省略号），原文留在 [snippetOriginals] 里供还原。
     * [visibleEnd] 是原卡片能显示的字数，用来决定窗口从哪里开始；[compact] 为真时退回紧凑片段。
     */
    private fun applySnippet(
        tv: TextView,
        pending: PendingSnippet,
        visibleEnd: Int,
        compact: Boolean,
        round: Int
    ) {
        val first = pending.ranges.first()
        val compactStart = ClipboardSnippetLogic.snippetStart(first.first, SNIPPET_LEAD_CHARS)
        var start = if (compact) {
            compactStart
        } else {
            ClipboardSnippetLogic.snippetWindowStart(first.first, pending.source.length, visibleEnd)
        }
        // 窗口算出来贴着开头（等于没换）时，至少用紧凑片段，否则命中仍在看不见的位置。
        if (start <= 0) start = compactStart
        if (start <= 0) {
            pendingSnippets.remove(tv)
            unwatchSnippet(tv) // 不换片段即终结：摘掉排版监听。
            return
        }
        val endExclusive = minOf(pending.source.length, start + SNIPPET_MAX_CHARS)
        val head = SNIPPET_ELLIPSIS
        val snippet = SpannableStringBuilder().append(head).append(pending.source, start, endExclusive)
        for (range in pending.ranges) {
            val s0 = (range.first - start).coerceAtLeast(0) + head.length
            val s1 = (range.last + 1 - start).coerceAtMost(endExclusive - start) + head.length
            if (range.last + 1 <= start || range.first >= endExclusive || s0 >= s1) continue
            snippet.setSpan(ForegroundColorSpan(HIGHLIGHT_COLOR), s0, s1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        snippetOriginals[tv] = pending.original
        // 紧凑片段本身就保证命中在第一行，不用再验证。
        if (compact) {
            pendingSnippets.remove(tv)
            unwatchSnippet(tv) // 紧凑片段即终结：摘掉排版监听。
        } else {
            pendingSnippets[tv] = PendingSnippet(
                pending.original, pending.source, pending.ranges,
                applied = snippet, appliedMatchEnd = first.last - start + head.length, round = round
            )
            // 收窄续轮是从 evaluateSnippet 直接调进来的，那里可能刚摘过监听：这里补挂，
            // 保证片段自己的排版出来后仍能收到布局回调完成验证。
            if (snippetWatched.add(tv)) tv.addOnLayoutChangeListener(snippetLayoutListener)
        }
        tv.setText(snippet, TextView.BufferType.NORMAL)
    }

    /** 把 [snippetOriginals] 里存着的完整原文还回去（正文没被我们换过就什么都不做）。 */
    private fun restoreSnippet(tv: TextView) {
        val original = snippetOriginals.remove(tv) ?: return
        try {
            if (TextUtils.equals(tv.text, original)) return
            tv.setText(original, TextView.BufferType.NORMAL)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "restore snippet failed: ${t.message}")
        }
    }

    /**
     * 命中片段在可视区之外时，把内容行所在的横向滚动容器滚到首个高亮处，
     * 让长文本的匹配结果直接落在这一行里（原生只会在绑定时复位到 0）。
     */
    private fun scrollContentToHighlight(tv: TextView, offset: Int) {
        try {
            val scroll = tv.parent as? HorizontalScrollView ?: return
            // 布局用的是 transformation 后的文本（SingleLineTransformationMethod 会把
            // \r/\n 换成 \uFEFF，且保持 1:1 长度），必须按变换后的内容与 layout.text 比较。
            val expectedText = runCatching {
                tv.transformationMethod?.getTransformation(tv.text, tv)
            }.getOrNull() ?: tv.text
            scrollToHighlightAttempt(tv, scroll, offset, expectedText, 0)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "scroll content to highlight failed: ${t.message}")
        }
    }

    private fun scrollToHighlightAttempt(
        tv: TextView,
        scroll: HorizontalScrollView,
        offset: Int,
        expectedText: CharSequence?,
        attempt: Int
    ) {
        if (attempt > 10) return
        try {
            tv.postDelayed({
                try {
                    val layout = tv.layout
                    if (layout != null && TextUtils.equals(layout.text, expectedText) &&
                        tv.width > 0 && scroll.width > 0
                    ) {
                        scrollViewToOffset(tv, scroll, layout, offset)
                    } else {
                        scrollToHighlightAttempt(tv, scroll, offset, expectedText, attempt + 1)
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "scroll content to highlight failed: ${t.message}")
                }
            }, 16L)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "schedule scroll to highlight failed: ${t.message}")
        }
    }

    private fun scrollViewToOffset(
        tv: TextView,
        scroll: HorizontalScrollView,
        layout: android.text.Layout,
        offset: Int
    ) {
        if (offset < 0 || offset >= layout.text.length) return
        val viewport = scroll.width - scroll.paddingLeft - scroll.paddingRight
        if (viewport <= 0) return
        val maxScroll = (tv.width + scroll.paddingLeft + scroll.paddingRight - scroll.width)
            .coerceAtLeast(0)
        if (maxScroll <= 0) return
        val x = tv.left + tv.compoundPaddingLeft + layout.getPrimaryHorizontal(offset)
        val lead = (tv.resources.displayMetrics.density * 12f).toInt().coerceAtMost(viewport / 3)
        val target = (x - lead).toInt().coerceIn(0, maxScroll)
        if (scroll.scrollX != target) {
            scroll.scrollTo(target, 0)
            AndroidLog.i(TAG, "scroll to highlight: offset=$offset x=${x.toInt()} target=$target max=$maxScroll")
        }
    }

    private fun clearOurSpans(tv: TextView) {
        try {
            val spanned = tv.text as? Spanned ?: return
            val spans = spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
            if (spans.isEmpty()) return
            // tv.text 可能是不可变 Spanned：转可变后再清，避免直接 remove 抛异常。
            val editable = SpannableString(spanned)
            var changed = false
            for (span in spans) {
                try {
                    if (span.foregroundColor != HIGHLIGHT_COLOR) continue
                    editable.removeSpan(span)
                    changed = true
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "remove span failed: ${t.message}")
                }
            }
            if (changed) tv.setText(editable, TextView.BufferType.NORMAL)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "clear spans failed: ${t.message}")
        }
    }

    // ---- C 条目反射读写（数据面，后台/主线程均可调，不碰 View） ----

    private fun getItemType(item: Any): Long {
        try {
            var m = getTypeMethod
            if (m == null || m.declaringClass != item.javaClass) {
                // S7o：装机版 C.getType 改名（NoSuchMethod 导致全保留、过滤无事
                // 发生）。jadx 354 批注 `renamed from: q` + 真机普查 q():long=0
                // 在文本条目上吻合 type==0，三重印证，直接绑 q。
                m = pickHostMethod(item.javaClass, "q") { c ->
                    c.parameterTypes.isEmpty() &&
                        (c.returnType == Long::class.javaPrimitiveType ||
                            c.returnType == Long::class.java ||
                            c.returnType == Int::class.javaPrimitiveType ||
                            c.returnType == Integer::class.java)
                }
                if (m == null) {
                    AndroidLog.e(TAG, "C.q() not found on ${item.javaClass.name}, keep item")
                    return 1L
                }
                m.isAccessible = true
                getTypeMethod = m
                AndroidLog.i(TAG, "C type accessor bound: q() on ${item.javaClass.name}")
            }
            val v = m.invoke(item)
            return when (v) {
                is Long -> v
                is Int -> v.toLong()
                is Number -> v.toLong()
                else -> 1L
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "getType failed: ${t.message}")
            // 读不到类型时按非文本保留，避免误删数据。
            return 1L
        }
    }

    private fun getItemContent(item: Any): String? {
        try {
            var m = getContentMethod
            if (m == null || m.declaringClass != item.javaClass) {
                // S7o：同上，content 绑 a()（普查 a() 即条目正文，三重印证）。
                // 绑不上返回 null，调用方保留条目。
                m = pickHostMethod(item.javaClass, "a") { c ->
                    c.parameterTypes.isEmpty() &&
                        c.returnType == String::class.java
                }
                if (m == null) {
                    AndroidLog.e(TAG, "C.a() not found on ${item.javaClass.name}, keep item")
                    return null
                }
                m.isAccessible = true
                getContentMethod = m
                AndroidLog.i(TAG, "C content accessor bound: a() on ${item.javaClass.name}")
            }
            return m.invoke(item) as? String
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "getContent failed: ${t.message}")
            return null
        }
    }

    /** 契约优先、写死的短名兜底。契约解析出来的 Class 自带宿主 ClassLoader。 */
    private fun resolveClipboardScrollView(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_SCROLLVIEW) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(SCROLLVIEW_CLASS, false, it) }.getOrNull()
            }

    private fun resolveClipboardAdapter(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_ADAPTER) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(ADAPTER_CLASS, false, it) }.getOrNull()
            }
}

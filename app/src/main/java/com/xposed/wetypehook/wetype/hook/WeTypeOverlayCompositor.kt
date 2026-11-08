package com.xposed.wetypehook.wetype.hook

import android.graphics.Canvas
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.loadClassOrNull
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

private const val SETTINGS_KEYBOARD = "com.tencent.wetype.plugin.hld.keyboard.S10SettingsKeyboard"
private const val CANDIDATE_VIEW = "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView"
private const val SETTINGS_RECYCLER = "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingRecyclerView"
private val OVERLAY_KEYBOARDS = setOf(
    SETTINGS_KEYBOARD,
    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S11EmojiKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S15CustomPhraseAndClipboardKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S29ClipboardCleanRecordKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S34ClipboardBombKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S35RequestAIKeyboard"
)
private val SETTINGS_PAGES = setOf(
    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingKeyboardTypeView",
    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingCustomToolbarView",
    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingPlusModeOpenView",
    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingOfflineVoiceGuideView"
)

/**
 * Transparent skins remove the host's opaque occlusion. Restore that occlusion at
 * composition time, without taking ownership of visibility, alpha or animations.
 */
internal object WeTypeOverlayCompositor {
    /**
     * 一次结构遍历得到的角色节点缓存。每帧只刷新这些节点的 rendered/opacity，
     * 不再重复遍历整棵视图树；只有布局/结构变化或缓存失效时才重建。
     */
    private class CachedLayer(
        val id: Int,
        val role: WeTypeLayerRole,
        val ancestors: List<Int>,
        val settingsOwner: Int?,
        val view: WeakReference<View>,
        /** 自身 → 根节点的弱引用链，用于低成本的每帧可见性/透明度刷新 */
        val viewChain: List<WeakReference<View>>,
        var bounds: WeTypeLayerBounds?,
        val settingsContentGetter: Method?
    ) {
        var rendered = true
        var opacity = 1f
    }

    private class WindowFrame {
        var observer: WeakReference<ViewTreeObserver>? = null
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
        var maskedViews: List<Pair<WeakReference<View>, Int>> = emptyList()
        /** 布局/结构变化标记：由 OnGlobalLayout 置位，消费后清除 */
        var dirty = true
        var cachedLayers: List<CachedLayer> = emptyList()
        var cachedViews: Map<Int, WeakReference<View>> = emptyMap()
        var cachedSnapshots: List<WeTypeLayerSnapshot> = emptyList()
        var cachedToolsRequested: Boolean? = null
        var hasCachedTools = false
    }

    private val windows = WeakHashMap<View, WindowFrame>()
    private val drawMasks = Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** 当前是否存在生效遮罩；为 false（常态）时 drawChild 全局 hook 直接放行，免去每帧同步查表 */
    @Volatile
    private var masksActive = false

    /** 每帧复用的父节点索引表（id → 父 id，根为 -1），避免每个节点每帧分配 ancestors 列表 */
    private var parentIds = IntArray(256)
    private val keyboardClasses = WeakHashMap<Class<*>, Boolean>()
    private val settingsContentGetters = WeakHashMap<Class<*>, Method?>()
    private var settingsManager: Any? = null
    private var toolsRequestedGetter: Method? = null
    private var settingsChromeIds: Set<Int> = emptySet()

    fun install() {
        resolveSettingsStateReader()
        val resourceClass = loadClassOrNull("com.tencent.wetype.plugin.hld.s")
        // Host-owned chrome has two sibling branches: back/title and the lazily
        // added settings toolbar (settings / file transfer). Neither is candidates.
        settingsChromeIds = listOf("setting_container_rl", "setting_toolbar_recyclerview")
            .mapNotNull { name ->
                runCatching { resourceClass?.getDeclaredField(name)?.getInt(null) }.getOrNull()
            }.toSet()
        runCatching {
            val drawChild = ViewGroup::class.java.getDeclaredMethod(
                "drawChild", Canvas::class.java, View::class.java, Long::class.javaPrimitiveType
            ).apply { isAccessible = true }
            HookEnvironment.registerHook(drawChild, "overlay-composition") { chain ->
                val child = chain.args[1] as? View
                val canvas = chain.args[0] as? Canvas
                // masksActive 为 false 时跳过同步 map 查找，hook 只付一次 volatile 读
                val drawingAlpha = if (masksActive) child?.let { drawMasks[it] } else null
                if (child == null || canvas == null || drawingAlpha == null) {
                    chain.proceed()
                } else {
                    // An empty clip/early return can skip descendant legacy animations.
                    // A transparent layer retains the entire native draw traversal.
                    withWeTypeTransparentDrawing(
                        saveTransparentLayer = { canvas.saveLayerAlpha(null, drawingAlpha) },
                        restore = canvas::restoreToCount,
                        draw = { chain.proceed() }
                    )
                }
            }
            val serviceClass = InputMethodService::class.java
            serviceClass.getMethod("onWindowShown").hookAfter { registerServiceWindow(it.thisObject) }
            serviceClass.getMethod("onStartInputView", EditorInfo::class.java,
                Boolean::class.javaPrimitiveType).hookAfter { registerServiceWindow(it.thisObject) }
            listOf("onWindowHidden", "hideWindow", "onDestroy").forEach { name ->
                serviceClass.getMethod(name).hookAfter { param ->
                    serviceDecor(param.thisObject)?.let(::removeWindow)
                }
            }
            Log.i("Success: Hook WeType transparent layer composition")
        }.onFailure {
            Log.i("Failed: Hook WeType transparent layer composition")
            Log.i(it)
        }
    }

    private fun serviceDecor(service: Any): View? =
        (service as? InputMethodService)?.takeIf { it.packageName == "com.tencent.wetype" }
            ?.window?.window?.decorView

    private fun registerServiceWindow(service: Any) {
        serviceDecor(service)?.let(::registerWindow)
    }

    fun reconcile(rootViews: List<View>) {
        rootViews.map { it.rootView }.distinct().filter(::containsHostLayer).forEach(::registerWindow)
    }

    /**
     * 悬浮键盘的 PopupWindow 不经过 InputMethodService 的窗口生命周期回调，且宿主
     * 把浮动视图的 getRootView() 重写为背景容器，reconcile 的 containsHostLayer
     * 过滤会漏掉整棵浮动树。悬浮模式下的面板遮挡改为直接从 PopupWindow 内容根
     * 注册逐帧观察，复用同一套图层合成与 drawChild 遮罩。
     */
    fun registerFloatingRoot(root: View) {
        if (root is ViewGroup) registerWindow(root)
    }

    private fun containsHostLayer(view: View): Boolean {
        if (view.javaClass.name == CANDIDATE_VIEW || isKeyboardRoot(view)) return true
        return view is ViewGroup && (0 until view.childCount).any {
            containsHostLayer(view.getChildAt(it))
        }
    }

    private fun registerWindow(decor: View) {
        val frame = windows.getOrPut(decor) { WindowFrame() }
        val observer = decor.viewTreeObserver
        if (!observer.isAlive) return
        if (frame.observer?.get() !== observer) {
            removeListener(frame, observer)
            val rootReference = WeakReference(decor)
            val listener = ViewTreeObserver.OnPreDrawListener {
                rootReference.get()?.let { root ->
                    runCatching { updateFrame(root, frame) }.onFailure {
                        clearMasks(frame)
                        Log.i("Failed: Compose WeType layers")
                        Log.i(it)
                    }
                }
                true
            }
            frame.observer = WeakReference(observer)
            frame.listener = listener
            observer.addOnPreDrawListener(listener)
            // 布局/结构变化时重建角色缓存；稳态绘制帧只刷新少数组件的透明度。
            val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { frame.dirty = true }
            frame.layoutListener = layoutListener
            observer.addOnGlobalLayoutListener(layoutListener)
        }
        frame.dirty = true
        updateFrame(decor, frame)
    }

    private fun updateFrame(decor: View, frame: WindowFrame) {
        if (frame.dirty) {
            frame.dirty = false
            rebuildLayers(decor, frame)
        }
        if (frame.cachedLayers.isEmpty()) {
            // 无角色节点时不再每帧遍历整棵树
            return
        }
        val toolsRequested = runCatching {
            toolsRequestedGetter?.invoke(settingsManager) as? Boolean
        }.getOrNull()
        val toolsChanged = !frame.hasCachedTools || frame.cachedToolsRequested != toolsRequested
        val opacityChanged = refreshCachedLayers(frame)
        if (!toolsChanged && !opacityChanged) return
        frame.cachedToolsRequested = toolsRequested
        frame.hasCachedTools = true
        applyLayerMasks(frame, toolsRequested)
    }

    /** 结构/布局变化时重建角色节点缓存；此路径不再是每帧热路径。 */
    private fun rebuildLayers(decor: View, frame: WindowFrame) {
        val layers = ArrayList<CachedLayer>()
        val views = HashMap<Int, WeakReference<View>>()
        val recyclerCache = WeakHashMap<View, Boolean>()
        var nextId = 0

        fun roleOf(view: View, name: String, owner: Int?, candidateOwner: Int?): WeTypeLayerRole? =
            when {
                name in OVERLAY_KEYBOARDS -> WeTypeLayerRole.OverlayKeyboard
                name == CANDIDATE_VIEW -> WeTypeLayerRole.Candidate
                candidateOwner != null && view.id in settingsChromeIds ->
                    WeTypeLayerRole.SettingsChrome
                name in SETTINGS_PAGES -> WeTypeLayerRole.SettingsPage
                owner != null && view is LinearLayout && containsSettingsRecycler(view, recyclerCache) ->
                    WeTypeLayerRole.SettingsTools
                isKeyboardRoot(view) -> WeTypeLayerRole.Keyboard
                candidateOwner != null -> WeTypeLayerRole.CandidateContent
                else -> null
            }

        fun visit(view: View, parentId: Int, parentRendered: Boolean, settingsOwner: Int?,
                  candidateOwner: Int?, parentOpacity: Float, offsetX: Int, offsetY: Int) {
            val id = nextId++
            if (id >= parentIds.size) parentIds = parentIds.copyOf((id + 1) * 2)
            parentIds[id] = parentId
            val rendered = parentRendered && view.isAttachedToWindow &&
                (view.visibility == View.VISIBLE || view.animation?.hasEnded() == false) &&
                view.alpha > 0f && view.width > 0 && view.height > 0
            if (!rendered) return
            val name = view.javaClass.name
            val owner = if (name == SETTINGS_KEYBOARD) id else settingsOwner
            val candidate = if (name == CANDIDATE_VIEW) id else candidateOwner
            val effectiveOpacity = parentOpacity * view.alpha
            val left = offsetX + view.left
            val top = offsetY + view.top
            val role = roleOf(view, name, owner, candidate)
            if (role != null) {
                val contentGetter = if (role == WeTypeLayerRole.SettingsPage) {
                    settingsContentGetters.getOrPut(view.javaClass) {
                        runCatching { view.javaClass.getMethod("getSettingBaseGridViewContainer") }.getOrNull()
                    }
                } else null
                val opacity = if (role == WeTypeLayerRole.SettingsPage) {
                    // The host animates the page's content and below-background alpha,
                    // not the root FrameLayout. Read the same native content branch.
                    val content = runCatching { contentGetter?.invoke(view) as? View }.getOrNull()
                    view.alpha * (content?.alpha ?: 1f)
                } else effectiveOpacity
                // 仅为角色节点（每帧数量少）构造祖先链；其余节点只写 parentIds 复用表。
                val ancestors = ArrayList<Int>()
                var ancestorId = parentId
                while (ancestorId >= 0) {
                    ancestors.add(ancestorId)
                    ancestorId = parentIds[ancestorId]
                }
                // 角色节点数量有限，仅在重建时构建自身→根的弱引用链，供每帧刷新使用。
                val chain = ArrayList<WeakReference<View>>(ancestors.size + 1)
                chain.add(WeakReference(view))
                var parent: ViewParent? = view.parent
                while (parent is View) {
                    chain.add(WeakReference(parent))
                    parent = parent.parent
                }
                layers.add(
                    CachedLayer(
                        id, role, ancestors, owner, WeakReference(view), chain,
                        WeTypeLayerBounds(left, top, left + view.width, top + view.height),
                        contentGetter
                    )
                )
                views[id] = WeakReference(view)
            }
            if (view is ViewGroup) {
                // The host's keyboard containers use standard child order. Z is applied
                // before index, matching the framework's stable preordered child list.
                val count = view.childCount
                var allDefaultZ = true
                for (index in 0 until count) {
                    if (view.getChildAt(index).z != 0f) {
                        allDefaultZ = false
                        break
                    }
                }
                if (allDefaultZ) {
                    for (index in 0 until count) {
                        visit(view.getChildAt(index), id, rendered, owner, candidate, effectiveOpacity, left, top)
                    }
                } else {
                    (0 until count).map(view::getChildAt).sortedBy { it.z }.forEach {
                        visit(it, id, rendered, owner, candidate, effectiveOpacity, left, top)
                    }
                }
            }
        }

        visit(decor, -1, decor.isShown, null, null, 1f, 0, 0)
        frame.cachedLayers = layers
        frame.cachedViews = views
        frame.cachedSnapshots = emptyList()
        frame.cachedToolsRequested = null
        frame.hasCachedTools = false
        if (layers.isEmpty()) {
            clearMasks(frame)
            return
        }
        refreshCachedLayers(frame)
        frame.cachedSnapshots = snapshotsOf(layers)
    }

    /**
     * 稳态帧只检查缓存的角色节点及其祖先链：不遍历整棵树，也不分配图层对象。
     * 返回 true 表示某个节点的可见性或透明度发生变化，需要重新计算遮罩。
     */
    private fun refreshCachedLayers(frame: WindowFrame): Boolean {
        var changed = false
        for (layer in frame.cachedLayers) {
            val self = layer.view.get()
            if (self == null) {
                frame.dirty = true
                return true
            }
            var rendered = true
            var parentOpacity = 1f
            var index = 0
            var left = 0
            var top = 0
            for (reference in layer.viewChain) {
                val view = reference.get()
                if (view == null) {
                    frame.dirty = true
                    return true
                }
                val isRoot = index == layer.viewChain.lastIndex
                if (!view.isAttachedToWindow || (isRoot && !view.isShown()) ||
                    view.alpha <= 0f || view.width <= 0 || view.height <= 0 ||
                    (view.visibility != View.VISIBLE && view.animation?.hasEnded() != false)
                ) {
                    rendered = false
                    break
                }
                left += view.left
                top += view.top
                // viewChain[0] 是角色节点自身，祖先透明度不包含自身 alpha
                if (index > 0) parentOpacity *= view.alpha
                index++
            }
            // 不构造临时 WeTypeLayerBounds：稳态帧零分配
            if (rendered) {
                val current = layer.bounds
                if (current == null || current.left != left || current.top != top ||
                    current.right != left + self.width || current.bottom != top + self.height
                ) {
                    layer.bounds = WeTypeLayerBounds(left, top, left + self.width, top + self.height)
                    changed = true
                }
            } else if (layer.rendered) {
                changed = true
            }
            val opacity = if (layer.role == WeTypeLayerRole.SettingsPage) {
                val content = runCatching {
                    layer.settingsContentGetter?.invoke(self) as? View
                }.getOrNull()
                self.alpha * (content?.alpha ?: 1f)
            } else {
                parentOpacity * self.alpha
            }
            if (layer.rendered != rendered || layer.opacity != opacity) {
                layer.rendered = rendered
                layer.opacity = opacity
                changed = true
            }
        }
        if (changed) frame.cachedSnapshots = snapshotsOf(frame.cachedLayers)
        return changed
    }

    private fun snapshotsOf(layers: List<CachedLayer>): List<WeTypeLayerSnapshot> =
        layers.map { layer ->
            WeTypeLayerSnapshot(
                layer.id, layer.role, layer.ancestors, layer.rendered,
                layer.settingsOwner, layer.opacity, layer.bounds
            )
        }

    private fun applyLayerMasks(frame: WindowFrame, toolsRequested: Boolean?) {
        val drawingAlphas = resolveWeTypeDrawAlphas(frame.cachedSnapshots, toolsRequested)
        val masked = drawingAlphas.mapNotNull { (id, alpha) ->
            frame.cachedViews[id]?.get()?.let { it to alpha }
        }.toMap()
        val previous = frame.maskedViews.mapNotNull { (reference, alpha) ->
            reference.get()?.let { it to alpha }
        }.toMap()
        (previous.keys - masked.keys).forEach { drawMasks.remove(it) }
        masked.forEach { (view, alpha) -> drawMasks[view] = alpha }
        masksActive = !drawMasks.isEmpty()
        // Hardware display lists can reuse the old masking command even when only
        // the host's alpha changed. Re-record affected parents when the plan changes.
        (previous.keys + masked.keys).filter { previous[it] != masked[it] }.forEach(::invalidateDrawing)
        frame.maskedViews = masked.map { (view, alpha) -> WeakReference(view) to alpha }
    }

    private fun isKeyboardRoot(view: View): Boolean {
        if (!view.javaClass.name.startsWith("com.tencent.wetype.plugin.hld.keyboard.")) return false
        return keyboardClasses.getOrPut(view.javaClass) {
            view.javaClass.methods.any { it.name == "getKeyboardType" && it.parameterCount == 0 }
        }
    }

    private fun containsSettingsRecycler(view: View, cache: MutableMap<View, Boolean>): Boolean {
        cache[view]?.let { return it }
        val result = view.javaClass.name == SETTINGS_RECYCLER || (view is ViewGroup && (0 until view.childCount).any {
            containsSettingsRecycler(view.getChildAt(it), cache)
        })
        cache[view] = result
        return result
    }

    private fun resolveSettingsStateReader() {
        // Verified against the actual 3.5.4 Tinker SettingsMgr: d() reads the same
        // showSettingToolbar StateFlow that o(boolean) updates during page rendering.
        runCatching {
            val clazz = loadClassOrNull("com.tencent.wetype.plugin.hld.settings.d")
                ?: error("No SettingsMgr")
            val singleton = clazz.declaredFields.single {
                Modifier.isStatic(it.modifiers) && it.type == clazz
            }.apply { isAccessible = true }.get(null)
            val getter = clazz.getDeclaredMethod("d").apply { isAccessible = true }
            check(getter.returnType == Boolean::class.javaPrimitiveType)
            check(getter.invoke(singleton) is Boolean)
            settingsManager = singleton
            toolsRequestedGetter = getter
        }.onFailure {
            // Unknown hosts still get visual occlusion from actual page views. Do
            // not guess a pending page from a timer or change their visibility.
            Log.i("Unavailable: WeType settings intent reader; using rendered layers")
        }
    }

    private fun invalidateDrawing(view: View) {
        view.invalidate()
        (view.parent as? View)?.invalidate()
    }

    private fun clearMasks(frame: WindowFrame) {
        frame.maskedViews.mapNotNull { it.first.get() }.forEach {
            drawMasks.remove(it)
            invalidateDrawing(it)
        }
        frame.maskedViews = emptyList()
        masksActive = !drawMasks.isEmpty()
    }

    private fun removeListener(frame: WindowFrame, currentObserver: ViewTreeObserver? = null) {
        val listener = frame.listener
        // A floating observer transfers its listeners when the decor attaches. The
        // stored observer may now be dead while the listener lives on the new one.
        if (listener != null) {
            listOfNotNull(frame.observer?.get(), currentObserver).distinct().forEach { observer ->
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            }
        }
        frame.layoutListener?.let { layoutListener ->
            listOfNotNull(frame.observer?.get(), currentObserver).distinct().forEach { observer ->
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(layoutListener)
            }
        }
        frame.observer = null
        frame.listener = null
        frame.layoutListener = null
    }

    private fun removeWindow(decor: View) {
        windows.remove(decor)?.let {
            removeListener(it, decor.viewTreeObserver)
            clearMasks(it)
        }
    }

    fun clearAll() {
        windows.entries.toList().forEach { (decor, frame) ->
            removeListener(frame, decor.viewTreeObserver)
            clearMasks(frame)
        }
        windows.clear()
        drawMasks.clear()
        masksActive = false
        keyboardClasses.clear()
        settingsContentGetters.clear()
        settingsManager = null
        toolsRequestedGetter = null
        settingsChromeIds = emptySet()
    }
}

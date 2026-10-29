package com.xposed.wetypehook

import android.app.Application
import android.util.Log
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class ModuleApplication : Application(), XposedServiceHelper.OnServiceListener {
    companion object {
        private const val TAG = "MIUIIME.Service"
        private const val WETYPE_IME_PROCESS = "com.tencent.wetype:hld"

        @Volatile
        var xposedService: XposedService? = null
            private set

        /**
         * 保存之后主动刷新被 hook 的 WeType 输入法进程。
         *
         * 跨进程必须走 API 102 的 `hotReloadModule()`：`Process.sendSignal` 只对自己的 uid
         * 有效，设置页进程打不到 `com.tencent.wetype:hld`。
         *
         * 注意本模块的设置页是**寄生在宿主进程里**的（`WeTypeHostLauncher` 在
         * `ImeMainSettingActivity` 上挂 ComponentDialog），点刷新时可能正好跑在
         * `com.tencent.wetype` 主进程，甚至是 `:hld` 自己。这时热重载的是当前进程，
         * 设置对话框会随之重建 —— 这是预期行为，与上游「设置页在模块自己进程、只影响对面」
         * 不同。
         *
         * @return 是否成功发出了热重载请求。
         */
        fun requestHotReloadForWeType(): Boolean {
            val service = xposedService ?: return false
            val targets = runCatching { service.runningTargets }.getOrNull().orEmpty()
            val target = targets.firstOrNull { it.processName == WETYPE_IME_PROCESS }
                ?: targets.firstOrNull { it.processName.startsWith("com.tencent.wetype:") }
                ?: return false
            return runCatching {
                service.hotReloadModule(target, null) { reloadedTarget, result ->
                    if (result.status == HotReloadResult.Status.SUCCEEDED) {
                        Log.i(TAG, "Hot reloaded ${reloadedTarget.processName} after settings save")
                    } else {
                        Log.w(
                            TAG,
                            "Hot reload ${reloadedTarget.processName}: ${result.status} ${result.message.orEmpty()}"
                        )
                    }
                }
                true
            }.onFailure { error ->
                Log.e(TAG, "Failed to hot reload WeType after settings save", error)
            }.getOrDefault(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        runCatching {
            if (service.apiVersion < XposedService.API_102) {
                Log.w(TAG, "Framework service API ${service.apiVersion} does not support API 102")
                return@runCatching
            }

            val targets = service.runningTargets
            ModuleActivationTracker.recordRunningTargets(this, targets)
            targets
                .filter { target -> target.state == HookedTarget.State.STALE }
                .forEach { target -> requestHotReload(service, target) }
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialize libxposed service", error)
        }
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService !== service) return
        xposedService = null
    }

    private fun requestHotReload(service: XposedService, target: HookedTarget) {
        runCatching {
            service.hotReloadModule(target, null) { reloadedTarget, result ->
                when (result.status) {
                    HotReloadResult.Status.SUCCEEDED -> {
                        Log.i(TAG, "Hot reloaded ${reloadedTarget.processName}")
                        runCatching {
                            ModuleActivationTracker.recordRunningTargets(
                                this,
                                service.runningTargets
                            )
                        }
                    }

                    else -> Log.w(
                        TAG,
                        "Hot reload ${reloadedTarget.processName}: ${result.status} ${result.message.orEmpty()}"
                    )
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to request hot reload for ${target.processName}", error)
        }
    }
}

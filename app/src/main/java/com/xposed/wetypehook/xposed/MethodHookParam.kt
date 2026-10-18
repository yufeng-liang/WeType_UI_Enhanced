package com.xposed.wetypehook.xposed

import java.lang.reflect.Executable
import java.lang.reflect.Method

private object StaticHookThisObject

class MethodHookParam internal constructor(
    val method: Executable,
    thisObject: Any?,
    private val originalArgs: List<Any?>,
    result: Any? = null
) {
    val thisObject: Any = thisObject ?: StaticHookThisObject

    internal var changedArgs: Array<Any?>? = null
        private set

    // Resource/draw hooks usually only inspect arguments. Copy only when a hook writes.
    operator fun get(index: Int): Any? {
        val changed = changedArgs
        return if (changed == null) originalArgs[index] else changed[index]
    }

    operator fun set(index: Int, value: Any?) {
        args[index] = value
    }

    val argumentCount: Int get() = changedArgs?.size ?: originalArgs.size

    fun argumentOrNull(index: Int): Any? = if (index in 0 until argumentCount) get(index) else null

    /** Retains the array-based API for callers that explicitly need mutable arguments. */
    var args: Array<Any?>
        get() = changedArgs ?: originalArgs.toTypedArray().also { changedArgs = it }
        set(value) { changedArgs = value }

    var result: Any? = result
        set(value) {
            field = value
            resultWasSet = true
        }

    internal var resultWasSet: Boolean = false
}

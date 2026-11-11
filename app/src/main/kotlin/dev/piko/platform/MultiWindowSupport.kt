package dev.piko.platform

import android.content.Context
import android.os.Build

/**
 * 独立多窗口播放的可用性。
 *
 * Quest 这类头显上 Horizon OS 会把同一应用的多个任务显示成空间里可拖拽的 2D 面板，
 * 和系统浏览器开多个窗口是同一套机制，所以默认打开；手机上默认仍是应用内单窗口播放，
 * 用户可以自己开（DeX、自由窗口等场景同样可用）。
 */
internal object MultiWindowSupport {
    fun isHeadset(context: Context): Boolean {
        val manager = context.packageManager
        return manager.hasSystemFeature("android.hardware.vr.headtracking") ||
            Build.MANUFACTURER.equals("Oculus", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Meta", ignoreCase = true)
    }

    fun defaultEnabled(context: Context): Boolean = isHeadset(context)
}

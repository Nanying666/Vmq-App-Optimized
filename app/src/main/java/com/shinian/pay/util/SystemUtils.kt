package com.shinian.pay.util

import android.app.ActivityManager
import android.content.Context

/**
 * 工具类（Kotlin 版）。
 *
 * 判断本应用是否存活。如果需要判断本应用在前台还是后台，请使用 getRunningTask。
 */
object SystemUtils {

    /**
     * 判断本应用是否存活。
     *
     * @param mContext 上下文
     * @param packageName 进程名（即包名）
     * @return 是否存活
     */
    @JvmStatic
    fun isAPPALive(mContext: Context, packageName: String): Boolean {
        val activityManager = mContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val appProcessInfoList = activityManager.runningAppProcesses ?: return false
        for (appInfo in appProcessInfoList) {
            if (packageName == appInfo.processName) {
                return true
            }
        }
        return false
    }
}
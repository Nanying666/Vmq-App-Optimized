package com.shinian.pay.util;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限引导工具类
 *
 * 功能：首次启动自动检测 APP 运行必需的权限，缺失时弹出清单对话框，
 * 用户点击"去开启"后直接跳转到对应的系统设置页（而不是让用户自己找）。
 *
 * 覆盖的权限：
 * 1. 通知使用权（NotificationListenerService，监听微信/支付宝收款通知）——核心
 * 2. 电池优化白名单（防止系统杀后台导致掉单）
 * 3. 通知栏显示权限（Android 13+，保活前台通知）
 * 4. 文件读写权限（Android 12 及以下，保存打赏码等）
 * 5. 自启动管理（国产 ROM 私有页面，跳转失败自动回退应用详情页）
 */
public final class PermissionGuideHelper {

    private static final String TAG = "PermissionGuide";

    /** 权限项请求码（与扫码/相机/存储的请求码错开） */
    public static final int REQ_POST_NOTIFICATIONS = 11010;
    public static final int REQ_LEGACY_STORAGE = 11011;

    private PermissionGuideHelper() {
    }

    /** 权限项定义 */
    public static class PermissionItem {
        public final int id;
        public final String name;
        public final String desc;
        public boolean granted;

        PermissionItem(int id, String name, String desc, boolean granted) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.granted = granted;
        }
    }

    // ===== 权限项 ID =====
    public static final int PERM_NOTIFICATION_LISTENER = 1; // 通知使用权
    public static final int PERM_BATTERY_WHITELIST = 2;    // 电池优化白名单
    public static final int PERM_POST_NOTIFICATIONS = 3;   // 通知栏显示(Android 13+)
    public static final int PERM_LEGACY_STORAGE = 4;       // 文件读写(Android 12-)
    public static final int PERM_AUTO_START = 5;           // 自启动(国产ROM)

    /**
     * 检测所有必需权限的授权状态
     */
    public static List<PermissionItem> checkMissingPermissions(Activity activity) {
        List<PermissionItem> all = new ArrayList<>();

        // 1. 通知使用权（监听收款通知的核心权限）
        boolean nlEnabled = isNotificationListenerEnabled(activity);
        all.add(new PermissionItem(PERM_NOTIFICATION_LISTENER, "通知使用权",
                "监听微信/支付宝收款通知（必需）", nlEnabled));

        // 2. 电池优化白名单
        boolean batteryOk = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(activity.getPackageName());
        } else {
            batteryOk = true;
        }
        all.add(new PermissionItem(PERM_BATTERY_WHITELIST, "电池优化白名单",
                "防止后台被杀导致收款掉单（强烈建议）", batteryOk));

        // 3. 通知栏显示权限（Android 13+ 动态申请）
        if (Build.VERSION.SDK_INT >= 33) {
            boolean notifOk = ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            all.add(new PermissionItem(PERM_POST_NOTIFICATIONS, "通知栏显示",
                    "显示保活通知与到账提醒（必需）", notifOk));
        }

        // 4. 文件读写（Android 12L 及以下；Android 13+ 该权限已废弃，系统相册选择器自带授权）
        if (Build.VERSION.SDK_INT <= 32) {
            boolean storageOk = ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            all.add(new PermissionItem(PERM_LEGACY_STORAGE, "文件读写",
                    "保存收款码图片等功能（建议）", storageOk));
        }

        // 5. 自启动权限（国产 ROM 无公开 API 检测开关状态，采用"引导确认"机制：
        //    用户去过自启动设置页或明确关闭引导后，标记为已确认，不再报缺失）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent autoStartIntent = getAutoStartIntent(activity);
            if (autoStartIntent != null && !isAutoStartAcked(activity)) {
                all.add(new PermissionItem(PERM_AUTO_START, "自启动/后台运行",
                        "允许开机自启与后台保活（小米/华为/OPPO/vivo 等，APP无法检测开关状态，请确认已开启）", false));
            }
        }

        // 过滤出未授权项
        List<PermissionItem> missing = new ArrayList<>();
        for (PermissionItem item : all) {
            if (!item.granted) {
                missing.add(item);
            }
        }
        return missing;
    }

    /**
     * 弹出权限引导对话框。
     *
     * @param fromMenu true=用户从菜单手动触发（始终弹出，权限齐时提示已就绪）
     *                 false=首次启动自动触发（仅在有缺失权限时弹出）
     */
    public static void showPermissionGuideDialog(Activity activity,
                                                 List<PermissionItem> missing,
                                                 boolean fromMenu) {
        if (!fromMenu && (missing == null || missing.isEmpty())) {
            return; // 自动模式下无缺失权限，不打扰
        }

        if (missing == null || missing.isEmpty()) {
            Toast.makeText(activity, "所有必要权限已开启 ✓", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder("以下权限未开启，可能导致收不到收款通知或被系统杀后台：\n\n");
        List<PermissionItem> needGuide = new ArrayList<>();
        for (PermissionItem item : missing) {
            sb.append("• ").append(item.name).append("：").append(item.desc).append("\n");
            needGuide.add(item);
        }
        sb.append("\n点击「去开启」逐项授权（将打开对应系统设置页）");

        final Activity act = activity;
        new AlertDialog.Builder(activity)
                .setTitle("权限设置引导")
                .setMessage(sb.toString())
                .setCancelable(!fromMenu)
                .setPositiveButton("去开启", (dialog, which) -> {
                    // 挂起待引导队列：从系统设置返回后 onResume 会自动复查并继续下一项
                    sPendingGuide = new ArrayList<>(needGuide);
                    openPermissionSettings(act, needGuide.get(0).id, sPendingGuide);
                })
                .setNegativeButton(fromMenu ? "关闭" : "暂不授权", (dialog, which) -> {
                    // 用户明确拒绝，本次不再自动弹窗
                    sPendingGuide = null;
                })
                .show();
    }

    /** 待引导队列：点击「去开启」后挂起，从设置页返回时据此继续引导 */
    private static List<PermissionItem> sPendingGuide = null;

    /**
     * 启动时自动检查并弹窗（带节流：10 分钟内不重复弹）
     */
    public static void checkAndShowOnLaunch(Activity activity) {
        long now = System.currentTimeMillis();
        if (now - sLastAutoShowAt < 10 * 60 * 1000L) {
            return;
        }
        List<PermissionItem> missing = checkMissingPermissions(activity);
        if (!missing.isEmpty()) {
            sLastAutoShowAt = now;
            showPermissionGuideDialog(activity, missing, false);
        }
    }

    /**
     * onResume 复查：仅当存在待引导队列时才介入。
     * 用户从系统设置授权返回后调用——全部授权则提示就绪，仍有缺失则继续弹出引导。
     */
    public static void onResumeCheck(Activity activity) {
        if (sPendingGuide == null) {
            return; // 没有进行中的引导，不打扰
        }
        List<PermissionItem> missing = checkMissingPermissions(activity);
        if (missing.isEmpty()) {
            sPendingGuide = null;
            Toast.makeText(activity, "所有必要权限已开启 ✓", Toast.LENGTH_SHORT).show();
        } else {
            // 剔除已授权的项后继续引导剩余项
            showPermissionGuideDialog(activity, missing, false);
        }
    }

    private static long sLastAutoShowAt = 0L;

    /**
     * 按权限项跳转对应系统设置页。
     * 跳转成功后，从待引导列表中移除该项；用户返回 APP 时若仍有缺失，onResume 会再次弹出。
     */
    public static void openPermissionSettings(Activity activity, int permId, List<PermissionItem> remaining) {
        if (remaining != null) {
            for (PermissionItem item : remaining) {
                if (item.id == permId) {
                    remaining.remove(item);
                    break;
                }
            }
        }
        try {
            switch (permId) {
                case PERM_NOTIFICATION_LISTENER:
                    // 通知使用权设置页
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        openAppDetailsSettings(activity);
                    }
                    break;

                case PERM_BATTERY_WHITELIST:
                    // 直接弹系统的"忽略电池优化"确认对话框
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:" + activity.getPackageName()))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception e) {
                            try {
                                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Exception e2) {
                                openAppDetailsSettings(activity);
                            }
                        }
                    }
                    break;

                case PERM_POST_NOTIFICATIONS:
                    // Android 13+ 运行时权限弹窗
                    if (Build.VERSION.SDK_INT >= 33) {
                        ActivityCompat.requestPermissions(activity,
                                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                REQ_POST_NOTIFICATIONS);
                    }
                    break;

                case PERM_LEGACY_STORAGE:
                    // 旧系统存储权限运行时弹窗
                    ActivityCompat.requestPermissions(activity,
                            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQ_LEGACY_STORAGE);
                    break;

                case PERM_AUTO_START:
                    // 国产 ROM 自启动管理页，逐个候选尝试，全部失败回退应用详情
                    Intent autoStart = getAutoStartIntent(activity);
                    markAutoStartAcked(activity); // 去过即视为已确认，不再报缺失
                    if (autoStart != null) {
                        activity.startActivity(autoStart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } else {
                        openAppDetailsSettings(activity);
                    }
                    break;

                default:
                    openAppDetailsSettings(activity);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "跳转权限设置页失败: " + e.getMessage());
            Toast.makeText(activity, "未能打开设置页，已跳转到应用详情", Toast.LENGTH_SHORT).show();
            openAppDetailsSettings(activity);
        }
    }

    /** 自启动"已确认"标记（该权限无检测API，用户去过设置页即视为已确认） */
    private static final String PREF_NAME = "perm_guide";
    private static final String KEY_AUTO_START_ACK = "auto_start_acked";

    private static boolean isAutoStartAcked(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START_ACK, false);
    }

    private static void markAutoStartAcked(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_START_ACK, true).apply();
    }

    /** 回退方案：打开本 APP 的系统应用详情页（里面有全部权限开关） */
    public static void openAppDetailsSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + activity.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(activity, "请到系统设置中手动开启权限", Toast.LENGTH_LONG).show();
        }
    }

    /** 获取厂商自启动管理页 Intent（仅国产 ROM 有，其余返回 null） */
    private static Intent getAutoStartIntent(Context context) {
        PackageManager pm = context.getPackageManager();

        String[][] candidates = {
                // 小米 MIUI
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartupManagementActivity"},
                // 华为/荣耀 EMUI / Magic
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                // OPPO ColorOS
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                // vivo OriginOS / FuntouchOS
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                // 三星
                {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        };

        for (String[] c : candidates) {
            try {
                Intent intent = new Intent().setComponent(new ComponentName(c[0], c[1]));
                if (pm.resolveActivity(intent, 0) != null) {
                    return intent;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 通知使用权是否已开启 */
    public static boolean isNotificationListenerEnabled(Context context) {
        String pkgName = context.getPackageName();
        final String flat = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
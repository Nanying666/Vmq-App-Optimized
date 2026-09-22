package com.shinian.pay.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.shinian.pay.R;
import com.shinian.pay.manager.AppConstants;
import com.shinian.pay.service.DaemonService;
import com.shinian.pay.service.PlayerMusicService;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.shinian.pay.ui.MainActivity.getHttpURLConnection;


public class SettingActivity extends AppCompatActivity {

    private static final String TAG = "SettingActivity";

    // 延迟时间常量
    private static final int RESTART_DELAY_OPEN = 2000;  // 开启时延迟2秒
    private static final int RESTART_DELAY_CLOSE = 1500; // 关闭时延迟1.5秒
    private static final int HTTP_TIMEOUT = 8000;

    private TextView version;
    private int code;
    private String ver;
    private String uplog;
    private String upurl;
    private int Version; // 当前软件版本号
    private int versions; // 最新软件版本号
    private Switch state_switch;
    private String state_swich;

    // 复用 Handler 实例
    private final Handler mainHandler = new Handler(Looper.getMainLooper());


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 隐藏标题栏
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_setting);

        version = findViewById(R.id.version);
        state_switch = findViewById(R.id.state_switch);
        version.setText("当前软件版本 V" + getAppVersionName());

        // 设置返回按钮点击事件
        View btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        SharedPreferences state = getSharedPreferences("state_switch", MODE_PRIVATE);
        state_swich = state.getString("state_switch", "");

        // 设置 Switch 开关状态
        setSwitchState(state_swich);
    }

    /**
     * 设置开关状态显示
     */
    private void setSwitchState(String state) {
        if ("no".equals(state)) {
            state_switch.setChecked(true);
            state_switch.setHint("开启");
        } else if ("off".equals(state)) {
            state_switch.setChecked(false);
            state_switch.setHint("关闭");
        }
    }

    private void stopPlayMusicService() {
        Intent intent = new Intent(this, PlayerMusicService.class);
        stopService(intent);
    }

    private void startPlayMusicService() {
        Intent intent = new Intent(this, PlayerMusicService.class);
        startService(intent);
    }

    private void startDaemonService() {
        Intent intent = new Intent(this, DaemonService.class);
        startService(intent);
    }

    // 停止 service
    private void stopDaemonService() {
        Intent intent = new Intent(this, DaemonService.class);
        stopService(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (AppConstants.DEBUG) {
            Log.d(TAG, "--->onDestroy");
        }
    }

    private AlertDialog loadingDialog;

    /**
     * 显示更新对话框
     */
    private void showUpdateDialog(String uplog, String upurl) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发现新版本！")
                .setMessage(uplog)
                .setIcon(R.drawable.app_gx)
                .setCancelable(false)
                .setPositiveButton("立即更新", (dia, which) -> {
                    Intent intent_d = new Intent(Intent.ACTION_VIEW);
                    intent_d.setData(Uri.parse(upurl));
                    startActivity(intent_d);
                })
                .setNeutralButton("忽略更新", null)
                .create();
        dialog.show();
    }

    /**
     * 通用资源关闭方法
     */
    private void closeResource(Closeable resource, String resourceName) {
        if (resource != null) {
            try {
                resource.close();
            } catch (IOException e) {
                Log.e(TAG, "关闭 " + resourceName + " 失败", e);
            }
        }
    }

    // 获取当前程序版本号
    public String getAppVersionCode() {
        String versioncode = "";
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
            versioncode = String.valueOf(pi.versionCode);
            if (versioncode == null || versioncode.isEmpty()) {
                return "";
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "获取版本号失败", e);
        }
        return versioncode;
    }

    // 获取当前应用的版本名
    private String getAppVersionName() {
        PackageManager packageManager = getPackageManager();
        try {
            PackageInfo packInfo = packageManager.getPackageInfo(getPackageName(), 0);
            if (packInfo != null && packInfo.versionName != null) {
                return packInfo.versionName;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "获取版本名失败", e);
        }
        return "未知版本";
    }

    /**
     * 检查应用版本更新
     * @return true 表示有新版本，false 表示已是最新版本或检查失败
     */
    public boolean App() {
        HttpURLConnection httpConn = null;
        DataOutputStream dos = null;
        BufferedReader responseReader = null;

        try {
            // TODO: 建议升级为 HTTPS
            String urlPath = "http://w.t3yanzheng.com/A729B02347E855EC";

            // 当前软件版本号
            String versionCode = getAppVersionCode();
            if (versionCode == null || versionCode.isEmpty()) {
                Log.e(TAG, "获取版本号失败");
                return false;
            }

            Version = Integer.parseInt(versionCode);
            String param = "ver=" + URLEncoder.encode(versionCode, "UTF-8");

            // 建立连接
            httpConn = getHttpURLConnection(urlPath);

            // 建立输入流，向指向的 URL 传入参数
            dos = new DataOutputStream(httpConn.getOutputStream());
            dos.writeBytes(param);
            dos.flush();

            // 获得响应状态
            int resultCode = httpConn.getResponseCode();
            if (HttpURLConnection.HTTP_OK == resultCode) {
                StringBuilder sb = new StringBuilder();
                String readLine;
                responseReader = new BufferedReader(new InputStreamReader(httpConn.getInputStream(), StandardCharsets.UTF_8));
                while ((readLine = responseReader.readLine()) != null) {
                    sb.append(readLine).append("\n");
                }

                JSONObject data = new JSONObject(sb.toString().trim());

                // 验证必要字段是否存在
                if (!data.has("code")) {
                    Log.e(TAG, "检查更新失败：缺少 code 字段");
                    return false;
                }

                code = data.getInt("code");

                // 只有在 code==200 时才尝试获取其他字段
                if (code == 200) {
                    // 安全地获取可选字段，避免崩溃
                    ver = data.optString("ver", "");
                    versions = data.optInt("version", 0);
                    uplog = data.optString("uplog", "");
                    upurl = data.optString("upurl", "");

                    // 验证必要字段
                    if (versions > 0 && versions > Version) {
                        final String finalUplog = uplog;
                        final String finalUpurl = upurl;
                        runOnUiThread(() -> showUpdateDialog(finalUplog, finalUpurl));
                        return true;
                    }
                } else {
                    Log.w(TAG, "检查更新返回错误码：" + code);
                }
            } else {
                Log.e(TAG, "检查更新请求失败，响应码：" + resultCode);
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "版本号格式错误", e);
        } catch (Exception e) {
            Log.e(TAG, "检查更新发生异常", e);
        } finally {
            // 关闭资源
            closeResource(dos, "DataOutputStream");
            closeResource(responseReader, "BufferedReader");
            if (httpConn != null) {
                httpConn.disconnect();
            }
        }
        return false;
    }

    // 检测更新
    public void ver_sion(View v) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showLoadingDialog();
                    }
                });

                try {
                    final boolean result = App();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissLoadingDialog();
                            if (!result) {
                                Toast.makeText(SettingActivity.this, "已经是最新版本", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "检查更新失败", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissLoadingDialog();
                            Toast.makeText(SettingActivity.this, "检查更新失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void showLoadingDialog() {
        if (loadingDialog == null || !loadingDialog.isShowing()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage("正在检查更新...");
            builder.setCancelable(false);
            loadingDialog = builder.create();
            loadingDialog.show();
        }
    }

    private void dismissLoadingDialog() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
    }

    /**
     * 获取网页 HTML 内容
     * @param path 网页地址
     * @return HTML 内容
     * @throws Exception 网络异常
     */
    public String getHtml(String path) throws Exception {
        URL url = new URL(path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(HTTP_TIMEOUT);

        InputStream inStream = null;
        try {
            inStream = conn.getInputStream();
            byte[] data = readInputStream(inStream);
            return new String(data, StandardCharsets.UTF_8);
        } finally {
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (IOException e) {
                    Log.w(TAG, "关闭输入流失败", e);
                }
            }
        }
    }

    // 读取输入流 获取 HTML 二进制数组
    public byte[] readInputStream(InputStream inStream) throws Exception {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        try {
            while ((len = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, len);
            }
        } finally {
            outStream.close();
        }
        return outStream.toByteArray();
    }

    // 问题反馈（跳转到 GitHub Issues）
    public void email_fk(View view) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/shinian-a/Vmq-App/issues"));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器，请检查浏览器设置", Toast.LENGTH_SHORT).show();
        }
    }

    // Docs
    public void help_api(View v) {
        Intent help = new Intent(this, HelpActivity.class);
        startActivity(help);
    }

    // 白名单权限检测
    public void dc_qx(View v) {
        if (!isIgnoringBatteryOptimizations()) {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(i);
            Toast.makeText(getApplication(), "请勾选此应用\"不优化\"", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "已授权白名单权限!", Toast.LENGTH_SHORT).show();
        }
    }

    // 检测电池白名单权限
    private boolean isIgnoringBatteryOptimizations() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return powerManager.isIgnoringBatteryOptimizations(getPackageName());
        }
        return false;
    }

    // 打开电池白名单设置
    public void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "打开电池优化设置失败", e);
        }
    }

    // 保活服务
    public void service_start(View v) {
        // 1. 启动前台 Service（唯一真实有效的保活手段）
        startDaemonService();

        // 2. 启动播放音乐 Service（低优先级进程提升，可开关）
        startPlayMusicService();

        // 说明：原 Native 守护（需Root，普通设备必败）、Account Sync（现代ROM已失效）、
        // 锁屏1像素 Activity、JobScheduler（static Context 泄漏）等无效/有害保活手段已移除
        Toast.makeText(this, "服务启动成功!", Toast.LENGTH_SHORT).show();
    }

    // 屏幕永亮
    public void state_swit(View v) {
        if (state_switch.isChecked()) {
            showEnableAlwaysOnDialog();
        } else {
            disableAlwaysOn();
        }
    }

    /**
     * 显示开启屏幕常亮对话框
     */
    private void showEnableAlwaysOnDialog() {
        String message = "开启或关闭此功能将会在 2 秒后重启软件！\n" +
                        "开启之后便会自动调低软件窗口亮度并进入全屏模式（退出即可恢复！）\n" +
                        "重启后请不要关闭软件，保持 Log 日志面板即可否则无效！\n" +
                        "此功能仅适用于真机独立挂 V 免签监控的";

        new AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("我已知晓", (dia, which) -> {
                // 先保存设置
                SharedPreferences.Editor editor = getSharedPreferences("state_switch", MODE_PRIVATE).edit();
                editor.putString("state_switch", "no");
                editor.apply(); // 使用 apply() 异步保存
                
                state_switch.setHint("开启");
                Toast.makeText(this, "屏幕永亮开启成功，2S 后重启...", Toast.LENGTH_SHORT).show();

                // 延迟重启
                restartApp(RESTART_DELAY_OPEN);
            })
                .setNegativeButton("暂不开启", (dia, which) -> {
                    // 用户取消，恢复开关状态
                    state_switch.setChecked(false);
                })
            .create()
            .show();
    }

    /**
     * 关闭屏幕常亮
     */
    private void disableAlwaysOn() {
        // 先保存设置
        SharedPreferences.Editor editor = getSharedPreferences("state_switch", MODE_PRIVATE).edit();
        editor.putString("state_switch", "off");
        editor.apply(); // 使用 apply() 异步保存
        state_switch.setHint("关闭");
        Toast.makeText(this, "屏幕永亮已关闭，1.5S 后重启...", Toast.LENGTH_SHORT).show();
        // 延迟重启
        restartApp(RESTART_DELAY_CLOSE);
    }

    /**
     * 重启应用
     * 使用 PendingIntent 方式重启，更加优雅
     * @param delayMillis 延迟时间 (毫秒)
     */
    private void restartApp(int delayMillis) {
        mainHandler.postDelayed(() -> {
            try {
                // 获取启动 Intent
                Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (intent != null) {
                    // 添加标志位，确保重新启动
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    // 启动主 Activity
                    startActivity(intent);
                    // 结束当前 Activity
                    finish();
                    // 退出进程（可选，根据需求决定是否保留）
                    android.os.Process.killProcess(android.os.Process.myPid());
                } else {
                    Log.e(TAG, "无法获取启动 Intent");
                    Toast.makeText(this, "重启失败，请手动重启应用", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "重启应用失败", e);
                Toast.makeText(this, "重启失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, delayMillis);
    }

    public void vmq_Pro_gy(View v) {
        Intent aboutIntent = new Intent(this, AboutActivity.class);
        startActivity(aboutIntent);
    }
}

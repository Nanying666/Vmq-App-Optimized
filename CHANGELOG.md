# 更新日志 / 优化修复说明

本文件记录本分支相对[原版 Vmq-App](https://github.com/shinian-a/Vmq-App)的修复与优化。

> 本项目基于 **shinian-a/Vmq-App** 二次开发，遵循原项目 **Apache License 2.0** 开源协议。
> 原项目版权归原作者所有，本分支的修改内容同样以 Apache License 2.0 发布。

---

## 版本概览

| 项目 | 原版 | 本优化版 | 变化 |
|:---|:---:|:---:|:---:|
| Java 文件总数 | 48 | 38 | **−10** |
| 代码总行数 | 8440 | 7972 | −468 |
| 业务代码文件（`com/shinian`） | 27 | 17 | **−10** |
| 业务代码行数 | 5522 | 5054 | −468 |
| 运行进程数 | **1 主 + 5 子** | **1** | 单进程 |
| Manifest 服务声明 | 9 | 5 | −4 |

> 说明：删除了约 **1600 行**失效/有害代码，同时**新增 642 行**有效功能代码
> （`NetworkClient` 290 行 + `PermissionGuideHelper` 352 行）。

---

## 一、🔴 核心功能修复（11 个功能性 Bug）

### 1. 自动补单功能从未成功过（致命）

- **原版问题**：`scheduleRetryCallback()` 使用主线程 `Handler.postDelayed()` 执行**同步网络请求**。
  Android 禁止主线程网络 IO，第一次执行必然抛出 `NetworkOnMainThreadException`，
  被 catch 后仅弹一句“自动补单回调失败”。
- **后果**：首次回调失败后的**补单机制完全失效**，网络抖动直接导致真实掉单。
- **修复**：改为子线程执行（`Thread.sleep(1000)` + 同步重试请求），
  结果通过 `mainHandler.post()` 回主线程提示。

### 2. 心跳循环无异常保护，一次异常永久停跳

- **原版问题**：`while(true)` 循环体中仅 `Thread.sleep()` 有 try-catch，
  其余任何代码（SharedPreferences 读取、网络栈初始化等）抛出的未捕获异常
  会直接**杀死心跳线程**。
- **后果**：心跳永久停止，而 APP 界面显示正常，用户毫无感知。
- **修复**：整个循环体包裹 try-catch，异常记录日志后继续下一轮；
  仅 `InterruptedException` 时退出循环。

### 3. 崩溃处理器导致“僵尸进程”

- **原版问题**：`VmqApplication` 的全局 `UncaughtExceptionHandler` 记录异常后
  **不终止进程**（`killProcess` 被注释）。
- **后果**：主线程崩溃后进程仍存活，界面冻结、服务假死、无法再接收通知，
  用户误以为 APP 正常运行。
- **修复**：记录日志后转交系统默认处理器正常终止，由 `START_STICKY` 前台服务自动重启恢复。

### 4. 守护服务退出死循环

- **原版问题**：`DaemonService` / `PlayerMusicService` 在 `onDestroy()` 中
  **无条件自我重启**。
- **后果**：用户点击“退出 APP”后 `stopService` 反而触发服务重启，退出不干净、
  通知栏残留。
- **修复**：新增 `AppConstants.IS_USER_EXIT` 静态标志，用户主动退出后不再复活；
  被系统杀死时仍会自动重启（保活能力不受影响）。

### 5. 网络客户端持续泄漏

- **原版问题**：DoH DNS 解析每次调用都 `new OkHttpClient.Builder().build()`。
- **后果**：心跳每 50 秒触发一次 DNS 查询，**连接池与线程池持续泄漏**，
  长时间运行后内存占用不断攀升。
- **修复**：改为全局单例复用。

### 6. MediaPlayer 资源泄漏

- **原版问题**：`PlayerMusicService.stopPlayMusic()` 只调用 `stop()`，
  从未调用 `release()`。
- **后果**：每次启停服务泄漏一个 MediaPlayer 实例及其底层音频资源。
- **修复**：`release()` + 置 null，并捕获 `IllegalStateException`。

### 7. Android 13+ 申请已废弃存储权限导致扫码死循环

- **原版问题**：`startQrCode()` 无条件检查 `READ_EXTERNAL_STORAGE`。
  该权限在 **Android 13（API 33）已被系统废弃**，申请后系统直接返回拒绝。
- **后果**：Android 13+ 用户点击“扫码配置” → 申请秒拒 → 弹提示 → 循环卡死，
  **完全无法使用扫码配置**。
- **修复**：
  - Android 13+ 不再申请该废弃权限，直接进入扫码页
    （相册选图走系统选择器自带授权，保存图片走 MediaStore 免权限）；
  - Android 12L 及以下保持原有申请逻辑；
  - 权限被拒提示从 Toast 升级为**弹窗 + 「去开启」直达系统设置页**。

### 8. 自启动权限永久误报

- **原版问题**：`PermissionGuideHelper` 将自启动项硬编码为“未授权”。
- **后果**：国产 ROM 的自启动开关是厂商私有设置，Android **没有公开 API 可读取其状态**，
  用户即使已开启也永远提示缺失。
- **修复**：改为“引导确认”机制 —— 用户访问过自启动设置页后标记为已确认，
  不再重复报缺失。

### 9. 打赏保存收款码功能静默失效

- **原版问题**：与问题 7 同源 —— Android 10+ 分支反而检查已废弃的
  `WRITE_EXTERNAL_STORAGE`，检查不通过后流程中断。
- **修复**：Android 10+ 直接使用 MediaStore 保存（免权限），Android 9 及以下保留原申请逻辑。

### 10. 字符串引用比较

- **原版问题**：`host != "" && key != ""` 比较的是**对象引用**而非内容。
- **修复**：改用 `TextUtils.isEmpty()`。

### 11. 退出流程反射调用失效

- **原版问题**：`exitApp()` 通过反射调用 `releaseWakeLock`，
  但该方法为**实例方法**却以 `invoke(null)` 调用，必然抛出异常（被 catch 吞掉）。
- **修复**：改为直接 `stopService()` 触发监听服务的 `onDestroy()`
  （该生命周期已实现心跳线程中断 + WakeLock 释放）。

---

## 二、🌐 网络层重构（新增 `NetworkClient`）

原版网络请求**硬编码 `http://` 单协议、零重试**，在移动网络下极易失败(特别是未备案域名)。

| 能力 | 原版 | 本优化版 |
|:---|:---|:---|
| 协议 | 硬编码 `http://` 单协议 | **https / http 交替重试** |
| 重试策略 | **无**（一次失败即判定错误） | **6 次重试 + 递增退避** |
| 308 重定向 | 不跟随 | **自动跟随**（`followSslRedirects`） |
| DNS 解析 | 明文 UDP（可被污染） | **阿里 DoH 加密解析** + 系统 DNS 降级 |
| IP 直连 | 不支持 | **支持**（`IP` / `IP:端口`，自动 http 优先） |
| 连接管理 | 每次新建 | 单例复用 + `Connection: close` 防半死连接 |

**设计要点**：

- IP 直连模式自动全部走 `http`（`https + IP` 无匹配证书必然失败，避免无效尝试）；
- 域名模式自动 `https` 优先，并跟随服务端 308 跳转；
- DoH 失败自动降级系统 DNS，保证可用性；
- 统一入口：异步 `getWithRetry()` / 同步 `getWithRetrySync()`。

**实测效果**（联通移动网络，同网络路径）：

| 场景 | 原版 | 优化版 |
|:---|:---:|:---:|
| 域名被 SNI 阻断环境下 | **0/10 成功** | 12/12 成功（IP 直连通道） |

---

## 三、🔐 权限引导体系（新增 `PermissionGuideHelper`）

| 场景 | 原版 | 本优化版 |
|:---|:---|:---|
| 首次打开 | 仅检查通知权限，**Toast 文字提示** | **弹窗列出全部缺失项** |
| 用户操作 | 自行到系统设置中翻找 | 点击「去开启」**直达对应设置页** |
| 覆盖权限 | 1 项（通知使用权） | **5 项**（通知使用权 / 电池白名单 / 通知栏 / 存储 / 自启动） |
| 授权反馈 | 无 | 返回后自动复查，全部就绪提示 ✓ |
| 厂商适配 | 无 | **内置 11 个厂商自启动页**（小米 / 华为 / OPPO / vivo / 三星） |
| 手动入口 | 无 | 菜单「权限检查」 |

所有跳转均带三级回退（专用设置页 → 备用页 → 应用详情页），不会因机型差异崩溃。

---

## 四、⚡ 稳定性与资源管理

| 项 | 原版 | 本优化版 |
|:---|:---|:---|
| 心跳线程生命周期 | 裸线程，Service 销毁不回收 | 绑定 Service，`onDestroy` 中断 |
| WakeLock | **无限期持有**（异常时设备永不休眠，耗电剧增） | **6 分钟超时自动释放** |
| 日志存储 | `commit()` 同步磁盘 IO | `apply()` 异步写入 |
| 进程模型 | 1 主 + 5 子（内存 ×6，查杀面大） | **单进程** |
| 崩溃恢复 | 僵尸进程 | 记录后正常崩溃，前台服务自动重启 |

### 多进程收敛（重要）

原版在 Manifest 中声明了 5 个独立进程：

```
:daemon_service  :service  :music_service  :native_daemon  :sync_adapter
```

每个子进程都会**完整加载一套 ART 虚拟机与应用类**，内存占用 ×6，
且为厂商 ROM 提供了更多查杀入口，**反而降低整体存活率**。

本版已移除全部 `android:process` 声明，收敛为单进程。

---

## 五、🧹 死代码清理（删除 12 个文件，约 1600 行）

| 删除项 | 删除理由 |
|:---|:---|
| `NativeDaemonService` | 需 Root 执行 shell 命令，普通设备必然失败 |
| `SyncAdapterService` + `SyncAdapter` + `AccountAuthService` + `AccountAuthenticatorService` + `SyncProvider` + `SyncManager` + 2 个 XML | Account Sync 保活在 Android 8+ 现代 ROM 已全面失效 |
| `AliveJobService` | JobScheduler 短周期轮询在 Android 8+ 被限制到 15 分钟起，失去保活意义 |
| `JobSchedulerManager` | 持有 `static Context`，内存泄漏典型 |
| `SinglePixelActivity` + `ScreenManager` + `ScreenReceiverUtil` | 1 像素保活方案自 2017 年后已被各 ROM 识别并封杀 |
| Manifest 中对应声明（4 服务 + 1 Provider） | 同上 |

> 保留的保活手段仅剩**真正有效**的组合：
> 前台服务 + 电池优化白名单 + 通知使用权 + （可选）音频保活。

---

## 六、🛡️ 安全加固

| 项 | 原版 | 本优化版 |
|:---|:---|:---|
| 版本更新检查 | `http://` 明文（存在被中间人劫持推送恶意安装包的风险） | **`https://`** |
| 调试日志 | `DEBUG = true` 硬编码，release 包也全量输出（含 host、收款金额等敏感信息） | **绑定 `BuildConfig.DEBUG`**，release 自动静默 |

---

## 七、📦 构建与部署改进

| 项 | 原版 | 本优化版 |
|:---|:---|:---|
| 依赖仓库 | 仅 google / mavenCentral（国内直连不稳定） | **优先国内镜像**（腾讯 / 阿里），官方源回退 |
| AGP 8 兼容 | 未显式开启 BuildConfig | `buildFeatures { buildConfig true }` |
| 服务端接入 | 仅域名通道 | **双通道**：域名 + 裸 IP 端口（绕开 SNI 阻断） |

> 裸 IP 端口通道仅暴露 API 接口（心跳 / 推送 / 下单），**不暴露管理后台**，
> 降低被端口扫描探测到后台登录入口的风险。

---

## 八、⚠️ 已知局限与后续计划

**当前局限**：

1. **签名差异**：本版为 debug 签名，无法覆盖安装官方版，需卸载重装；
2. **zxing 源码内嵌**：仍保留约 1800 行硬拷贝源码（功能正常但无法升级），
   后续可替换为 ML Kit / CameraX；
3. **缺少自动化测试**：所有修复依赖人工验证与代码审查，通知解析逻辑尚无单元测试覆盖；
4. **明文 HTTP 通道**：IP 直连通道为明文传输，V免签协议自带 MD5 签名防篡改，
   风险可控但非端到端加密。

**后续计划**：

- [ ] 通知解析逻辑补充单元测试（微信 / 支付宝各类通知文案用例集）
- [ ] zxing 替换为 ML Kit Barcode Scanning
- [ ] 引入 Kotlin + Coroutines + ViewModel 渐进式现代化
- [ ] 双通道自动健康检查与故障切换

---

## 九、许可证与致谢

本项目基于 [shinian-a/Vmq-App](https://github.com/shinian-a/Vmq-App) 二次开发。

- 原项目版权归原作者 **shinian-a** 所有
- 遵循 **Apache License 2.0** 开源协议发布
- 修改内容同样以 **Apache License 2.0** 发布
- 详细许可条款见项目根目录 [LICENSE](LICENSE) 文件

感谢原作者的开源贡献。如本项目对你有帮助，也请给[原项目](https://github.com/shinian-a/Vmq-App)一个 Star ⭐。

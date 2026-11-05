# rayneotv2-tools

雷鸟魔盒 2（RayNeo PocketTV2 / `RayNeo_PocketTV2`，Android 14 user build）诊断与投屏工具集。

## 目录

| 路径 | 说明 |
| --- | --- |
| `src/` | 诊断 App 源码：`MainActivity`（五块卡片：设备 / Shell 权限 / 电池 / Wi‑Fi / 时间）、`BatteryLogService`（后台电量采样，1.5 MB 滚动 gzip，留 6 个存档）、`GlobalSettingsActivity`（`content://settings/global` 只读浏览）、`ShizukuBridge`（借 shell 权限执行命令） |
| `libs/` | Shizuku 13.1.5 的 4 个 AAR（api / provider / aidl / shared）及解出的 `*-classes.jar` |
| `build.ps1` | 一键构建，无需 Gradle：aapt2 link → javac（`-classpath` 全部 libs）→ d8（`.class` + libs）→ 塞 `classes.dex` → zipalign → apksigner |
| `AndroidManifest.xml` | versionCode 4 / versionName 1.3；含 `moe.shizuku.manager.permission.API_V23`、`<queries>`、`ShizukuProvider`（authority `com.rayneo.diag.shizuku`） |
| `RayNeoDiag.apk` | 构建产物（约 49 KB，已用 `debug.keystore` 签名） |
| `debug.keystore` | 调试签名（alias `rayneo`，storepass/keypass 均为 `android`，非机密，仅为能重装到同机而保留） |
| `watch-screen.cmd` | 一键投屏（Windows，CRLF）；调用 `scrcpy\scrcpy-win64-v5.0.1\scrcpy.exe` |
| `scrcpy/` | scrcpy 5.0.1 原程序，**被 `.gitignore` 排除**（约 11 MB 二进制），需要时自行下载解压到该路径 |

## 构建

```powershell
D:\lib\rayneotv2-diag-app\build.ps1
```

脚本内自己设置 `JAVA_HOME`（Corretto 17）与 `PATH`，依赖 `D:\lib\android-sdk\android-14\build-tools\34.0.0`（aapt2 / d8 / zipalign / apksigner）与 `android-35\android.jar`。

## 安装与授权

```bash
adb -s <serial> install -r -g RayNeoDiag.apk
adb -s <serial> shell pm grant com.rayneo.diag android.permission.WRITE_SECURE_SETTINGS
adb -s <serial> shell am start-foreground-service -n com.rayneo.diag/.BatteryLogService
```

## Shizuku（非 root 借 shell 权限）

`user` build + `ro.debuggable=0` 时，App 进程被 SELinux 挡在 `getprop ro.serialno` / `/proc/loadavg` / 全进程列表之外。装 [Shizuku](https://github.com/RikkaApps/Shizuku) 后用 adb 拉起它的服务，App 即可通过 binder 借 shell 权限：

```bash
# 路径以 Shizuku 界面「通过连接电脑启动（使用 adb）」→「查看指令」的当前文本为准
adb -s <serial> shell /data/app/~~<随机串>/moe.shizuku.privileged.api-<随机串>/lib/arm/libshizuku.so
```

- 服务进程父进程是 init（`ps -A -o PID,PPID,USER,NAME | grep shizuku_server` 可见 PPID=1），**adb 断开/拔线不影响**；
- 但**设备每次重启后要重跑一次**，它不会开机自启。

## 投屏

双击 `watch-screen.cmd`，等价于：

```
scrcpy.exe --serial <serial> --no-audio --max-size 1600 --stay-awake --window-title "RayNeo PocketTV2"
```

> 注意：scrcpy 会为设备新建一块虚拟显示（`1600x896`，owner `com.android.shell`），此时 `screencap` 抓的物理屏和 scrcpy 窗口内容不是同一块屏，排查画面问题时别混。

## 已知限制（ROM 层面，非代码问题）

- 框架里**没有电池设备**（`/sys/class/power_supply/` 空、`healthd: No battery devices found`），真实电量只能读厂商写入的 `Settings.Global`（`battery` / `glass_battery` / `battery_temperature` / `battery_discharging` / `battery_care_mode`）。
- 未接 Shizuku 时：序列号、`/proc/stat`、`/proc/loadavg`、全进程列表都读不到（SELinux `untrusted_app` 域限制）。
- 写入 `Settings.Global` 需要 `WRITE_SECURE_SETTINGS`（用 adb 一次性授权）；App 只提供 NTP 服务器写入与 Global 只读浏览，不做任意键写入。

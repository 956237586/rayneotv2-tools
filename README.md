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

## 耗电分析（查“到底谁在耗电”）

这台机器框架里没有电池设备，`batterystats` 算不出每进程 mAh（实测 `Capacity: 1000, Computed drain: 0, actual drain: 0`），
所以 App 用“电量曲线 + 进程 CPU 占比”两条腿归因：

- 电量/温度：每 60 秒读厂商写进 `Settings.Global` 的 `battery` / `battery_temperature` / `battery_discharging`
- 进程 CPU：每 60 秒调一次系统自带的 `dumpsys cpuinfo`（最近 5 分钟窗口里各进程占全部 CPU 的百分比）。
  不用 `/proc/<pid>/stat` 差分，是因为逐 pid 要 fork 400+ 次、实测 11.7 秒（再读 cmdline 23.6 秒），
  60 秒采一次等于自己成了最大的耗电项

数据都在 `Android/data/com.rayneo.diag/files/logs/`，`power.csv` 1.5 MB 滚动、留 6 个 `power_*.csv.gz`：

```
# S: ts,battery%,temp,discharging,proc_count
S,2026-11-05 23:55:37,55,41,1,101
# P: ts,pid,owner,name,cpu%
P,2026-11-05 23:55:37,508,mediacodec,android.hardware.media.c2@1.2-service,11.00
```

界面（主页工具栏「耗电分析」）给三张图：电量趋势、温度趋势、Top6 进程 CPU 走势，外加「这段时间谁最吃 CPU」排行。
**点排行里任意一行，就把那行的完整包名/进程名复制到剪贴板**（系统进程没有包名，复制出来的是进程名）。
## 进程列表（v1.5）

主页工具栏「进程列表」（`com.rayneo.diag/.ProcessActivity`）给完整进程表：

- 数据：`ps -A -o PID,PPID,USER,RSS,NAME` 全量（本机 400 个左右，含内核线程）+ `dumpsys cpuinfo` 的 CPU 占比，按 CPU 降序
- 每行：进程名 / PID / 属主 / 内存 MB / CPU%，点行看详情、复制包名；顶部有「仅第三方应用」筛选和 `复制列表`
- **只有真正装在 data 分区的第三方应用**（`pm list packages -3` 口径）才出现「结束」按钮，点了走 `am force-stop`；系统组件、`system_server`、内核线程、以及 `com.sei.serialportservice`（电量就是它写的）一律不给结束
- 实现用原生 `ListView` + `convertView` 复用，不引 AndroidX：初版 ScrollView 逐行建 400 个 View 会直接 ANR（`Input dispatching timed out … 5000ms`）
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

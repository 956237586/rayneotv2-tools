package com.rayneo.diag;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final String NTP_ALIYUN = "ntp.aliyun.com";
    private static final int COLOR_BG = 0xFFF2F3F5;
    private static final int COLOR_TEXT = 0xFF1F2329;
    private static final int COLOR_SUB = 0xFF646A73;

    private Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private String lastText = "";

    private TextView tvDevice, tvBattery, tvWifi, tvTime, tvNtpState, tvShell;
    private Button btnRecord;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String[][][] d = collect();
                    final String flat = flatten(d);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            lastText = flat;
                            render(d);
                        }
                    });
                }
            }).start();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        setupShizuku();

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, 100);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
        }
    }

    // ---------------- UI ----------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        HorizontalScrollView barScroll = new HorizontalScrollView(this);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(12, 12, 12, 8);

        bar.addView(button("刷新", new View.OnClickListener() {
            @Override public void onClick(View v) { refreshTask.run(); }
        }));
        bar.addView(button("系统设置", new View.OnClickListener() {
            @Override public void onClick(View v) { openSystemSettings(); }
        }));
        bar.addView(button("Shizuku 授权", new View.OnClickListener() {
            @Override public void onClick(View v) { requestShizuku(); }
        }));
        bar.addView(button("耗电分析", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, PowerActivity.class));
            }
        }));
        bar.addView(button("进程列表", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ProcessActivity.class));
            }
        }));
        bar.addView(button("复制", new View.OnClickListener() {
            @Override public void onClick(View v) { copyAll(); }
        }));
        bar.addView(button("分享", new View.OnClickListener() {
            @Override public void onClick(View v) { shareAll(); }
        }));
        bar.addView(button("NTP→阿里云", new View.OnClickListener() {
            @Override public void onClick(View v) { applyAliyunNtp(); }
        }));
        barScroll.addView(bar);
        root.addView(barScroll);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(12, 0, 12, 12);

        body.addView(cardTitle("设备信息"));
        tvDevice = card(body);

        body.addView(cardTitle("Shell 权限（Shizuku）"));
        tvShell = card(body);

        body.addView(cardTitle("电池"));
        tvBattery = card(body);

        body.addView(cardTitle("Wi-Fi / 网络"));
        tvWifi = card(body);

        body.addView(cardTitle("时间与校时"));
        tvTime = card(body);
        tvNtpState = new TextView(this);
        tvNtpState.setTextSize(13);
        tvNtpState.setTextColor(COLOR_SUB);
        tvNtpState.setPadding(8, 4, 8, 40);
        body.addView(tvNtpState);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(0, 8, 0, 8);
        btnRecord = button("开始记录电量", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRecord(); }
        });
        bottom.addView(btnRecord);
        bottom.addView(button("Global 设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, GlobalSettingsActivity.class));
            }
        }));
        body.addView(bottom);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView cardTitle(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setTextColor(COLOR_SUB);
        tv.setPadding(8, 16, 8, 6);
        return tv;
    }

    private TextView card(LinearLayout parent) {
        TextView tv = new TextView(this);
        tv.setTextSize(14);
        tv.setTextColor(COLOR_TEXT);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(6, 1);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(20);
        tv.setBackground(bg);
        tv.setPadding(24, 20, 24, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 4);
        tv.setLayoutParams(lp);
        parent.addView(tv);
        return tv;
    }

    // ---------------- 数据 ----------------

    private String[][][] collect() {
        String[][][] d = new String[5][][];
        d[0] = deviceRows();
        d[1] = shellRows();
        d[2] = batteryRows();
        d[3] = wifiRows();
        d[4] = timeRows();
        return d;
    }

    private String[][] deviceRows() {
        boolean priv = ShizukuBridge.running() && ShizukuBridge.granted();
        return new String[][]{
                {"型号", Build.MANUFACTURER + " " + Build.MODEL},
                {"系统", "Android " + Build.VERSION.RELEASE + "（SDK " + Build.VERSION.SDK_INT + "）"},
                {"固件", Build.DISPLAY},
                {"内核", String.valueOf(System.getProperty("os.version"))},
                {"序列号", serialLine(priv)},
                {"CPU 占用", cpuLine(priv)},
                {"内存", memoryLine()},
                {"开机时长", (SystemClock.elapsedRealtime() / 1000) + " 秒"},
        };
    }

    private String[][] shellRows() {
        boolean ready = ShizukuBridge.running();
        boolean priv = ready && ShizukuBridge.granted();
        if (!priv) {
            return new String[][]{
                    {"状态", ShizukuBridge.statusLine()},
                    {"说明", ready ? "点顶部「Shizuku 授权」并在设备上允许，即可读到 /proc 与完整进程列表"
                            : "Shizuku 服务没在跑。用 adb 执行其启动命令后重开本页"},
                    {"现在的边界", "序列号 / /proc/stat / /proc/loadavg / 全进程列表都读不到（SELinux 限制）"},
            };
        }
        String load = ShizukuBridge.sh("cat /proc/loadavg");
        String procs = ShizukuBridge.sh("ps -A | wc -l");
        String uptime = ShizukuBridge.sh("cat /proc/uptime");
        return new String[][]{
                {"状态", ShizukuBridge.statusLine()},
                {"负载 1/5/15 分钟", load == null ? "读取失败" : load},
                {"进程数", procs == null ? "读取失败" : procs.trim()},
                {"系统运行", uptime == null ? "读取失败" : (uptime.split(" ")[0] + " 秒")},
                {"已补上的项", "序列号、CPU 汇总、负载、进程数（原先三个 n/a 全部打通）"},
        };
    }

    private String serialLine(boolean priv) {
        if (priv) {
            String s = ShizukuBridge.sh("getprop ro.serialno");
            if (s != null && !s.trim().isEmpty() && !"null".equals(s.trim())) return s.trim();
        }
        return prop("ro.serialno");
    }


    private String[][] batteryRows() {
        String level = global("battery");
        String glass = global("glass_battery");
        String temp = global("battery_temperature");
        String discharging = global("battery_discharging");
        String care = global("battery_care_mode");
        String log = BatteryLogService.summary(this);
        return new String[][]{
                {"当前电量", level + "%"},
                {"厂商上报", glass},
                {"温度", temp + " ℃"},
                {"状态", "1".equals(discharging) ? "放电中" : "充电 / 满电"},
                {"充电保护", "1".equals(care) ? "开启" : "关闭"},
                {"框架视角", "present=" + global("battery_present") + "（框架无电池设备）"},
                {"采样记录", log},
                {"归因采样", PowerLogService.summary(this)},
        };
    }

    private String[][] wifiRows() {
        String[][] rows;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return new String[][]{{"状态", "Wi-Fi 服务不可用"}};
            WifiInfo info = wm.getConnectionInfo();
            if (info != null && info.getNetworkId() != -1) {
                rows = new String[][]{
                        {"开关", wm.isWifiEnabled() ? "已开启" : "已关闭"},
                        {"SSID", info.getSSID()},
                        {"BSSID", info.getBSSID()},
                        {"信号", info.getRssi() + " dBm"},
                        {"速率", info.getLinkSpeed() + " Mbps"},
                        {"频段", info.getFrequency() + " MHz"},
                        {"本机 IP", ipOf("wlan0")},
                        {"MAC", info.getMacAddress()},
                };
            } else {
                rows = new String[][]{
                        {"开关", wm.isWifiEnabled() ? "已开启" : "已关闭"},
                        {"SSID", "未连接"},
                        {"本机 IP", ipOf("wlan0")},
                };
            }
        } catch (Throwable t) {
            return new String[][]{{"状态", "读取失败：" + t.getClass().getSimpleName()}};
        }
        return rows;
    }

    private String[][] timeRows() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        return new String[][]{
                {"系统时间", f.format(new Date())},
                {"时区", TimeZone.getDefault().getID()},
                {"自动校时", global("auto_time")},
                {"自动时区", global("auto_time_zone")},
                {"NTP 服务器", global("ntp_server")},
        };
    }

    private void render(String[][][] data) {
        tvDevice.setText(block(data[0]));
        tvShell.setText(block(data[1]));
        tvBattery.setText(block(data[2]));
        tvWifi.setText(block(data[3]));
        tvTime.setText(block(data[4]));
        String ntp = global("ntp_server");
        tvNtpState.setText(NTP_ALIYUN.equals(ntp)
                ? "当前已是国内源：" + ntp
                : "当前为 " + ntp + "，点上方按钮可一键切到 " + NTP_ALIYUN);
        btnRecord.setText(BatteryLogService.isRunning() ? "停止记录电量" : "开始记录电量");
    }

    private String block(String[][] rows) {
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            sb.append(r[0]).append("：").append(r[1]).append("\n");
        }
        return sb.toString().trim();
    }

    private String flatten(String[][][] data) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("雷鸟魔盒诊断 ").append(f.format(new Date())).append("\n");
        String[] names = {"【设备】", "【Shell】", "【电池】", "【Wi-Fi】", "【时间】"};
        for (int i = 0; i < data.length; i++) {
            sb.append(names[i]).append("\n").append(block(data[i])).append("\n\n");
        }
        android.util.Log.i("RayNeoDiag", sb.toString());
        return sb.toString();
    }

    // ---------------- 工具方法 ----------------

    private String global(String key) {
        try {
            String v = Settings.Global.getString(getContentResolver(), key);
            return v == null ? "null" : v;
        } catch (Throwable t) {
            return "读取失败";
        }
    }

    private String ipOf(String iface) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(iface);
            if (ni == null) return "无";
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address) return a.getHostAddress();
            }
        } catch (Throwable ignored) {
        }
        return "无";
    }

    private String memoryLine() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return (mi.availMem / 1048576) + " MB 可用 / " + (mi.totalMem / 1048576) + " MB 总计";
        } catch (Throwable t) {
            return "读取失败";
        }
    }

    private String cpuLine(boolean priv) {
        String[] lines = priv ? runTopPriv() : runTop();
        String last = null;
        for (String l : lines) {
            if (l.contains("%cpu") && l.contains("%idle")) last = l;
        }
        if (last != null) {
            java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("(\\d+)%cpu").matcher(last);
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("(\\d+)%idle").matcher(last);
            if (m1.find() && m2.find()) {
                int total = Integer.parseInt(m1.group(1));
                int idle = Integer.parseInt(m2.group(1));
                if (total > 0) {
                    return String.format(Locale.US, "%.1f%%（空闲 %d%% / 共 %d%%）",
                            100.0 * (total - idle) / total, 100 * idle / total, total);
                }
            }
        }
        return "受限（ROM 未开放 /proc/stat）";
    }

    private String[] runTopPriv() {
        String out = ShizukuBridge.sh("top -b -n 2 -d 0.5 -m 1");
        if (out == null || out.isEmpty()) return new String[0];
        return out.split("\n");
    }

    private String[] runTop() {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/top", "-b", "-n", "2", "-d", "0.5", "-m", "1"});
            BufferedReader br = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            java.util.List<String> out = new java.util.ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) out.add(line);
            br.close();
            p.waitFor();
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private String prop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/getprop", key});
            BufferedReader br = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            br.close();
            if (line == null || line.trim().isEmpty()) return "受限（应用无权读 prop）";
            return line.trim();
        } catch (Throwable t) {
            return "受限";
        }
    }

    // ---------------- 动作 ----------------

    private void setupShizuku() {
        try {
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    Toast.makeText(MainActivity.this,
                            grantResult == PackageManager.PERMISSION_GRANTED ? "Shizuku 已授权" : "Shizuku 授权被拒绝",
                            Toast.LENGTH_SHORT).show();
                    refreshTask.run();
                }
            });
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    refreshTask.run();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void requestShizuku() {
        if (!ShizukuBridge.running()) {
            Toast.makeText(this, "Shizuku 服务未运行。先用 adb 执行其给出的启动命令，再回来点这个按钮",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (ShizukuBridge.granted()) {
            Toast.makeText(this, "已授权，Shell 能力可用（序列号 / 负载 / 进程数都能读了）",
                    Toast.LENGTH_SHORT).show();
            refreshTask.run();
            return;
        }
        ShizukuBridge.request(2001);
        Toast.makeText(this, "已在 Shizuku 里发起授权，请在设备屏上点「允许」", Toast.LENGTH_LONG).show();
    }

    private void openSystemSettings() {
        // 1) 标准入口：会解析到 com.android.tv.settings/.MainSettings（设备实测焦点即此 Activity）
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
            i.addCategory(Intent.CATEGORY_DEFAULT);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        // 2) 兜底：直接点名 Activity
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(
                    "com.android.tv.settings", "com.android.tv.settings.MainSettings"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开系统设置：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyAliyunNtp() {
        try {
            Settings.Global.putString(getContentResolver(), "ntp_server", NTP_ALIYUN);
            String now = global("ntp_server");
            if (NTP_ALIYUN.equals(now)) {
                Toast.makeText(this, "已切到 " + NTP_ALIYUN + "，稍后自动生效", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "写入未生效，当前仍为 " + now
                        + "\n需执行 adb 授权 WRITE_SECURE_SETTINGS", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "写入失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
        refreshTask.run();
    }

    private void toggleRecord() {
        Intent i = new Intent(this, BatteryLogService.class);
        if (BatteryLogService.isRunning()) {
            stopService(i);
            Toast.makeText(this, "已停止记录", Toast.LENGTH_SHORT).show();
        } else {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Toast.makeText(this, "开始记录，每 60 秒一条", Toast.LENGTH_SHORT).show();
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { refreshTask.run(); }
        }, 800);
    }

    private void copyAll() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("diag", lastText));
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    private void shareAll() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, lastText);
        startActivity(Intent.createChooser(i, "分享诊断结果"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacks(refreshTask);
    }
}

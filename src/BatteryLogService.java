package com.rayneo.diag;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

public class BatteryLogService extends Service {

    private static final long INTERVAL_MS = 60_000L;
    private static final long MAX_BYTES = 1_500_000L;   // 单文件上限 1.5MB
    private static final int MAX_ARCHIVES = 6;          // 最多保留 6 个压缩存档

    private static volatile boolean running = false;
    private Handler handler;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public static boolean isRunning() {
        return running;
    }

    private static File logFile(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "logs");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "battery.csv");
    }

    public static String summary(Context ctx) {
        File f = logFile(ctx);
        String state = running ? "记录中" : "未记录";
        if (!f.exists()) return state + "（暂无数据）";
        int lines = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(open(f)))) {
            while (br.readLine() != null) lines++;
        } catch (Throwable ignored) {
        }
        long kb = Math.max(1, f.length() / 1024);
        return state + "，" + Math.max(0, lines - 1) + " 条，" + kb + " KB";
    }

    private static java.io.InputStream open(File f) throws Exception {
        return new FileInputStream(f);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        running = true;
        handler.removeCallbacks(sample);
        handler.post(sample);
        return START_STICKY;
    }

    private final Runnable sample = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                appendSample();
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private void appendSample() throws Exception {
        File f = logFile(this);
        if (f.length() > MAX_BYTES) rotate(f);

        boolean isNew = !f.exists();
        BufferedWriter w = new BufferedWriter(new FileWriter(f, true));
        if (isNew) {
            w.write("时间,电量%,温度,状态\n");
        }
        w.write(fmt.format(new Date()) + ","
                + global("battery") + ","
                + global("battery_temperature") + ","
                + ("1".equals(global("battery_discharging")) ? "放电" : "充电") + "\n");
        w.close();
    }

    private String global(String key) {
        try {
            String v = Settings.Global.getString(getContentResolver(), key);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    private void rotate(File f) throws Exception {
        File dir = f.getParentFile();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File gz = new File(dir, "battery_" + stamp + ".csv.gz");
        try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(gz));
             FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        // 截断主文件（保留表头）
        try (FileWriter w = new FileWriter(f, false)) {
            w.write("时间,电量%,温度,状态\n");
        }
        // 只保留最近 MAX_ARCHIVES 个存档
        File[] all = dir.listFiles(new java.io.FileFilter() {
            @Override public boolean accept(File f) {
                String n = f.getName();
                return n.startsWith("battery_") && n.endsWith(".csv.gz");
            }
        });
        if (all != null && all.length > MAX_ARCHIVES) {
            Arrays.sort(all, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (int i = 0; i < all.length - MAX_ARCHIVES; i++) all[i].delete();
        }
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    "diag_log", "电量采样记录", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 26) b.setChannelId("diag_log");
        b.setContentTitle("雷鸟魔盒诊断 · 电量记录中")
                .setContentText("每 60 秒采样一次，写入 battery.csv")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setOngoing(true);
        startForeground(1001, b.build());
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacks(sample);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * 耗电归因采样服务：每 60 秒一次，记「电量 + 每个进程最近 5 分钟的 CPU 占比」到 power.csv。
 * 单次采样只有 settings / ps / dumpsys cpuinfo 三个命令，实测 0.1 秒级，不会反过来把电吃掉。
 */
public class PowerLogService extends Service {

    private static final long MAX_BYTES = 1_500_000L;
    private static final int MAX_ARCHIVES = 6;

    private static volatile boolean running = false;
    private Handler handler;
    private int samples = 0;

    public static boolean isRunning() {
        return running;
    }

    public static File logFile(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "logs");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "power.csv");
    }

    public static String summary(Context ctx) {
        File f = logFile(ctx);
        String state = running ? "归因采样中" : "未采样";
        if (!f.exists()) return state + "（暂无数据）";
        int rows = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
            String line;
            while ((line = br.readLine()) != null) if (line.startsWith("S,")) rows++;
        } catch (Throwable ignored) {
        }
        return state + "，" + rows + " 个采样点，" + Math.max(1, f.length() / 1024) + " KB";
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
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    long t0 = android.os.SystemClock.elapsedRealtime();
                    try {
                        PowerCollector.Sample s = PowerCollector.sample();
                        append(s);
                        samples++;
                        android.util.Log.i("RayNeoDiag", "power sample " + s.time + " took "
                                + (android.os.SystemClock.elapsedRealtime() - t0) + "ms, procs=" + s.procs.size()
                                + (s.note.isEmpty() ? "" : (" note=" + s.note)));
                    } catch (Throwable t) {
                        android.util.Log.i("RayNeoDiag", "power sample failed: " + t);
                    }
                    handler.postDelayed(tick, PowerCollector.INTERVAL_MS);
                }
            }).start();
        }
    };

    private void append(PowerCollector.Sample s) throws Exception {
        File f = logFile(this);
        if (f.length() > MAX_BYTES) rotate(f);

        boolean isNew = !f.exists();
        StringBuilder sb = new StringBuilder();
        if (isNew) {
            sb.append("# rayneo power log | S: ts,battery%,temp,discharging,proc_count | P: ts,pid,owner,name,cpu%\n");
        }
        sb.append("S,").append(s.time).append(',').append(s.battery).append(',').append(s.temp)
                .append(',').append(s.discharging ? 1 : 0).append(',').append(s.procCount).append('\n');
        for (PowerCollector.Proc p : s.procs) {
            sb.append("P,").append(s.time).append(',').append(p.pid).append(',').append(p.owner)
                    .append(',').append(clean(p.name)).append(',')
                    .append(String.format(Locale.US, "%.2f", p.cpuPct)).append('\n');
        }
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(sb.toString());
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace(',', '_');
    }

    private void rotate(File f) throws Exception {
        File dir = f.getParentFile();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File gz = new File(dir, "power_" + stamp + ".csv.gz");
        try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(gz));
             FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        try (FileWriter w = new FileWriter(f, false)) {
            w.write("# rayneo power log | S: ts,battery%,temp,discharging,proc_count | P: ts,pid,owner,name,cpu%\n");
        }
        File[] all = dir.listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(File file) {
                String n = file.getName();
                return n.startsWith("power_") && n.endsWith(".csv.gz");
            }
        });
        if (all != null && all.length > MAX_ARCHIVES) {
            Arrays.sort(all, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
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
                    "diag_power", "耗电归因采样", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 26) b.setChannelId("diag_power");
        b.setContentTitle("雷鸟魔盒诊断 · 耗电归因采样中")
                .setContentText("每 60 秒记一次各进程 CPU 占比，写入 power.csv")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setOngoing(true);
        startForeground(1002, b.build());
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

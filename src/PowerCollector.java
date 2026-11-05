package com.rayneo.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * 进程级 CPU 采样器：借 Shizuku 的 shell 身份调系统自带的 dumpsys。
 *
 * 为什么不用（看起来更“原生”的）API：
 *  - BatteryStatsManager.getBatteryUsageStats() 需要 BATTERY_STATS（signature|privileged），
 *    普通 App 拿不到；而且本机框架没有电池设备（/sys/class/power_supply 为空），
 *    batterystats 里的 mAh 归因全是 0（实测 Capacity 1000 / Computed drain 0）。
 *  - StatsManager / statsd pull 需要 PACKAGE_USAGE_STATS + DUMP，同样只能 shell 用。
 *  - UsageStatsManager 只给“用了多久”，和电无关。
 *  - 逐 pid 读 /proc/<pid>/stat 精度最高，但要 fork 400+ 次，实测 11.7 秒（再加 cmdline 23.6 秒），
 *    60 秒采一次等于自己就是最大的耗电项，所以改用 dumpsys cpuinfo（实测 0.03 秒）。
 *
 * 数据语义：dumpsys cpuinfo 给的是**最近 5 分钟滑动窗口**里每个进程占全部 CPU 的百分比，
 * 正好适合“谁在持续吃 CPU”这种归因问题。
 */
public final class PowerCollector {

    public static final long INTERVAL_MS = 60_000L;

    private PowerCollector() {
    }

    public static class Proc {
        public int pid;
        public String owner = "";
        public String name = "";
        public double cpuPct;
        public double share;   // 与 cpuPct 同义，保留给图表层
    }

    public static class Sample {
        public String time;
        public int battery = -1;
        public int temp = -1;
        public boolean discharging;
        public long uptimeSec = -1;
        public int procCount;
        public String note = "";
        public List<Proc> procs = new ArrayList<Proc>();
    }

    /**
     * 一次采样：电量 3 条 settings + uptime + cpuinfo + ps（名字/属主）。
     * 全部是系统自带命令，除了 settings/ps/cpuinfo 各自一次进程外没有额外 fork。
     */
    public static final String SCRIPT =
            "echo \"B $(settings get global battery)\";"
                    + "echo \"T $(settings get global battery_temperature)\";"
                    + "echo \"D $(settings get global battery_discharging)\";"
                    + "echo \"U $(awk '{print $1}' /proc/uptime)\";"
                    + "echo \"---P---\";"
                    + "ps -A -o PID,USER,NAME 2>/dev/null;"
                    + "echo \"---C---\";"
                    + "dumpsys cpuinfo 2>/dev/null";

    public static Sample sample() {
        Sample s = new Sample();
        s.time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());

        String raw = ShizukuBridge.sh(SCRIPT);
        if (raw == null || raw.trim().isEmpty()) {
            s.note = "需要 Shizuku（shell 权限）才能读进程 CPU";
            return s;
        }

        String[] lines = raw.split("\n");
        java.util.Map<Integer, String> owners = new java.util.HashMap<Integer, String>();
        java.util.Map<Integer, String> names = new java.util.HashMap<Integer, String>();
        List<Proc> list = new ArrayList<Proc>();
        boolean inPs = false, inC = false;

        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.equals("---P---")) {
                inPs = true;
                inC = false;
                continue;
            }
            if (t.equals("---C---")) {
                inPs = false;
                inC = true;
                continue;
            }
            if (inPs) {
                // "  508 u0_a95 name..."
                String[] p = t.split("\\s+", 3);
                if (p.length >= 2 && p[0].matches("\\d+")) {
                    int pid = Integer.parseInt(p[0]);
                    owners.put(pid, p[1]);
                    if (p.length >= 3) names.put(pid, p[2]);
                }
                continue;
            }
            if (inC) {
                // "  7.8% 508/android.hardware.media.c2@1.2-service: 0.9% user + 6.8% kernel ..."
                if (!t.endsWith("%") && t.indexOf('%') < 0) continue;
                int slash = t.indexOf('/');
                if (slash < 0) continue;
                String head = t.substring(0, slash).trim();   // "7.8% 508"
                String[] hp = head.split("\\s+");
                if (hp.length < 2) continue;
                double pct;
                int pid;
                try {
                    pct = Double.parseDouble(hp[0].replace("%", ""));
                    pid = Integer.parseInt(hp[1]);
                } catch (Throwable e) {
                    continue;
                }
                Proc pr = new Proc();
                pr.pid = pid;
                pr.cpuPct = pct;
                pr.share = pct;
                pr.owner = owners.containsKey(pid) ? owners.get(pid) : "";
                String nm = names.get(pid);
                if (nm == null) {
                    String tail = t.substring(slash + 1);
                    int colon = tail.indexOf(':');
                    nm = colon > 0 ? tail.substring(0, colon) : tail;
                }
                pr.name = nm.trim();
                list.add(pr);
                continue;
            }
            if (t.startsWith("B ")) s.battery = (int) parseIntSafe(t.substring(2), -1);
            else if (t.startsWith("T ")) s.temp = (int) parseIntSafe(t.substring(2), -1);
            else if (t.startsWith("D ")) s.discharging = "1".equals(t.substring(2).trim());
            else if (t.startsWith("U ")) s.uptimeSec = (long) parseDoubleSafe(t.substring(2));
        }

        java.util.Collections.sort(list, new java.util.Comparator<Proc>() {
            @Override
            public int compare(Proc a, Proc b) {
                return Double.compare(b.cpuPct, a.cpuPct);
            }
        });
        for (int i = 0; i < Math.min(12, list.size()); i++) s.procs.add(list.get(i));
        s.procCount = list.size();
        if (s.procs.isEmpty()) s.note = "cpuinfo 没解析出进程（窗口内无活动？）";
        return s;
    }

    static long parseIntSafe(String v, long def) {
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    static double parseDoubleSafe(String v) {
        try {
            return Double.parseDouble(v.trim());
        } catch (Throwable t) {
            return -1;
        }
    }
}

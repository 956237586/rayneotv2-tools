package com.rayneo.diag;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 耗电分析页：把 battery.csv（电量/温度）和 power.csv（每进程 CPU 增量）画成图。
 * 目的只有一个 —— 一眼看出是哪个进程在吃电。
 */
public class PowerActivity extends Activity {

    private static final int COLOR_BG = 0xFFF2F3F5;
    private static final int COLOR_TEXT = 0xFF1F2329;
    private static final int COLOR_SUB = 0xFF646A73;
    private static final int MAX_POINTS = 300;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean alive = true;

    private TextView tvSummary, tvHint;
    private Button btnSample;
    private ChartViews.LineChart chartBattery, chartTemp, chartProcs;
    private ChartViews.Bars chartRank;
    private String lastText = "";

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Object[] d = load();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            render(d);
                        }
                    });
                }
            }).start();
            handler.postDelayed(this, 30_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        handler.post(refresh);
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
            @Override
            public void onClick(View v) {
                refresh.run();
            }
        }));
        btnSample = button("开始采样", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSample();
            }
        });
        bar.addView(btnSample);
        bar.addView(button("复制数据", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("power", lastText));
                Toast.makeText(PowerActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        }));
        bar.addView(button("分享", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, lastText);
                startActivity(Intent.createChooser(i, "分享耗电归因"));
            }
        }));
        barScroll.addView(bar);
        root.addView(barScroll);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(12, 0, 12, 12);

        tvSummary = new TextView(this);
        tvSummary.setTextSize(13);
        tvSummary.setTextColor(COLOR_TEXT);
        tvSummary.setTypeface(Typeface.MONOSPACE);
        tvSummary.setLineSpacing(5, 1);
        tvSummary.setPadding(22, 18, 22, 18);
        tvSummary.setBackground(roundRect());
        body.addView(tvSummary, matchWrap(0, 6));

        body.addView(sectionTitle("电量趋势（%）"));
        chartBattery = chart();
        body.addView(chartBattery, fixedHeight(150));

        body.addView(sectionTitle("温度趋势（℃）"));
        chartTemp = chart();
        body.addView(chartTemp, fixedHeight(132));

        body.addView(sectionTitle("进程 CPU 占比走势（%，满 100 就是 1 核跑满）"));
        chartProcs = chart();
        body.addView(chartProcs, fixedHeight(176));

        body.addView(sectionTitle("这段时间谁最吃 CPU（平均占全部 CPU 的比例，点一行可复制包名）"));
        chartRank = new ChartViews.Bars(this);
        chartRank.setEmpty("还没有采样点，点上方「开始采样」");
        chartRank.setOnRowClick(new ChartViews.Bars.OnRowClick() {
            @Override
            public void onRowClick(String name, int index) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("proc", name));
                Toast.makeText(PowerActivity.this, "已复制：" + name, Toast.LENGTH_SHORT).show();
            }
        });
        body.addView(chartRank, fixedHeight(250));

        tvHint = new TextView(this);
        tvHint.setTextSize(12);
        tvHint.setTextColor(COLOR_SUB);
        tvHint.setLineSpacing(5, 1);
        tvHint.setPadding(10, 14, 10, 30);
        tvHint.setText("数据来源：电量每 60 秒一条（厂商写的 Settings.Global: battery / battery_temperature）；"
                + "进程 CPU 每 60 秒调一次系统自带的 dumpsys cpuinfo（给的是最近 5 分钟窗口里各进程占全部 CPU 的比例，需要 Shizuku 的 shell 权限）。\n"
                + "框架自带的 batterystats 在这台机器上算不出 mAh（/sys/class/power_supply 是空的，框架认为没有电池），"
                + "所以“谁耗电”是按 CPU 时间加权的估算，不是厂商电量计；平均放电速率是整段线性拟合，中间插过电会被拉低。");
        body.addView(tvHint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private ChartViews.LineChart chart() {
        ChartViews.LineChart c = new ChartViews.LineChart(this);
        c.setEmpty("还没有采样点");
        return c;
    }

    private LinearLayout.LayoutParams fixedHeight(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().density * dp));
        lp.setMargins(0, 0, 0, 6);
        return lp;
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, top, 0, bottom);
        return lp;
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

    private TextView sectionTitle(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setTextColor(COLOR_SUB);
        tv.setPadding(8, 14, 8, 6);
        return tv;
    }

    private GradientDrawable roundRect() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(20);
        return bg;
    }

    // ---------------- 数据 ----------------

    private File logDir() {
        return new File(getExternalFilesDir(null), "logs");
    }

    private static class BatPoint {
        long ts;
        float battery;
        float temp;
    }

    private static class ProcPoint {
        long ts;
        Map<String, Float> share = new HashMap<String, Float>();
    }

    private Object[] load() {
        // 1) battery.csv
        List<BatPoint> bat = new ArrayList<BatPoint>();
        File bf = new File(logDir(), "battery.csv");
        if (bf.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(bf)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("时间") || line.startsWith("#")) continue;
                    String[] p = line.split(",");
                    if (p.length < 3) continue;
                    try {
                        BatPoint pt = new BatPoint();
                        pt.ts = parseTime(p[0]);
                        pt.battery = Float.parseFloat(p[1].trim());
                        pt.temp = Float.parseFloat(p[2].trim());
                        if (pt.battery >= 0) bat.add(pt);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 2) power.csv
        Map<Long, ProcPoint> byTs = new HashMap<Long, ProcPoint>();
        Map<String, Double> rank = new HashMap<String, Double>();
        File pf = new File(logDir(), "power.csv");
        if (pf.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(pf)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("#") || line.trim().isEmpty()) continue;
                    String[] p = line.split(",");
                    if (p.length < 6 || !"P".equals(p[0])) continue;
                    long ts = parseTime(p[1]);
                    String name = p[4];
                    float share;
                    try {
                        share = Float.parseFloat(p[5].trim());
                    } catch (Throwable t) {
                        continue;
                    }
                    ProcPoint pt = byTs.get(ts);
                    if (pt == null) {
                        pt = new ProcPoint();
                        pt.ts = ts;
                        byTs.put(ts, pt);
                    }
                    pt.share.put(name, share);
                    Double acc = rank.get(name);
                    rank.put(name, (acc == null ? 0 : acc) + share);
                }
            } catch (Throwable ignored) {
            }
        }

        // 3) top 进程：按“平均占比”排序更公允
        final int sampleCount = Math.max(1, byTs.size());
        List<String> names = new ArrayList<String>(rank.keySet());
        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Double.compare(rank.get(b), rank.get(a));
            }
        });
        List<String> rankNames = new ArrayList<String>();
        List<Double> rankVals = new ArrayList<Double>();
        for (int i = 0; i < Math.min(8, names.size()); i++) {
            rankNames.add(names.get(i));
            rankVals.add(rank.get(names.get(i)) / sampleCount);
        }

        // 4) 图表数据
        List<ProcPoint> pps = new ArrayList<ProcPoint>(byTs.values());
        Collections.sort(pps, new Comparator<ProcPoint>() {
            @Override
            public int compare(ProcPoint a, ProcPoint b) {
                return Long.compare(a.ts, b.ts);
            }
        });
        if (pps.size() > MAX_POINTS) pps = new ArrayList<ProcPoint>(pps.subList(pps.size() - MAX_POINTS, pps.size()));

        int keep = Math.min(6, names.size());
        List<float[]> procSeries = new ArrayList<float[]>();
        List<String> procLegend = new ArrayList<String>();
        List<Integer> procColors = new ArrayList<Integer>();
        for (int k = 0; k < keep; k++) {
            String name = names.get(k);
            float[] arr = new float[pps.size()];
            for (int i = 0; i < pps.size(); i++) {
                Float v = pps.get(i).share.get(name);
                arr[i] = v == null ? 0f : v;
            }
            procSeries.add(arr);
            procLegend.add(name);
            procColors.add(ChartViews.PALETTE[k % ChartViews.PALETTE.length]);
        }

        if (bat.size() > MAX_POINTS) bat = new ArrayList<BatPoint>(bat.subList(bat.size() - MAX_POINTS, bat.size()));
        float[] batArr = new float[bat.size()];
        float[] tempArr = new float[bat.size()];
        for (int i = 0; i < bat.size(); i++) {
            batArr[i] = bat.get(i).battery;
            tempArr[i] = bat.get(i).temp;
        }

        // 5) 摘要
        StringBuilder sb = new StringBuilder();
        sb.append("电量采样：").append(bat.size()).append(" 点");
        if (bat.size() >= 2) sb.append("，").append(fmt(bat.get(0).ts)).append(" ~ ").append(fmt(bat.get(bat.size() - 1).ts));
        sb.append("\n");
        sb.append("进程采样：").append(byTs.size()).append(" 点");
        if (pps.size() >= 2) sb.append("，").append(fmt(pps.get(0).ts)).append(" ~ ").append(fmt(pps.get(pps.size() - 1).ts));
        sb.append("\n");

        if (!bat.isEmpty()) {
            BatPoint last = bat.get(bat.size() - 1);
            sb.append("最近一次电量：").append((int) last.battery).append("%");
            if (last.temp > 0) sb.append("，温度 ").append((int) last.temp).append(" ℃");
            sb.append("\n");
            long span = last.ts - bat.get(0).ts;
            if (span > 60_000L && bat.get(0).battery > last.battery) {
                double hours = span / 3600000.0;
                sb.append(String.format(Locale.US, "平均放电速率：%.1f %%/小时（%.0f%% → %.0f%%，%.1f 小时）\n",
                        (bat.get(0).battery - last.battery) / hours, bat.get(0).battery, last.battery, hours));
            }
        } else {
            sb.append("电量记录：暂无（先在主页开「记录电量」）\n");
        }
        if (!rankNames.isEmpty()) {
            sb.append("最吃 CPU：").append(rankNames.get(0))
                    .append(String.format(Locale.US, "（平均 %.1f%% CPU）", rankVals.get(0))).append("\n");
        }
        sb.append("文件：logs/battery.csv + logs/power.csv");
        lastText = sb.toString();

        return new Object[]{bat, pps, procSeries, procLegend, procColors, batArr, tempArr, rankNames, rankVals, sb.toString()};
    }

    @SuppressWarnings("unchecked")
    private void render(Object[] d) {
        List<BatPoint> bat = (List<BatPoint>) d[0];
        List<ProcPoint> pps = (List<ProcPoint>) d[1];
        List<float[]> procSeries = (List<float[]>) d[2];
        List<String> procLegend = (List<String>) d[3];
        List<Integer> procColors = (List<Integer>) d[4];
        float[] batArr = (float[]) d[5];
        float[] tempArr = (float[]) d[6];
        List<String> rankNames = (List<String>) d[7];
        List<Double> rankVals = (List<Double>) d[8];
        String summary = (String) d[9];

        tvSummary.setText(summary);

        List<float[]> batSeries = new ArrayList<float[]>();
        batSeries.add(batArr);
        List<String> batLegend = new ArrayList<String>();
        batLegend.add("电量%");
        List<Integer> batColors = new ArrayList<Integer>();
        batColors.add(ChartViews.PALETTE[0]);
        chartBattery.setEmpty(bat.isEmpty() ? "还没有电量记录（主页点「记录电量」）" : "暂无数据");
        chartBattery.setData(batSeries, batLegend, batColors, "%", 100);

        List<float[]> tempSeries = new ArrayList<float[]>();
        tempSeries.add(tempArr);
        List<String> tempLegend = new ArrayList<String>();
        tempLegend.add("温度");
        List<Integer> tempColors = new ArrayList<Integer>();
        tempColors.add(ChartViews.PALETTE[1]);
        chartTemp.setEmpty(bat.isEmpty() ? "还没有温度记录" : "暂无数据");
        chartTemp.setData(tempSeries, tempLegend, tempColors, "℃", -1);

        chartProcs.setEmpty("还没有进程采样（点「开始采样」，需要 Shizuku 在跑）");
        chartProcs.setData(procSeries, procLegend, procColors, "%CPU", -1);

        chartRank.setEmpty("还没有进程采样（点「开始采样」，需要 Shizuku 在跑）");
        chartRank.setData(rankNames, rankVals, "%CPU");

        btnSample.setText(PowerLogService.isRunning() ? "停止采样" : "开始采样");
    }

    private static long parseTime(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            Date date = f.parse(s.trim());
            return date == null ? 0 : date.getTime();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String fmt(long ts) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ts));
    }

    private void toggleSample() {
        Intent i = new Intent(this, PowerLogService.class);
        if (PowerLogService.isRunning()) {
            stopService(i);
            Toast.makeText(this, "已停止采样", Toast.LENGTH_SHORT).show();
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Toast.makeText(this, "开始采样：每 60 秒一条（需要 Shizuku 在线）", Toast.LENGTH_SHORT).show();
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh.run();
            }
        }, 1500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        alive = false;
        handler.removeCallbacks(refresh);
    }
}

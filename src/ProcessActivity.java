package com.rayneo.diag;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 完整进程列表：ps -A 全量（含内核线程），叠加 dumpsys cpuinfo 的 CPU 占比。
 *
 * 列表用原生 ListView + BaseAdapter：本机有 400 个进程，一次性 new 400 行 View 会把主线程堵死
 * （实测直接 ANR），ListView 只创建屏幕内的行、复用 convertView 才稳。
 *
 * 结束操作只对**第三方应用**开放（uid 形如 u0_aXX 且有包名）：
 * system uid 的常驻服务 force-stop 之后会被 system_server 立刻拉起，没意义；
 * com.sei.serialportservice 这类还负责把电量写进 Settings.Global，杀了直接丢电量数据。
 */
public class ProcessActivity extends Activity {

    private static final int COLOR_TEXT = 0xFF1F2329;
    private static final int COLOR_SUB = 0xFF646A73;
    private static final int COLOR_WARN = 0xFFD64545;
    private static final int COLOR_MID = 0xFFE0A100;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ListView listView;
    private ProcAdapter adapter;
    private TextView tvSummary;
    private Button btnFilter;
    private boolean onlyThird = false;

    private List<Row> rows = new ArrayList<Row>();
    private List<Row> shown = new ArrayList<Row>();

    private static class Row {
        int pid;
        int ppid;
        String user;
        long rssKb;
        String name;
        double cpuPct;
        boolean thirdParty;
        boolean self;
        String note;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F3F5);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF1F2329);
        top.setPadding(16, 14, 16, 14);

        Button back = smallButton("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back);

        TextView title = new TextView(this);
        title.setText("进程列表（CPU 占比 / 内存）");
        title.setTextSize(15);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(20, 0, 0, 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(title);

        Button refresh = smallButton("刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        top.addView(refresh);
        root.addView(top);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(12, 10, 12, 4);

        btnFilter = smallButton("仅第三方应用");
        btnFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onlyThird = !onlyThird;
                btnFilter.setText(onlyThird ? "显示全部进程" : "仅第三方应用");
                applyFilter();
            }
        });
        bar.addView(btnFilter);

        Button copy = smallButton("复制列表");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StringBuilder sb = new StringBuilder();
                for (Row r : shown) {
                    sb.append(r.name).append('\t').append(r.pid).append('\t').append(r.user)
                            .append('\t').append(String.format(Locale.US, "%.1f%%", r.cpuPct))
                            .append('\t').append(String.format(Locale.US, "%.1fMB", r.rssKb / 1024.0))
                            .append('\n');
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("procs", sb.toString()));
                Toast.makeText(ProcessActivity.this, "已复制 " + shown.size() + " 行", Toast.LENGTH_SHORT).show();
            }
        });
        bar.addView(copy);
        root.addView(bar);

        tvSummary = new TextView(this);
        tvSummary.setTextSize(12);
        tvSummary.setTextColor(COLOR_SUB);
        tvSummary.setPadding(16, 4, 16, 8);
        tvSummary.setText("读取中…");
        root.addView(tvSummary);

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setPadding(12, 0, 12, 24);
        listView.setClipToPadding(false);
        listView.setCacheColorHint(0);
        adapter = new ProcAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < shown.size()) showDetail(shown.get(position));
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refresh();
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setPadding(18, 6, 18, 6);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private void refresh() {
        tvSummary.setText("读取中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Row> list = load();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        rows = list;
                        applyFilter();
                    }
                });
            }
        }).start();
    }

    private List<Row> load() {
        // pm 放在最前面：如果 shell 侧有任何截断，先保住"第三方包名单"这条关键信息
        String raw = ShizukuBridge.sh("echo \"---T---\"; pm list packages -3 2>/dev/null;"
                + "echo \"---P---\"; ps -A -o PID,PPID,USER,RSS,NAME 2>/dev/null;"
                + "echo \"---C---\"; dumpsys cpuinfo 2>/dev/null");
        List<Row> list = new ArrayList<Row>();
        if (raw == null || raw.trim().isEmpty()) {
            return list;
        }
        java.util.Map<Integer, Double> cpu = new java.util.HashMap<Integer, Double>();
        // 真正装进 data 分区的第三方应用（pm list packages -3 是权威口径）：
        // 只按 uid 判断会把 com.android.systemui / permissioncontroller 这类系统组件误判成可结束
        java.util.Set<String> thirdPkgs = new java.util.HashSet<String>();
        boolean inP = false, inC = false, inT = false;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.equals("---P---")) {
                inP = true;
                inC = false;
                inT = false;
                continue;
            }
            if (t.equals("---C---")) {
                inP = false;
                inC = true;
                inT = false;
                continue;
            }
            if (t.equals("---T---")) {
                inP = false;
                inC = false;
                inT = true;
                continue;
            }
            if (inT) {
                if (t.startsWith("package:")) thirdPkgs.add(t.substring(8).trim());
                continue;
            }
            if (inP) {
                if (t.startsWith("PID")) continue;   // 表头
                String[] p = t.split("\\s+", 5);
                if (p.length < 4) continue;
                try {
                    Row r = new Row();
                    r.pid = Integer.parseInt(p[0]);
                    r.ppid = (int) PowerCollector.parseIntSafe(p[1], 0);
                    r.user = p[2];
                    r.rssKb = PowerCollector.parseIntSafe(p[3], 0);
                    r.name = p.length >= 5 ? p[4] : "";
                    if (r.name.isEmpty()) continue;
                    String base = r.name;
                    int colon = base.indexOf(':');
                    if (colon > 0) base = base.substring(0, colon);   // com.x:remote → com.x
                    r.thirdParty = thirdPkgs.contains(base);
                    r.self = base.equals(getPackageName());
                    list.add(r);
                } catch (Throwable ignored) {
                }
                continue;
            }
            if (inC) {
                int slash = t.indexOf('/');
                if (slash < 0) continue;
                String[] hp = t.substring(0, slash).trim().split("\\s+");
                if (hp.length < 2) continue;
                try {
                    cpu.put(Integer.parseInt(hp[1]), Double.parseDouble(hp[0].replace("%", "")));
                } catch (Throwable ignored) {
                }
            }
        }

        for (Row r : list) {
            Double c = cpu.get(r.pid);
            r.cpuPct = c == null ? 0 : c;
            if (r.self) r.note = "本 App 自己";
            else if (!r.thirdParty) {
                if (r.name.startsWith("com.sei.")) r.note = "厂商常驻（电量靠它写，别杀）";
                else if (r.user.equals("root")) r.note = "内核线程/root，不可结束";
                else r.note = "系统进程 / 预装组件，不可结束";
            }
        }

        Collections.sort(list, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int c = Double.compare(b.cpuPct, a.cpuPct);
                return c != 0 ? c : Long.compare(b.rssKb, a.rssKb);
            }
        });
        android.util.Log.i("RayNeoDiag", "proc load: chars=" + raw.length() + " thirdPkgs=" + thirdPkgs.size()
                + " rows=" + list.size());
        return list;
    }

    private void applyFilter() {
        shown = new ArrayList<Row>();
        double cpuSum = 0;
        int third = 0;
        for (Row r : rows) {
            if (r.thirdParty && !r.self) third++;
            cpuSum += r.cpuPct;
            if (!onlyThird || r.thirdParty) shown.add(r);
        }
        adapter.notifyDataSetChanged();
        listView.setSelection(0);
        tvSummary.setText(String.format(Locale.US,
                "共 %d 个进程（第三方应用 %d 个），显示 %d 行；CPU 占比合计 %.1f%%",
                rows.size(), third, shown.size(), cpuSum));
    }

    private class ProcAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            if (convertView instanceof LinearLayout) {
                card = (LinearLayout) convertView;
            } else {
                card = buildCard();
            }
            bind(card, shown.get(position));
            return card;
        }
    }

    private LinearLayout buildCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(16, 10, 12, 10);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(14);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 8;
        card.setLayoutParams(lp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setTextSize(13);
        name.setTextColor(COLOR_TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        left.addView(name);

        TextView sub = new TextView(this);
        sub.setTextSize(11);
        sub.setTextColor(COLOR_SUB);
        left.addView(sub);

        card.addView(left);

        TextView cpu = new TextView(this);
        cpu.setTextSize(13);
        cpu.setGravity(Gravity.END);
        cpu.setPadding(0, 0, 14, 0);
        card.addView(cpu);

        Button act = smallButton("结束");
        card.addView(act);

        card.setTag(new Object[]{name, sub, cpu, act});
        return card;
    }

    private void bind(LinearLayout card, final Row r) {
        Object[] h = (Object[]) card.getTag();
        TextView name = (TextView) h[0];
        TextView sub = (TextView) h[1];
        TextView cpu = (TextView) h[2];
        Button act = (Button) h[3];

        name.setText(r.name);
        sub.setText(String.format(Locale.US, "PID %d · %s · 内存 %.1f MB%s",
                r.pid, r.user, r.rssKb / 1024.0, r.note == null ? "" : (" · " + r.note)));
        cpu.setText(String.format(Locale.US, "%.1f%%", r.cpuPct));
        cpu.setTextColor(r.cpuPct >= 5 ? COLOR_WARN : (r.cpuPct >= 2 ? COLOR_MID : COLOR_SUB));

        boolean can = r.thirdParty && !r.self;
        act.setText(can ? "结束" : "系统");
        act.setEnabled(can);
        act.setOnClickListener(can ? new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmStop(r);
            }
        } : null);
    }

    private void showDetail(final Row r) {
        String msg = "进程：" + r.name + "\nPID：" + r.pid + "（父 " + r.ppid + "）\n属主：" + r.user
                + "\nCPU：" + String.format(Locale.US, "%.1f%%", r.cpuPct)
                + "\n内存：" + String.format(Locale.US, "%.1f MB", r.rssKb / 1024.0)
                + (r.note == null ? "" : ("\n说明：" + r.note));
        new AlertDialog.Builder(this)
                .setTitle(r.name)
                .setMessage(msg)
                .setPositiveButton("复制包名", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("proc", r.name));
                        Toast.makeText(ProcessActivity.this, "已复制：" + r.name, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("取消", null)
                .show();
    }

    private void confirmStop(final Row r) {
        new AlertDialog.Builder(this)
                .setTitle("结束 " + r.name + "？")
                .setMessage("将执行 am force-stop " + r.name
                        + "\n\n它会被系统回收，需要时由启动器或用户重新打开；正在播放/下载的内容会中断。")
                .setNegativeButton("取消", null)
                .setPositiveButton("结束", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        stopApp(r);
                    }
                })
                .show();
    }

    private void stopApp(final Row r) {
        Toast.makeText(this, "正在结束 " + r.name + " …", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String out = ShizukuBridge.sh("am force-stop " + r.name + " ; echo rc=$?");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ProcessActivity.this,
                                "已发出结束请求：" + r.name + "（" + (out == null ? "无返回" : out.trim()) + "）",
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                });
            }
        }).start();
    }
}

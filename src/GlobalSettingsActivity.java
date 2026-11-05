package com.rayneo.diag;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class GlobalSettingsActivity extends Activity {

    private final List<String> all = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(8, 8, 8, 8);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        EditText search = new EditText(this);
        search.setHint("搜索键名，如 battery / ntp / time");
        search.setSingleLine(true);
        bar.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button copy = new Button(this);
        copy.setText("复制全部");
        copy.setAllCaps(false);
        copy.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { copyAll(); }
        });
        bar.addView(copy);

        root.addView(bar);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, shown);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString().trim()); }
        });

        loadAll();
        filter("");
    }

    private void loadAll() {
        all.clear();
        Cursor c = null;
        try {
            c = getContentResolver().query(Uri.parse("content://settings/global"), null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int nameIdx = c.getColumnIndex("name");
                int valueIdx = c.getColumnIndex("value");
                do {
                    String k = nameIdx >= 0 ? c.getString(nameIdx) : null;
                    String v = valueIdx >= 0 ? c.getString(valueIdx) : null;
                    if (k != null) all.add(k + " = " + (v == null ? "" : v));
                } while (c.moveToNext());
            }
        } catch (Throwable t) {
            all.add("读取 settings 库失败：" + t.getClass().getSimpleName()
                    + "（多半是该 ROM 对第三方应用限制了 ContentProvider 读取）");
        } finally {
            if (c != null) c.close();
        }
        if (all.isEmpty()) {
            all.add("settings 库没有返回任何条目。");
        }
        all.add(0, "共 " + Math.max(0, all.size() - 1) + " 条 global 设置");
        android.util.Log.i("RayNeoDiag", "GlobalSettings 条目数=" + all.size());
    }

    private void filter(String q) {
        shown.clear();
        for (String s : all) {
            if (q.isEmpty() || s.toLowerCase().contains(q.toLowerCase())) shown.add(s);
        }
        adapter.notifyDataSetChanged();
    }

    private void copyAll() {
        StringBuilder sb = new StringBuilder();
        for (String s : shown) sb.append(s).append("\n");
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("global settings", sb.toString()));
        Toast.makeText(this, "已复制 " + shown.size() + " 条", Toast.LENGTH_SHORT).show();
    }
}

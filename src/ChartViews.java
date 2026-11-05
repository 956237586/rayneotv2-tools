package com.rayneo.diag;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 极简自绘图表：不引第三方库，Canvas 手画折线和横条。 */
public final class ChartViews {

    public static final int[] PALETTE = {
            0xFF3B7CFF, 0xFFFF7A45, 0xFF2BB673, 0xFFB25BFF, 0xFFE0A100,
            0xFF00A6A6, 0xFFD64545, 0xFF6B7A99
    };

    private ChartViews() {
    }

    private static float dp(View v, float v2) {
        return v.getResources().getDisplayMetrics().density * v2;
    }

    /** 多线折线图：x 轴按采样点等距，y 轴自适应（或固定 0..max）。 */
    public static class LineChart extends View {
        private final List<float[]> series = new ArrayList<float[]>();
        private final List<String> names = new ArrayList<String>();
        private final List<Integer> colors = new ArrayList<Integer>();
        private String unit = "";
        private int fixedMax = -1;
        private String empty = "暂无数据";

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        public LineChart(Context c) {
            super(c);
            init();
        }

        public LineChart(Context c, AttributeSet a) {
            super(c, a);
            init();
        }

        private void init() {
            bgPaint.setColor(Color.WHITE);
            gridPaint.setColor(0xFFE6E8EB);
            gridPaint.setStrokeWidth(dp(this, 1));
            linePaint.setStrokeWidth(dp(this, 2.2f));
            linePaint.setStyle(Paint.Style.STROKE);
            textPaint.setColor(0xFF646A73);
            textPaint.setTextSize(dp(this, 11));
            dotPaint.setStyle(Paint.Style.FILL);
        }

        public void setEmpty(String s) {
            empty = s;
        }

        public void setData(List<float[]> data, List<String> legend, List<Integer> cols,
                            String unitLabel, int fixedMaxValue) {
            series.clear();
            names.clear();
            colors.clear();
            if (data != null) series.addAll(data);
            if (legend != null) names.addAll(legend);
            if (cols != null) colors.addAll(cols);
            unit = unitLabel == null ? "" : unitLabel;
            fixedMax = fixedMaxValue;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            canvas.drawRoundRect(new RectF(0, 0, w, h), dp(this, 16), dp(this, 16), bgPaint);

            float left = dp(this, 48), top = dp(this, 30), right = dp(this, 14), bottom = dp(this, 18);
            float pw = w - left - right, ph = h - top - bottom;
            if (pw <= 0 || ph <= 0) return;

            if (series.isEmpty() || series.get(0).length == 0) {
                textPaint.setTextSize(dp(this, 13));
                canvas.drawText(empty, left, h / 2f, textPaint);
                return;
            }

            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (float[] s : series) {
                for (float v : s) {
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
            if (fixedMax > 0) {
                min = 0;
                max = fixedMax;
            } else {
                float pad = Math.max(1f, (max - min) * 0.15f);
                min -= pad;
                max += pad;
                if (min < 0) min = 0;
            }
            if (max <= min) max = min + 1;

            // 横向网格 + 刻度
            textPaint.setTextSize(dp(this, 11));
            for (int i = 0; i <= 4; i++) {
                float y = top + ph * i / 4f;
                canvas.drawLine(left, y, left + pw, y, gridPaint);
                float v = max - (max - min) * i / 4f;
                canvas.drawText(String.format(Locale.US, "%.0f", v), dp(this, 6), y + dp(this, 4), textPaint);
            }
            if (!unit.isEmpty()) {
                canvas.drawText(unit, dp(this, 6), dp(this, 16), textPaint);
            }

            int n = series.get(0).length;
            float stepX = n > 1 ? pw / (n - 1) : 0;

            for (int si = 0; si < series.size(); si++) {
                float[] s = series.get(si);
                int col = colors.size() > si ? colors.get(si) : PALETTE[si % PALETTE.length];
                linePaint.setColor(col);
                path.reset();
                for (int i = 0; i < s.length && i < n; i++) {
                    float x = left + stepX * i;
                    float y = top + ph * (1f - (s[i] - min) / (max - min));
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                canvas.drawPath(path, linePaint);

                // 终点圆点 + 数值
                if (s.length > 0) {
                    int last = Math.min(s.length, n) - 1;
                    float x = left + stepX * last;
                    float y = top + ph * (1f - (s[last] - min) / (max - min));
                    dotPaint.setColor(col);
                    canvas.drawCircle(x, y, dp(this, 3), dotPaint);
                }
            }

            // 顶部图例
            float lx = left, ly = dp(this, 16);
            textPaint.setTextSize(dp(this, 11));
            for (int i = 0; i < names.size(); i++) {
                int col = colors.size() > i ? colors.get(i) : PALETTE[i % PALETTE.length];
                dotPaint.setColor(col);
                canvas.drawCircle(lx + dp(this, 4), ly - dp(this, 3), dp(this, 4), dotPaint);
                String label = names.get(i);
                float tw = textPaint.measureText(label);
                if (lx + dp(this, 12) + tw > left + pw) break;
                canvas.drawText(label, lx + dp(this, 12), ly, textPaint);
                lx += dp(this, 18) + tw;
            }
        }
    }

    /** 横向条形图：进程排行。 */
    public static class Bars extends View {
        private final List<String> names = new ArrayList<String>();
        private final List<Double> values = new ArrayList<Double>();
        private String suffix = "%";
        private String empty = "暂无数据";

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Bars(Context c) {
            super(c);
            bgPaint.setColor(Color.WHITE);
            textPaint.setColor(0xFF1F2329);
            textPaint.setTextSize(dp(this, 12));
        }

        public void setEmpty(String s) {
            empty = s;
        }

        /** 点中某一行就回调完整名字（应用即包名，系统进程即进程名）。 */
        public interface OnRowClick {
            void onRowClick(String name, int index);
        }

        private OnRowClick rowClick;

        public void setOnRowClick(OnRowClick l) {
            rowClick = l;
            setClickable(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                int rows = names.size();
                if (rows > 0) {
                    float rowH = (getHeight() - dp(this, 14)) / rows;
                    int idx = (int) ((e.getY() - dp(this, 7)) / Math.max(1f, rowH));
                    if (idx >= 0 && idx < rows && rowClick != null) {
                        rowClick.onRowClick(names.get(idx), idx);
                    }
                }
            }
            return true;
        }

        public void setData(List<String> n, List<Double> v, String suffixLabel) {
            names.clear();
            values.clear();
            if (n != null) names.addAll(n);
            if (v != null) values.addAll(v);
            if (suffixLabel != null) suffix = suffixLabel;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            canvas.drawRoundRect(new RectF(0, 0, w, h), dp(this, 16), dp(this, 16), bgPaint);
            if (names.isEmpty()) {
                canvas.drawText(empty, dp(this, 16), h / 2f, textPaint);
                return;
            }
            int rows = names.size();
            float rowH = (h - dp(this, 14)) / rows;
            float labelW = Math.min(dp(this, 430), w * 0.40f);
            float valW = dp(this, 54);
            float barX = dp(this, 8) + labelW;
            float barMax = w - barX - valW - dp(this, 8);
            double max = 0.01;
            for (double v : values) max = Math.max(max, v);

            for (int i = 0; i < rows; i++) {
                float cy = dp(this, 7) + rowH * i + rowH / 2f;
                float th = Math.max(dp(this, 8), rowH * 0.52f);

                textPaint.setTextSize(dp(this, 12));
                textPaint.setColor(0xFF1F2329);
                String label = names.get(i);
                while (textPaint.measureText(label) > labelW - dp(this, 6) && label.length() > 4) {
                    label = label.substring(0, label.length() - 1);
                }
                if (!label.equals(names.get(i))) label = label + "…";
                canvas.drawText(label, dp(this, 8), cy + dp(this, 4), textPaint);

                barPaint.setColor(PALETTE[i % PALETTE.length]);
                float len = (float) (barMax * values.get(i) / max);
                canvas.drawRoundRect(new RectF(barX, cy - th / 2, barX + Math.max(dp(this, 2), len), cy + th / 2),
                        th / 2, th / 2, barPaint);

                textPaint.setColor(0xFF646A73);
                canvas.drawText(String.format(Locale.US, "%.1f%s", values.get(i), suffix),
                        w - valW + dp(this, 4), cy + dp(this, 4), textPaint);
            }
        }
    }
}

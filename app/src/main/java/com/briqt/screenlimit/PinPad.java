package com.briqt.screenlimit;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设置页用的 6 位密码输入：锁形图标 + 标题 + 6 个圆点 + 自绘数字键盘，满 6 位自动提交。
 *
 * <p>为什么不用 EditText + 系统键盘：密码门原先是一个系统对话框里的一条下划线输入框，
 * 弹起的是厂商安全键盘，和本应用、和拦截页的键盘都是三套样子（用户反馈"太丑、割裂"）。
 * 设置页门、设置密码、拦截页的密码面板都用这一个，样式完全一致（固定浅色语义色）。
 */
final class PinPad {
    interface Listener {
        void onPin(String pin);
    }

    final LinearLayout view;
    private final Context c;
    private final TextView title, sub, error;
    private final TextView[] dots = new TextView[6];
    private final StringBuilder pin = new StringBuilder();
    private final LinearLayout dotsRow;
    private Listener listener;
    private boolean enabled = true;

    PinPad(Context c) {
        this.c = c;
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(dp(28), dp(24), dp(28), dp(16));

        TextView lock = text("🔒", 34, R.color.text_hi);
        lock.setPadding(0, 0, 0, dp(14));
        view.addView(lock);
        title = text("", 21, R.color.text_hi);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        view.addView(title);
        sub = text("", 13.5f, R.color.text_mid);
        sub.setPadding(0, dp(6), 0, dp(26));
        view.addView(sub);

        dotsRow = new LinearLayout(c);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < 6; i++) {
            TextView d = new TextView(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(14));
            lp.leftMargin = lp.rightMargin = dp(9);
            d.setLayoutParams(lp);
            dots[i] = d;
            dotsRow.addView(d);
        }
        view.addView(dotsRow);
        error = text(" ", 13, R.color.danger);
        error.setPadding(0, dp(14), 0, dp(18));
        view.addView(error);
        view.addView(keypad());
        syncDots();
    }

    PinPad texts(String t, String s) {
        title.setText(t);
        sub.setText(s);
        return this;
    }

    PinPad listener(Listener l) {
        listener = l;
        return this;
    }

    /** 在副标题和圆点之间插一块（拦截页用来放「解锁时长」选择）。 */
    PinPad extra(View v) {
        view.addView(v, view.indexOfChild(dotsRow));
        return this;
    }

    /** 底部一个文字按钮（退出 / 取消）。 */
    PinPad footer(String label, View.OnClickListener l) {
        TextView f = text(label, 15, R.color.text_mid);
        f.setPadding(dp(24), dp(16), dp(24), dp(8));
        f.setOnClickListener(l);
        view.addView(f);
        return this;
    }

    void error(String msg) {
        error.setText(msg == null || msg.isEmpty() ? " " : msg);
        if (msg != null && !msg.isEmpty()) shake();
    }

    void clear() {
        pin.setLength(0);
        syncDots();
    }

    /** 锁定期间键盘不可用，按键变灰。 */
    void setEnabled(boolean on) {
        enabled = on;
        view.setAlpha(1f);
        dotsRow.setAlpha(on ? 1f : 0.4f);
    }

    private void onKey(String k) {
        if (!enabled) return;
        if ("⌫".equals(k)) {
            if (pin.length() > 0) pin.setLength(pin.length() - 1);
        } else if ("清空".equals(k)) {
            pin.setLength(0);
        } else if (pin.length() < 6) {
            pin.append(k);
        }
        error.setText(" ");
        syncDots();
        if (pin.length() == 6 && listener != null) {
            String p = pin.toString();
            // 让第 6 个点先亮一下再提交，不然看起来像按键没反应
            view.postDelayed(() -> {
                pin.setLength(0);
                syncDots();
                listener.onPin(p);
            }, 120);
        }
    }

    private void syncDots() {
        for (int i = 0; i < dots.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            boolean filled = i < pin.length();
            g.setColor(filled ? c.getColor(R.color.primary) : Color.TRANSPARENT);
            if (!filled) g.setStroke(dp(1.5f), c.getColor(R.color.text_low));
            dots[i].setBackground(g);
        }
    }

    private void shake() {
        dotsRow.animate().cancel();
        dotsRow.setTranslationX(0);
        float d = dp(10);
        dotsRow.animate().translationX(d).setDuration(50).withEndAction(() ->
                dotsRow.animate().translationX(-d).setDuration(70).withEndAction(() ->
                        dotsRow.animate().translationX(d / 2).setDuration(60).withEndAction(() ->
                                dotsRow.animate().translationX(0).setDuration(50))));
    }

    private View keypad() {
        String[][] grid = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"清空", "0", "⌫"}};
        LinearLayout pad = new LinearLayout(c);
        pad.setOrientation(LinearLayout.VERTICAL);
        for (String[] r : grid) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (final String k : r) {
                Button b = new Button(c);
                b.setAllCaps(false);
                b.setText(k);
                boolean fn = k.length() > 1 || "⌫".equals(k);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, fn ? 15 : 24);
                b.setTextColor(c.getColor(fn ? R.color.text_mid : R.color.text_hi));
                b.setStateListAnimator(null);
                b.setMinHeight(dp(60));
                b.setBackground(keyBg(fn));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(60), 1f);
                lp.setMargins(dp(6), dp(6), dp(6), dp(6));
                row.addView(b, lp);
                b.setOnClickListener(v -> onKey(k));
            }
            pad.addView(row);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT);
        pad.setLayoutParams(lp);
        return pad;
    }

    private android.graphics.drawable.Drawable keyBg(boolean fn) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(c.getColor(fn ? R.color.surface : R.color.surface_container));
        if (!fn) g.setStroke(dp(1), c.getColor(R.color.outline_variant));
        android.content.res.ColorStateList ripple =
                android.content.res.ColorStateList.valueOf(c.getColor(R.color.primary_container));
        return new android.graphics.drawable.RippleDrawable(ripple, g, null);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(c.getColor(color));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }
}

package com.alharam.answer;
import com.alharam.answer.R;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * Numeric/text stepper used by the main controls.
 * Short tap changes one step; holding an arrow repeats with progressive acceleration.
 */
public class StepperView extends LinearLayout {
    private final TextView valueView;
    private final TextView upView;
    private final TextView downView;
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());

    private int min = 0, max = 0, value = -1;
    private boolean blankAllowed = false;
    private String[] textValues = null;
    private int textIndex = -1;

    private TextView heldArrow;
    private boolean longPressActive = false;
    private int repeatCount = 0;
    private long downAt = 0L;

    private final Runnable repeatRunnable = new Runnable() {
        @Override public void run() {
            if (!longPressActive || heldArrow == null) return;
            if (heldArrow == upView) increment();
            else decrement();
            repeatCount++;
            repeatHandler.postDelayed(this, nextRepeatDelay());
        }
    };

    public StepperView(Context context) { this(context, null); }

    public StepperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setBackgroundResource(com.alharam.answer.R.drawable.bg_input);
        int pad = dp(5);
        setPadding(pad, dp(3), pad, dp(3));
        setMinimumHeight(dp(52));
        setClipToPadding(false);

        LinearLayout arrows = new LinearLayout(context);
        arrows.setOrientation(VERTICAL);
        arrows.setGravity(Gravity.CENTER);
        arrows.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        LayoutParams ap = new LayoutParams(dp(58), LayoutParams.MATCH_PARENT);
        arrows.setLayoutParams(ap);

        upView = arrow("▲");
        downView = arrow("▼");
        arrows.addView(upView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        arrows.addView(downView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        valueView = new TextView(context);
        valueView.setGravity(Gravity.CENTER);
        valueView.setTextColor(Color.parseColor("#172033"));
        valueView.setTextSize(15);
        valueView.setTypeface(null, android.graphics.Typeface.BOLD);
        valueView.setSingleLine(true);
        LayoutParams vp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1);

        addView(arrows);
        addView(valueView, vp);

        upView.setOnTouchListener(new ArrowTouchListener());
        downView.setOnTouchListener(new ArrowTouchListener());
        refresh();
    }

    private TextView arrow(String t) {
        TextView v = new TextView(getContext());
        v.setText(t);
        // Approximately double the old arrow size for easier tapping and visibility.
        v.setTextSize(22);
        v.setTextColor(Color.parseColor("#1769D1"));
        v.setGravity(Gravity.CENTER);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setClickable(true);
        v.setFocusable(true);
        v.setMinHeight(dp(24));
        return v;
    }

    private final class ArrowTouchListener implements OnTouchListener {
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    heldArrow = (TextView) v;
                    downAt = System.currentTimeMillis();
                    repeatCount = 0;
                    longPressActive = true;
                    v.setPressed(true);
                    // First repeat is deliberately delayed so a normal tap remains one step.
                    repeatHandler.removeCallbacks(repeatRunnable);
                    repeatHandler.postDelayed(repeatRunnable, 380);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - v.getWidth() / 2f) > v.getWidth()
                            || Math.abs(e.getY() - v.getHeight() / 2f) > v.getHeight() * 1.5f) {
                        cancelRepeat(v);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean wasLong = repeatCount > 0 || System.currentTimeMillis() - downAt >= 380;
                    cancelRepeat(v);
                    if (!wasLong) {
                        if (v == upView) increment();
                        else decrement();
                    }
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelRepeat(v);
                    return true;
                default:
                    return true;
            }
        }
    }

    private void cancelRepeat(View v) {
        repeatHandler.removeCallbacks(repeatRunnable);
        longPressActive = false;
        if (v != null) v.setPressed(false);
        heldArrow = null;
    }

    /** Progressively shortens the repeat interval as the finger is held longer. */
    private long nextRepeatDelay() {
        if (repeatCount < 3) return 300;
        if (repeatCount < 7) return 220;
        if (repeatCount < 12) return 160;
        if (repeatCount < 20) return 110;
        if (repeatCount < 32) return 75;
        return 50;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    public void configure(int min, int max, int initial, boolean blankAllowed, String suffix) {
        this.min = min;
        this.max = max;
        this.blankAllowed = blankAllowed;
        this.textValues = null;
        if (blankAllowed && initial == 0) this.value = -1;
        else this.value = Math.max(min, Math.min(max, initial));
        refresh();
    }

    public void configureText(String[] values, int initialIndex, boolean blankAllowed) {
        this.textValues = values;
        this.blankAllowed = blankAllowed;
        this.textIndex = Math.max(-1, Math.min(values.length - 1, initialIndex));
        refresh();
    }

    private void increment() {
        if (textValues != null) {
            if (textIndex < textValues.length - 1) textIndex++;
        } else if (value < max) {
            value = value < min ? min : value + 1;
        }
        refresh();
    }

    private void decrement() {
        if (textValues != null) {
            if (textIndex > -1) textIndex--;
        } else if (value < 0) {
            // Keep an empty field empty.
        } else if (value > min) {
            value--;
        } else if (blankAllowed) {
            value = -1;
        }
        refresh();
    }

    private void refresh() {
        if (textValues != null) {
            valueView.setText(textIndex < 0 ? "" : textValues[textIndex]);
        } else {
            valueView.setText(value < 0 ? "" : String.format(Locale.getDefault(), "%02d", value));
        }
    }

    public int getNumericValue() { return textValues == null ? value : -1; }
    public int getTextIndex() { return textValues == null ? -1 : textIndex; }
    public String getTextValue() { return textValues != null && textIndex >= 0 ? textValues[textIndex] : ""; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}

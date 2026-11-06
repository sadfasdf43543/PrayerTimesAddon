package com.majnioui.tv.prayer;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Today's prayer times for one city from Aladhan (free, no account or key; only the coordinates and
 * the date are sent), with the calculation method in res/values/config.xml. The next prayer is highlighted
 * with a countdown.
 */
public class MainActivity extends Activity {
    private static final String TAG = "PrayerTimes";
    private static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final int[] NAME_RES = {
            R.string.prayer_fajr,
            R.string.prayer_sunrise,
            R.string.prayer_dhuhr,
            R.string.prayer_asr,
            R.string.prayer_maghrib,
            R.string.prayer_isha
    };
    private static final String[] ARABIC = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final int[] ICONS = {R.drawable.ic_fajr, R.drawable.ic_sunrise, R.drawable.ic_dhuhr,
            R.drawable.ic_asr, R.drawable.ic_maghrib, R.drawable.ic_isha};
    private static final long MINUTE_MS = 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Typeface font;
    private int accent;
    private TextView dates, next, status, occasion;
    private LinearLayout rows;
    /** Today's times as minutes after midnight, in NAMES order; null until loaded. */
    private int[] minutes;
    private String[] times;
    /** The calculation method's name as Aladhan gives it, for the status line. */
    private String methodName = "";
    /** Today's Hijri month (1-12) and day, from the last successful load; -1 until then. */
    private int hijriMonth = -1, hijriDay = -1;
    /** The day the times are for (yyyy-MM-dd), to reload after midnight. */
    private String loadedFor;
    private boolean loading;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!today().equals(loadedFor)) load();
            else render();
            main.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {(accent & 0x00FFFFFF) | 0x55000000, 0xFF050607, 0xFF050607}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(80), dp(48), dp(80), dp(40));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView place = text(18, 400, 0xB3FFFFFF);
        place.setLetterSpacing(0.25f);
        place.setText(getString(R.string.place).toUpperCase(Locale.ROOT));
        col.addView(place);

        next = text(52, 300, 0xFFFFFFFF);
        next.setPadding(0, dp(10), 0, dp(4));
        col.addView(next);
        dates = text(20, 400, 0xB3FFFFFF);
        col.addView(dates);
        occasion = text(16, 500, (accent & 0x00FFFFFF) | 0xFF000000);
        occasion.setPadding(0, dp(4), 0, 0);
        col.addView(occasion);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        rlp.setMargins(0, dp(36), 0, 0);
        col.addView(rows, rlp);

        status = text(16, 400, 0x80FFFFFF);
        status.setPadding(0, dp(18), 0, 0);
        status.setText(getString(R.string.loading));
        col.addView(status);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        main.postDelayed(tick, MINUTE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    private void load() {
        if (loading) return;
        loading = true;
        final String day = today();
        String url = "https://api.aladhan.com/v1/timings/"
                + new SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).format(new Date())
                + "?latitude=" + getString(R.string.latitude) + "&longitude=" + getString(R.string.longitude)
                + "&method=" + getString(R.string.method) + "&school=" + getString(R.string.school);
        new Thread(() -> {
            JSONObject json = null;
            try {
                json = new JSONObject(fetch(url));
            } catch (Exception e) {
                Log.w(TAG, "fetch: " + e);
            }
            final JSONObject result = json;
            main.post(() -> {
                loading = false;
                if (result == null) {
                    status.setText(getString(R.string.offline));
                    return;
                }
                try {
                    parse(result.getJSONObject("data"));
                    loadedFor = day;
                    if (getResources().getBoolean(R.bool.reminders)) {
                        PrayerScheduler.saveTimingsAndSchedule(MainActivity.this, day, NAMES, times, minutes, getString(R.string.place));
                    } else {
                        PrayerScheduler.cancel(MainActivity.this);
                    }
                    render();
                } catch (Exception e) {
                    Log.w(TAG, "parse: " + e);
                    status.setText(getString(R.string.unexpected_error));
                }
            });
        }).start();
    }

    private void parse(JSONObject data) throws Exception {
        JSONObject t = data.getJSONObject("timings");
        int[] m = new int[NAMES.length];
        String[] s = new String[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            String v = t.getString(NAMES[i]).trim();
            int sp = v.indexOf(' ');
            if (sp > 0) v = v.substring(0, sp);   // "05:12 (+01)" style suffixes
            s[i] = v;
            m[i] = Integer.parseInt(v.substring(0, 2)) * 60 + Integer.parseInt(v.substring(3, 5));
        }
        minutes = m;
        times = s;
        JSONObject meta = data.optJSONObject("meta");
        JSONObject method = meta != null ? meta.optJSONObject("method") : null;
        methodName = method != null ? method.optString("name", "") : "";
        JSONObject date = data.getJSONObject("date");
        JSONObject hijri = date.getJSONObject("hijri");
        dates.setText(new SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH).format(new Date()) + "  ·  "
                + hijri.getString("day") + " " + hijri.getJSONObject("month").getString("en") + " "
                + hijri.getString("year") + " AH");
        hijriDay = Integer.parseInt(hijri.getString("day"));
        hijriMonth = hijri.getJSONObject("month").getInt("number");
    }

    private void render() {
        if (minutes == null) return;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int nextIdx = -1;
        for (int i = 0; i < NAMES.length; i++) {
            if (i == 1) continue;   // sunrise is shown but is not a prayer
            if (minutes[i] > now) { nextIdx = i; break; }
        }
        if (nextIdx >= 0) {
            int left = minutes[nextIdx] - now;
            String timeStr = (left >= 60 ? left / 60 + " h " : "") + left % 60 + " min";
            next.setText(getString(R.string.prayer_in, getString(NAME_RES[nextIdx]), timeStr));
        } else {
            next.setText(getString(R.string.prayer_tomorrow, getString(NAME_RES[0])));
        }

        occasion.setText(occasionText());
        rows.removeAllViews();
        for (int i = 0; i < NAMES.length; i++) {
            boolean hl = i == nextIdx;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(6), dp(20), dp(6), dp(20));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(hl ? accent : 0x14FFFFFF);
            card.setBackground(bg);
            int dim = i == 1 ? 0x80FFFFFF : 0xFFFFFFFF;
            int fg = hl ? 0xFFFFFFFF : dim;
            ImageView icon = new ImageView(this);
            icon.setImageResource(ICONS[i]);
            icon.setImageTintList(ColorStateList.valueOf(hl ? 0xFFFFFFFF : (i == 1 ? 0x80FFFFFF : accent)));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
            ilp.bottomMargin = dp(22);
            card.addView(icon, ilp);
            TextView ar = centered(text(26, 400, fg));
            ar.setText(ARABIC[i]);
            card.addView(ar);
            TextView name = centered(text(20, 500, hl ? 0xE6FFFFFF : (i == 1 ? 0x80FFFFFF : 0xB3FFFFFF)));
            name.setText(getString(NAME_RES[i]));
            name.setPadding(0, dp(8), 0, dp(20));
            card.addView(name);
            TextView time = centered(text(42, hl ? 500 : 300, fg));
            time.setText(to12h(times[i]));
            // Shrink rather than cut "05:10" when the card is narrow.
            time.setMaxLines(1);
            time.setAutoSizeTextTypeUniformWithConfiguration(20, 42, 1, TypedValue.COMPLEX_UNIT_SP);
            card.addView(time, new LinearLayout.LayoutParams(-1, dp(52)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(i == 0 ? 0 : dp(8), 0, i == NAMES.length - 1 ? 0 : dp(8), 0);
            rows.addView(card, lp);
        }
        boolean reminders = getResources().getBoolean(R.bool.reminders);
        String updated = getString(R.string.updated_at, to12h(new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date())));
        String statusStr = "Aladhan" + (methodName.isEmpty() ? "" : " · " + methodName) + " · " + updated;
        if (reminders) {
            statusStr += " · " + getString(R.string.reminders_active);
        }
        status.setText(statusStr);
    }

    /** "05:12" -> "5:12 AM". Falls back to the original string if it is not HH:mm. */
    private static String to12h(String hhmm) {
        try {
            int hh = Integer.parseInt(hhmm.substring(0, 2));
            int mm = Integer.parseInt(hhmm.substring(3, 5));
            String ampm = hh < 12 ? "AM" : "PM";
            int h12 = hh % 12;
            if (h12 == 0) h12 = 12;
            return String.format(Locale.ROOT, "%d:%02d %s", h12, mm, ampm);
        } catch (Exception e) {
            return hhmm;
        }
    }

    /** Tabular Hijri calendar approximation (odd months 30 days, even months 29): within a day or two
     *  of the moon-sighted date used by local authorities, good enough for a countdown. */
    private static int daysInHijriMonth(int m) {
        return (m % 2 == 1) ? 30 : 29;
    }

    private static int hijriDaysUntil(int curMonth, int curDay, int targetMonth, int targetDay) {
        if (curMonth == targetMonth && targetDay >= curDay) return targetDay - curDay;
        int days = daysInHijriMonth(curMonth) - curDay;
        int m = curMonth;
        for (int i = 0; i < 24; i++) {
            m = m % 12 + 1;
            if (m == targetMonth) return days + targetDay;
            days += daysInHijriMonth(m);
        }
        return -1;
    }

    /** The nearest of Ramadan, Eid al-Fitr and Eid al-Adha, or a greeting during Ramadan itself. */
    private String occasionText() {
        if (hijriMonth < 1) return "";
        if (hijriMonth == 9) return getString(R.string.ramadan_mubarak);
        int toRamadan = hijriDaysUntil(hijriMonth, hijriDay, 9, 1);
        int toFitr = hijriDaysUntil(hijriMonth, hijriDay, 10, 1);
        int toAdha = hijriDaysUntil(hijriMonth, hijriDay, 12, 10);
        if (toRamadan <= toFitr && toRamadan <= toAdha) return getString(R.string.days_to_ramadan, toRamadan);
        if (toFitr <= toAdha) return getString(R.string.days_to_eid_fitr, toFitr);
        return getString(R.string.days_to_eid_adha, toAdha);
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Centred on its own line, whatever the script direction. */
    private static TextView centered(TextView t) {
        t.setGravity(Gravity.CENTER);
        t.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        return t;
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : font);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

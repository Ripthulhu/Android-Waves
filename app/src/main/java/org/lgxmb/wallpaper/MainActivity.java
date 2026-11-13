// SPDX-License-Identifier: GPL-3.0-or-later
package org.lgxmb.wallpaper;

import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

public final class MainActivity extends AppCompatActivity {
    private SharedPreferences preferences;
    private Map<String, ?> displayedPreferences;
    private NestedScrollView scroll;

    @Override public void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(light);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(light);
        preferences = WallpaperSettings.preferences(this);
        showSettings();
    }

    @Override protected void onResume() {
        super.onResume();
        // The system preview can open another settings screen above this one.
        if (!preferences.getAll().equals(displayedPreferences)) {
            int position = scroll.getScrollY();
            showSettings();
            scroll.post(() -> scroll.scrollTo(0, position));
        }
    }

    private void showSettings() {
        displayedPreferences = preferences.getAll();
        WallpaperSettings settings = WallpaperSettings.read(preferences);
        LinearLayout root = column();
        root.setBackgroundColor(colour(com.google.android.material.R.attr.colorSurface));
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.app_name);
        toolbar.setTitleTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        root.addView(toolbar, fullWidth());
        LinearLayout content = new LinearLayout(this) {
            @Override protected void onMeasure(int width, int height) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(Math.min(dp(640), MeasureSpec.getSize(width)),
                        MeasureSpec.getMode(width)), height);
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(24));
        MaterialButton preview = new MaterialButton(this);
        preview.setText(R.string.preview);
        preview.setMinHeight(dp(56));
        preview.setOnClickListener(view -> preview());
        content.addView(preview, fullWidth());

        LinearLayout appearance = section(content, R.string.appearance);
        colourSettings(appearance, settings);
        selector(appearance, R.string.time_of_day, R.string.time_help, WallpaperSettings.TIME_MODE,
                settings.timeMode, R.array.time_modes, new int[]{0, 1, 2});
        slider(appearance, R.string.brightness, 0, WallpaperSettings.BRIGHTNESS, settings.brightnessPercent, 25, 100);
        slider(appearance, R.string.wave_position, R.string.wave_position_help,
                WallpaperSettings.WAVE_POSITION, settings.wavePositionPercent, 20, 80);

        LinearLayout screensaver = section(content, R.string.screensaver);
        LinearLayout screensaverIntro = setting(screensaver);
        helper(screensaverIntro, R.string.screensaver_help);
        MaterialButton screensaverSettings = new MaterialButton(this);
        screensaverSettings.setText(R.string.screensaver_settings);
        screensaverSettings.setMinHeight(dp(48));
        screensaverSettings.setOnClickListener(view -> {
            try {
                // Keep Settings out of this task so its Customise button can return here.
                startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException unavailable) {
                Snackbar.make(scroll, R.string.screensaver_unavailable, Snackbar.LENGTH_LONG).show();
            }
        });
        screensaverIntro.addView(screensaverSettings, fullWidth());
        slider(screensaver, R.string.screensaver_background, 0,
                WallpaperSettings.SCREENSAVER_BACKGROUND, settings.screensaverBackgroundPercent, 0, 100);
        slider(screensaver, R.string.screensaver_waves, 0,
                WallpaperSettings.SCREENSAVER_WAVE, settings.screensaverWavePercent, 0, 100);
        slider(screensaver, R.string.screensaver_sparkles, R.string.screensaver_sparkles_help,
                WallpaperSettings.SCREENSAVER_SPARKLE, settings.screensaverSparklePercent, 0, 100);

        LinearLayout motion = section(content, R.string.motion);
        toggle(motion, R.string.pan_enabled, R.string.pan_help, WallpaperSettings.PAN_ENABLED, settings.panEnabled);
        slider(motion, R.string.pan_distance, R.string.pan_distance_help,
                WallpaperSettings.PAN_DISTANCE, settings.panDistancePercent, 0, 100);
        slider(motion, R.string.swipe_motion, R.string.swipe_help,
                WallpaperSettings.SCROLL_STRENGTH, settings.scrollStrengthPercent, 0, 100);
        toggle(motion, R.string.navigation_particles, R.string.navigation_particles_help,
                WallpaperSettings.NAVIGATION_PARTICLES, settings.navigationParticles);
        toggle(motion, R.string.phone_motion, R.string.phone_motion_help,
                WallpaperSettings.PHONE_MOTION, settings.phoneMotion);
        slider(motion, R.string.motion_strength, 0,
                WallpaperSettings.MOTION_STRENGTH, settings.motionStrengthPercent, 0, 100);
        slider(motion, R.string.wave_speed, 0, WallpaperSettings.WAVE_SPEED, settings.waveSpeedPercent, 25, 200);

        LinearLayout rendering = section(content, R.string.rendering);
        selector(rendering, R.string.frame_rate, R.string.frame_rate_help,
                WallpaperSettings.FPS, settings.fps, R.array.frame_rates, new int[]{30, 60});
        selector(rendering, R.string.render_resolution, R.string.resolution_help,
                WallpaperSettings.RESOLUTION, settings.resolutionPercent, R.array.resolutions, new int[]{50, 75, 100});
        selector(rendering, R.string.wave_mesh, R.string.mesh_help,
                WallpaperSettings.MESH, settings.mesh, R.array.mesh_sizes, new int[]{64, 128});
        selector(rendering, R.string.sparkles, R.string.sparkles_help,
                WallpaperSettings.PARTICLES, settings.particleCount, R.array.particle_counts, new int[]{0, 500, 1000, 2000});
        toggle(rendering, R.string.fxaa, R.string.fxaa_help, WallpaperSettings.FXAA, settings.fxaa);
        FrameLayout holder = new FrameLayout(this);
        holder.addView(content, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll = new NestedScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(holder);
        scroll.setId(R.id.settings_scroll);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private int colour(int attribute) {
        return MaterialColors.getColor(this, attribute, MainActivity.class.getSimpleName());
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(LinearLayout parent, int resource, int appearance, int bottom) {
        TextView view = new MaterialTextView(this);
        view.setTextAppearance(appearance);
        view.setTextColor(colour(com.google.android.material.R.attr.colorOnSurface));
        if (resource != 0) view.setText(resource);
        view.setPadding(0, 0, 0, dp(bottom));
        parent.addView(view, fullWidth());
        return view;
    }

    private LinearLayout section(LinearLayout parent, int title) {
        TextView heading = text(parent, title, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, 12);
        heading.setPadding(dp(4), dp(28), dp(4), dp(12));
        heading.setTextColor(colour(androidx.appcompat.R.attr.colorPrimary));
        ViewCompat.setAccessibilityHeading(heading, true);
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(colour(com.google.android.material.R.attr.colorSurfaceContainerLow));
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setRadius(dp(24));
        LinearLayout group = column();
        group.setPadding(0, dp(4), 0, dp(4));
        card.addView(group);
        parent.addView(card, fullWidth());
        return group;
    }

    private LinearLayout setting(LinearLayout parent) {
        LinearLayout row = column();
        row.setPadding(dp(20), dp(16), dp(20), dp(16));
        parent.addView(row, fullWidth());
        return row;
    }

    private void helper(LinearLayout parent, int resource) {
        if (resource == 0) return;
        TextView help = text(parent, resource, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 0);
        help.setPadding(0, dp(6), 0, 0);
        help.setTextColor(colour(com.google.android.material.R.attr.colorOnSurfaceVariant));
    }

    private void colourSettings(LinearLayout parent, WallpaperSettings settings) {
        boolean monthly = settings.colourMonth >= 1 && settings.colourMonth <= 12;
        int[] month = {settings.lastMonth};
        LinearLayout options = column();
        LinearLayout monthRow = selector(options, R.string.month, 0, month[0], R.array.months,
                new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, value -> {
                    month[0] = value;
                    preferences.edit().putInt(WallpaperSettings.COLOUR_MONTH, value)
                            .putInt(WallpaperSettings.LAST_MONTH, value).apply();
                });
        LinearLayout custom = column();
        colourSwatch(custom, R.string.lower_colour, WallpaperSettings.CUSTOM_PRIMARY, settings.customPrimary);
        colourSwatch(custom, R.string.upper_colour, WallpaperSettings.CUSTOM_SECONDARY, settings.customSecondary);
        colourSwatch(custom, R.string.glow_colour, WallpaperSettings.CUSTOM_TERTIARY, settings.customTertiary);
        options.addView(custom, fullWidth());
        selector(parent, R.string.colour_mode, 0, monthly ? -1 : settings.colourMonth,
                R.array.colour_modes, new int[]{0, WallpaperSettings.COLOUR_SYSTEM, -1, WallpaperSettings.COLOUR_CUSTOM},
                mode -> {
                    preferences.edit().putInt(WallpaperSettings.COLOUR_MONTH, mode == -1 ? month[0] : mode)
                            .putInt(WallpaperSettings.LAST_MONTH, month[0]).apply();
                    monthRow.setVisibility(mode == -1 ? View.VISIBLE : View.GONE);
                    custom.setVisibility(mode == WallpaperSettings.COLOUR_CUSTOM ? View.VISIBLE : View.GONE);
                });
        parent.addView(options, fullWidth());
        monthRow.setVisibility(monthly ? View.VISIBLE : View.GONE);
        custom.setVisibility(settings.colourMonth == WallpaperSettings.COLOUR_CUSTOM ? View.VISIBLE : View.GONE);
    }

    private LinearLayout selector(LinearLayout parent, int title, int help, String key, int selected, int labels, int[] values) {
        return selector(parent, title, help, selected, labels, values,
                value -> preferences.edit().putInt(key, value).apply());
    }

    private LinearLayout selector(LinearLayout parent, int title, int help, int selected, int labels,
                                  int[] values, IntConsumer onSelected) {
        LinearLayout row = setting(parent);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = column();
        text(words, title, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, 4);
        TextView valueText = text(words, 0, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, 0);
        valueText.setTextColor(colour(androidx.appcompat.R.attr.colorPrimary));
        line.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_expand_more);
        arrow.setColorFilter(colour(com.google.android.material.R.attr.colorOnSurfaceVariant));
        line.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
        row.addView(line, fullWidth());
        helper(row, help);
        String[] options = getResources().getStringArray(labels);
        int[] current = {0};
        for (int i = 0; i < values.length; i++) if (values[i] == selected) current[0] = i;
        valueText.setText(options[current[0]]);
        describeSelector(row, title, help, options[current[0]]);
        makeButton(row);
        row.setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, current[0], (dialog, position) -> {
                    current[0] = position;
                    onSelected.accept(values[position]);
                    valueText.setText(options[position]);
                    describeSelector(row, title, help, options[position]);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null).show());
        return row;
    }

    private void makeButton(LinearLayout row) {
        row.setFocusable(true);
        row.setMinimumHeight(dp(48));
        row.setDescendantFocusability(LinearLayout.FOCUS_BLOCK_DESCENDANTS);
        for (int i = 0; i < row.getChildCount(); i++)
            row.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName("android.widget.Button");
            }
        });
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
    }

    private void describeSelector(View row, int title, int help, String value) {
        row.setContentDescription(getString(title) + ", " + value + (help == 0 ? "" : ". " + getString(help)));
    }

    private void colourSwatch(LinearLayout parent, int title, String key, int selected) {
        LinearLayout row = setting(parent);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable swatch = colourShape(selected);
        View sample = new View(this);
        sample.setBackground(swatch);
        LinearLayout.LayoutParams sampleSize = new LinearLayout.LayoutParams(dp(40), dp(40));
        sampleSize.setMarginEnd(dp(16));
        row.addView(sample, sampleSize);
        LinearLayout words = column();
        text(words, title, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, 4);
        TextView value = text(words, 0, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, 0);
        value.setTextColor(colour(com.google.android.material.R.attr.colorOnSurfaceVariant));
        row.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        int[] current = {selected};
        value.setText(hexColour(selected));
        describeSelector(row, title, 0, hexColour(selected));
        makeButton(row);
        row.setOnClickListener(view -> colourPicker(title, current[0], colour -> {
            current[0] = colour;
            preferences.edit().putInt(key, colour).apply();
            swatch.setColor(colour);
            value.setText(hexColour(colour));
            describeSelector(row, title, 0, hexColour(colour));
        }));
    }

    private GradientDrawable colourShape(int value) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(value);
        shape.setCornerRadius(dp(12));
        shape.setStroke(dp(1), colour(com.google.android.material.R.attr.colorOutline));
        return shape;
    }

    private static String hexColour(int colour) {
        return String.format(Locale.ROOT, "#%06X", colour & 0x00ffffff);
    }

    private void colourPicker(int title, int selected, IntConsumer onSelected) {
        LinearLayout content = column();
        content.setPadding(dp(24), dp(8), dp(24), 0);
        GradientDrawable preview = colourShape(selected);
        View sample = new View(this);
        sample.setBackground(preview);
        sample.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams sampleSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        sampleSize.bottomMargin = dp(16);
        content.addView(sample, sampleSize);
        TextInputLayout hexField = new TextInputLayout(this);
        hexField.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        hexField.setHint(R.string.hex_colour);
        TextInputEditText hex = new TextInputEditText(hexField.getContext());
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setText(hexColour(selected));
        hexField.addView(hex, fullWidth());
        content.addView(hexField, fullWidth());
        int[] current = {selected};
        boolean[] updating = {false};
        Slider[] channels = new Slider[3];
        int[] names = {R.string.red, R.string.green, R.string.blue};
        for (int i = 0; i < channels.length; i++) {
            final int channel = i, shift = (2 - i) * 8;
            TextView label = text(content, 0, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, 0);
            label.setPadding(0, dp(16), 0, 0);
            Slider slider = new Slider(this);
            channels[i] = slider;
            slider.setId(View.generateViewId());
            slider.setValueFrom(0);
            slider.setValueTo(255);
            slider.setStepSize(1);
            slider.setTickVisible(false);
            slider.setMinimumHeight(dp(48));
            slider.setContentDescription(getString(names[i]));
            slider.setValue((selected >> shift) & 255);
            label.setLabelFor(slider.getId());
            label.setText(getString(R.string.channel_value, getString(names[i]), Math.round(slider.getValue())));
            slider.addOnChangeListener((view, value, fromUser) -> {
                label.setText(getString(R.string.channel_value, getString(names[channel]), Math.round(value)));
                if (updating[0] || !fromUser) return;
                current[0] = (current[0] & ~(255 << shift)) | (Math.round(value) << shift);
                preview.setColor(current[0]);
                updating[0] = true;
                hex.setText(hexColour(current[0]));
                hexField.setError(null);
                updating[0] = false;
            });
            content.addView(slider, fullWidth());
        }
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (updating[0]) return;
                hexField.setError(null);
                String entered = value.toString().trim();
                if (!entered.matches("#?[0-9a-fA-F]{6}")) return;
                current[0] = Color.parseColor(entered.startsWith("#") ? entered : "#" + entered);
                preview.setColor(current[0]);
                updating[0] = true;
                for (int i = 0; i < channels.length; i++) channels[i].setValue((current[0] >> ((2 - i) * 8)) & 255);
                updating[0] = false;
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        NestedScrollView viewport = new NestedScrollView(this) {
            private final Rect available = new Rect();

            @Override protected void onMeasure(int width, int height) {
                // Floating dialogs can keep measuring custom content at its pre-keyboard height.
                getWindowVisibleDisplayFrame(available);
                View titlePanel = getRootView().findViewById(androidx.appcompat.R.id.topPanel);
                View buttons = getRootView().findViewById(androidx.appcompat.R.id.buttonPanel);
                if (available.height() > 0 && titlePanel != null && buttons != null) {
                    int chrome = titlePanel.getMeasuredHeight() + buttons.getMeasuredHeight();
                    for (android.view.ViewParent parent = getParent(); parent instanceof View; parent = parent.getParent()) {
                        View ancestor = (View) parent;
                        chrome += ancestor.getPaddingTop() + ancestor.getPaddingBottom();
                    }
                    int limit = Math.max(0, available.height() - chrome);
                    if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED)
                        limit = Math.min(limit, MeasureSpec.getSize(height));
                    height = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
                }
                super.onMeasure(width, height);
            }
        };
        viewport.addView(content);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title).setView(viewport)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.apply, null).create();
        // Reserve the keyboard area so the dialog scrolls its fields, not its action buttons.
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (!hex.getText().toString().trim().matches("#?[0-9a-fA-F]{6}")) {
                        hexField.setError(getString(R.string.hex_colour_error));
                        hex.requestFocus();
                        return;
                    }
                    onSelected.accept(current[0]);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void slider(LinearLayout parent, int title, int help, String key, int selected, int minimum, int maximum) {
        LinearLayout row = setting(parent);
        TextView label = text(row, 0, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, 0);
        Slider slider = new Slider(this);
        slider.setId(View.generateViewId());
        slider.setValueFrom(minimum);
        slider.setValueTo(maximum);
        slider.setStepSize(1);
        slider.setValue(selected);
        slider.setTickVisible(false);
        slider.setContentDescription(getString(title));
        slider.setLabelFormatter(value -> getString(R.string.percent_value, Math.round(value)));
        slider.setMinimumHeight(dp(48));
        label.setLabelFor(slider.getId());
        label.setText(getString(R.string.percent_label, getString(title), selected));
        slider.addOnChangeListener((view, value, fromUser) -> {
            int percent = Math.round(value);
            label.setText(getString(R.string.percent_label, getString(title), percent));
            if (fromUser) preferences.edit().putInt(key, percent).apply();
        });
        row.addView(slider, fullWidth());
        helper(row, help);
    }

    private void toggle(LinearLayout parent, int title, int help, String key, boolean selected) {
        LinearLayout row = setting(parent);
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        toggle.setText(title);
        toggle.setMinHeight(dp(48));
        toggle.setSwitchPadding(dp(16));
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setChecked(selected);
        toggle.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean(key, checked).apply());
        row.addView(toggle, fullWidth());
        helper(row, help);
    }

    private void preview() {
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(this, WaveWallpaperService.class));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException unavailable) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (ActivityNotFoundException noPicker) {
                Snackbar.make(scroll, R.string.picker_unavailable, Snackbar.LENGTH_LONG).show();
            }
        }
    }
}

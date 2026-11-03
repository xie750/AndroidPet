package com.example.desktoppet;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    public static final String EXTRA_QUICK_ADD = "quick_add";
    private static final int REQUEST_NOTIFICATIONS = 2001;
    private static final int ORANGE = Color.rgb(255, 138, 52);
    private static final int CREAM = Color.rgb(255, 248, 237);
    private static final int TEXT = Color.rgb(61, 49, 43);

    private AppDatabase db;
    private LinearLayout listContainer;
    private TextView permissionStatus;
    private Button petToggleButton;
    private boolean quickAddHandled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new AppDatabase(this);
        AlarmReceiver.ensureChannel(this);
        setContentView(buildScreen());
        refreshList();
        handleQuickAdd(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        quickAddHandled = false;
        handleQuickAdd(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
        refreshList();
    }

    @Override
    protected void onDestroy() {
        db.close();
        super.onDestroy();
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CREAM);
        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(18), dp(18), dp(40));
        scroll.addView(root, matchWrap());

        LinearLayout hero = vertical();
        hero.setPadding(dp(20), dp(20), dp(20), dp(18));
        hero.setBackground(rounded(ORANGE, 24));
        TextView title = text("费雾", 28, Color.WHITE, true);
        TextView subtitle = text("会陪伴、记待办，也会准时提醒你的安卓桌宠", 14,
                Color.argb(225, 255, 255, 255), false);
        hero.addView(title);
        hero.addView(space(5));
        hero.addView(subtitle);
        root.addView(hero, matchWrap());

        root.addView(space(14));
        LinearLayout statusCard = card();
        permissionStatus = text("正在检查权限…", 14, TEXT, false);
        statusCard.addView(text("运行状态", 18, TEXT, true));
        statusCard.addView(space(8));
        statusCard.addView(permissionStatus);
        statusCard.addView(space(12));
        petToggleButton = primaryButton("开启桌宠", v -> togglePet());
        statusCard.addView(petToggleButton, matchWrap());
        root.addView(statusCard, matchWrap());

        root.addView(sectionTitle("快速记录"));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button task = primaryButton("＋ 新建待办", v -> showTaskDialog());
        Button note = secondaryButton("＋ 写笔记", v -> showNoteDialog());
        actions.addView(task, weighted());
        actions.addView(spaceHorizontal(10));
        actions.addView(note, weighted());
        root.addView(actions, matchWrap());

        root.addView(sectionTitle("待办与笔记"));
        listContainer = vertical();
        root.addView(listContainer, matchWrap());

        root.addView(sectionTitle("权限与适配"));
        LinearLayout settings = card();
        settings.addView(outlineButton("允许通知", v -> requestNotificationPermission()), matchWrap());
        settings.addView(space(8));
        settings.addView(outlineButton("允许精确提醒", v -> requestExactAlarmPermission()), matchWrap());
        settings.addView(space(8));
        settings.addView(outlineButton(isXiaomi() ? "红米/小米自启动管理" : "打开应用系统设置",
                v -> openVendorSettings()), matchWrap());
        settings.addView(space(8));
        settings.addView(outlineButton("电池与后台运行设置", v -> openBatterySettings()), matchWrap());
        settings.addView(space(10));
        CheckBox autoStart = new CheckBox(this);
        autoStart.setText("手机重启后尝试恢复桌宠");
        autoStart.setTextColor(TEXT);
        SharedPreferences prefs = getSharedPreferences("pet_settings", MODE_PRIVATE);
        autoStart.setChecked(prefs.getBoolean("auto_start", false));
        autoStart.setOnCheckedChangeListener((buttonView, checked) ->
                prefs.edit().putBoolean("auto_start", checked).apply());
        settings.addView(autoStart);
        TextView hint = text(isXiaomi()
                        ? "红米提示：请同时允许“自启动”，并将省电策略设为“不限制”。不同 HyperOS 版本的名称可能略有差异。"
                        : "如果桌宠容易消失，请在系统设置里允许自启动，并关闭针对本应用的电池限制。",
                12, Color.rgb(120, 103, 93), false);
        hint.setPadding(0, dp(8), 0, 0);
        settings.addView(hint);
        root.addView(settings, matchWrap());

        return scroll;
    }

    private void togglePet() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            toast("请打开“允许显示在其他应用上层”，返回后再点开启");
            return;
        }
        Intent service = new Intent(this, PetOverlayService.class);
        startForegroundService(service);
        petToggleButton.setText("桌宠已开启 · 可从通知栏停止");
        toast("费雾已经出来啦，长按它可以快速记待办");
    }

    private void showTaskDialog() {
        LinearLayout form = vertical();
        form.setPadding(dp(4), 0, dp(4), 0);
        EditText title = input("待办标题，例如：交水电费");
        EditText detail = input("补充说明（可选）");
        final long[] dueAt = {System.currentTimeMillis() + 60L * 60L * 1000L};
        Button dateButton = secondaryButton("选择日期", null);
        Button timeButton = secondaryButton("选择时间", null);
        TextView selected = text("", 13, TEXT, false);
        Runnable refreshSelected = () -> selected.setText(
                String.format(Locale.CHINA, "提醒时间：%s", formatTime(dueAt[0])));
        refreshSelected.run();

        dateButton.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(dueAt[0]);
            new DatePickerDialog(this, (view, year, month, day) -> {
                cal.set(Calendar.YEAR, year);
                cal.set(Calendar.MONTH, month);
                cal.set(Calendar.DAY_OF_MONTH, day);
                dueAt[0] = cal.getTimeInMillis();
                refreshSelected.run();
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });
        timeButton.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(dueAt[0]);
            new TimePickerDialog(this, (view, hour, minute) -> {
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, minute);
                cal.set(Calendar.SECOND, 0);
                dueAt[0] = cal.getTimeInMillis();
                refreshSelected.run();
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
        });

        Spinner repeat = new Spinner(this);
        String[] repeatLabels = {"不重复", "每天重复", "每周重复"};
        repeat.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, repeatLabels));
        form.addView(title);
        form.addView(space(6));
        form.addView(detail);
        form.addView(space(10));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(dateButton, weighted());
        row.addView(spaceHorizontal(8));
        row.addView(timeButton, weighted());
        form.addView(row, matchWrap());
        form.addView(space(8));
        form.addView(selected);
        form.addView(space(6));
        form.addView(repeat, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("新建待办")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并提醒", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String taskTitle = title.getText().toString().trim();
            if (taskTitle.isEmpty()) {
                title.setError("请填写待办标题");
                return;
            }
            if (dueAt[0] <= System.currentTimeMillis()) {
                toast("提醒时间需要晚于当前时间");
                return;
            }
            String[] repeatValues = {"NONE", "DAILY", "WEEKLY"};
            long id = db.insert("TASK", taskTitle, detail.getText().toString().trim(),
                    dueAt[0], repeatValues[repeat.getSelectedItemPosition()]);
            ReminderScheduler.schedule(this, id, dueAt[0]);
            refreshList();
            dialog.dismiss();
            toast("待办已保存");
        }));
        dialog.show();
    }

    private void showNoteDialog() {
        LinearLayout form = vertical();
        EditText title = input("笔记标题");
        EditText content = input("写下内容…");
        content.setMinLines(5);
        content.setGravity(Gravity.TOP);
        form.addView(title);
        form.addView(space(8));
        form.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("写笔记")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String noteTitle = title.getText().toString().trim();
            String noteContent = content.getText().toString().trim();
            if (noteTitle.isEmpty() && noteContent.isEmpty()) {
                title.setError("至少写一点内容");
                return;
            }
            if (noteTitle.isEmpty()) noteTitle = "随手记";
            db.insert("NOTE", noteTitle, noteContent, 0L, "NONE");
            refreshList();
            dialog.dismiss();
            toast("笔记已保存");
        }));
        dialog.show();
    }

    private void refreshList() {
        if (listContainer == null || db == null) return;
        listContainer.removeAllViews();
        List<Item> items = db.getAll();
        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("还没有记录", 16, TEXT, true));
            empty.addView(space(4));
            empty.addView(text("新建一个待办，试试让费雾提醒你。", 13,
                    Color.rgb(130, 112, 101), false));
            listContainer.addView(empty, matchWrap());
            return;
        }
        for (Item item : items) {
            LinearLayout card = card();
            TextView tag = text("TASK".equals(item.type) ? "待办" : "笔记", 12,
                    "TASK".equals(item.type) ? ORANGE : Color.rgb(79, 139, 125), true);
            card.addView(tag);
            TextView title = text((item.done ? "✓ " : "") + item.title, 17,
                    item.done ? Color.GRAY : TEXT, true);
            card.addView(title);
            if ("TASK".equals(item.type) && item.dueAt > 0) {
                String repeatText = "DAILY".equals(item.repeatType) ? " · 每天" :
                        "WEEKLY".equals(item.repeatType) ? " · 每周" : "";
                card.addView(text(formatTime(item.dueAt) + repeatText, 12,
                        Color.rgb(122, 101, 90), false));
            }
            if (!item.content.isEmpty()) {
                TextView content = text(item.content, 14, Color.rgb(93, 78, 70), false);
                content.setPadding(0, dp(7), 0, 0);
                card.addView(content);
            }
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setPadding(0, dp(10), 0, 0);
            if ("TASK".equals(item.type)) {
                Button complete = smallButton(item.done ? "恢复" : "完成", v -> {
                    db.markDone(item.id, !item.done);
                    if (!item.done) ReminderScheduler.cancel(this, item.id);
                    else ReminderScheduler.schedule(this, item.id, item.dueAt);
                    refreshList();
                });
                buttons.addView(complete);
                buttons.addView(spaceHorizontal(8));
            }
            Button delete = smallButton("删除", v -> confirmDelete(item));
            buttons.addView(delete);
            card.addView(buttons);
            listContainer.addView(card, matchWrap());
            listContainer.addView(space(10));
        }
    }

    private void confirmDelete(Item item) {
        new AlertDialog.Builder(this)
                .setTitle("删除“" + item.title + "”？")
                .setMessage("删除后无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    ReminderScheduler.cancel(this, item.id);
                    db.delete(item.id);
                    refreshList();
                }).show();
    }

    private void updatePermissionStatus() {
        if (permissionStatus == null) return;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(String.format(Locale.CHINA, "悬浮窗：%s　通知：%s%s",
                overlay ? "已允许" : "未允许",
                notifications ? "已允许" : "未允许",
                isXiaomi() ? "\n已识别为红米/小米设备，将提供专用后台设置入口。" : ""));
        if (overlay) petToggleButton.setText("开启 / 重新显示桌宠");
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else toast("通知权限已经可以使用");
    }

    private void requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager manager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (manager != null && !manager.canScheduleExactAlarms()) {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
        }
        toast("精确提醒已经可以使用");
    }

    private void openVendorSettings() {
        if (isXiaomi()) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                startActivity(intent);
                return;
            } catch (RuntimeException ignored) {}
        }
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (RuntimeException ignored) {
            openVendorSettings();
        }
    }

    private boolean isXiaomi() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.ROOT);
        return maker.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco");
    }

    private void handleQuickAdd(Intent intent) {
        if (!quickAddHandled && intent != null && intent.getBooleanExtra(EXTRA_QUICK_ADD, false)) {
            quickAddHandled = true;
            intent.removeExtra(EXTRA_QUICK_ADD);
            showTaskDialog();
        }
    }

    private String formatTime(long time) {
        return new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(new Date(time));
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = vertical();
        layout.setPadding(dp(16), dp(15), dp(16), dp(15));
        layout.setBackground(rounded(Color.WHITE, 18));
        return layout;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 18, TEXT, true);
        view.setPadding(dp(2), dp(22), 0, dp(10));
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private EditText input(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(Color.rgb(160, 145, 137));
        edit.setSingleLine(false);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return edit;
    }

    private Button primaryButton(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(rounded(ORANGE, 14));
        button.setMinHeight(dp(48));
        if (listener != null) button.setOnClickListener(listener);
        return button;
    }

    private Button secondaryButton(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(rounded(Color.rgb(255, 232, 203), 14));
        button.setMinHeight(dp(48));
        if (listener != null) button.setOnClickListener(listener);
        return button;
    }

    private Button outlineButton(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        GradientDrawable bg = rounded(Color.rgb(255, 251, 245), 12);
        bg.setStroke(dp(1), Color.rgb(230, 210, 191));
        button.setBackground(bg);
        button.setOnClickListener(listener);
        return button;
    }

    private Button smallButton(String value, View.OnClickListener listener) {
        Button button = secondaryButton(value, listener);
        button.setTextSize(12);
        button.setMinHeight(dp(38));
        button.setMinimumWidth(dp(72));
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private View space(int height) {
        return new View(this) {{ setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); }};
    }

    private View spaceHorizontal(int width) {
        return new View(this) {{ setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1)); }};
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
}

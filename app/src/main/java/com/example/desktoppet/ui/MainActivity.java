package com.example.desktoppet.ui;

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
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;

import com.example.desktoppet.R;
import com.example.desktoppet.data.AppDatabase;
import com.example.desktoppet.feature.pet.PetOverlayService;
import com.example.desktoppet.feature.pet.SkinCatalog;
import com.example.desktoppet.feature.reminder.AlarmReceiver;
import com.example.desktoppet.feature.reminder.ReminderScheduler;
import com.example.desktoppet.feature.voice.VoiceControlService;
import com.example.desktoppet.model.Item;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    public static final String EXTRA_QUICK_ADD = "quick_add";
    private static final int REQUEST_NOTIFICATIONS = 2001;
    private static final int REQUEST_MICROPHONE = 2002;
    private static final int REQUEST_CUSTOM_SKIN = 2003;
    private static final int DEEP_NAVY = Color.rgb(3, 8, 23);
    private static final int SURFACE = Color.rgb(9, 28, 54);
    private static final int SURFACE_HIGH = Color.rgb(13, 42, 72);
    private static final int CYAN = Color.rgb(50, 229, 242);
    private static final int COBALT = Color.rgb(75, 109, 255);
    private static final int SIGNAL = Color.rgb(183, 255, 69);
    private static final int TEXT = Color.rgb(239, 248, 255);
    private static final int TEXT_MUTED = Color.rgb(169, 195, 214);
    private static final int LINE = Color.argb(125, 70, 205, 231);

    private AppDatabase db;
    private LinearLayout listContainer;
    private TextView permissionStatus;
    private Button petToggleButton;
    private Button voiceToggleButton;
    private ImageView skinPreview;
    private TextView skinDescription;
    private FrameLayout pageHost;
    private TextView pageTitle;
    private Button[] navigationButtons;
    private int activePageIndex;
    private final Handler startupHandler = new Handler(Looper.getMainLooper());
    private FrameLayout startupOverlay;
    private TextView startupStage;
    private TextView startupDetail;
    private ImageView startupMascot;
    private boolean showStartupAnimation;
    private boolean quickAddHandled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(DEEP_NAVY);
        window.setNavigationBarColor(DEEP_NAVY);
        window.getDecorView().setSystemUiVisibility(0);
        db = new AppDatabase(this);
        AlarmReceiver.ensureChannel(this);
        showStartupAnimation = savedInstanceState == null &&
                !getIntent().getBooleanExtra(EXTRA_QUICK_ADD, false);
        setContentView(buildScreen());
        refreshList();
        if (showStartupAnimation) startStartupSequence();
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
        updateVoiceButton();
        refreshList();
        SharedPreferences prefs = getSharedPreferences("pet_settings", MODE_PRIVATE);
        if (PetOverlayService.isRunning() && prefs.getBoolean("voice_enabled", true) &&
                !VoiceControlService.isRunning() &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceControl(false);
        }
    }

    @Override
    protected void onDestroy() {
        startupHandler.removeCallbacksAndMessages(null);
        db.close();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CUSTOM_SKIN || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}
        getSharedPreferences("pet_settings", MODE_PRIVATE).edit()
                .putString("custom_skin_uri", uri.toString())
                .putInt("selected_skin", -1)
                .apply();
        showSkinPreview(-1);
        refreshRunningPet();
        toast("自定义形象已应用");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getSharedPreferences("pet_settings", MODE_PRIVATE).edit()
                        .putBoolean("voice_enabled", true).apply();
                startVoiceControl(true);
            } else {
                toast("需要麦克风权限才能使用语音控制");
            }
        }
    }

    private View buildScreen() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(DEEP_NAVY);
        screen.addView(new DeepSeaBackdropView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout chrome = vertical();
        chrome.setPadding(dp(12), dp(8), dp(12), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(6), 0);
        LinearLayout titleStack = vertical();
        titleStack.addView(text("FEIWU · PERSONAL CORE", 9, CYAN, true));
        pageTitle = text("首页", 22, TEXT, true);
        titleStack.addView(pageTitle);
        header.addView(titleStack, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView local = text("●  本机运行", 11, SIGNAL, true);
        local.setPadding(dp(11), dp(7), dp(11), dp(7));
        local.setBackground(rounded(Color.argb(120, 23, 52, 45), 10));
        header.addView(local);
        chrome.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        pageHost = new FrameLayout(this);
        pageHost.addView(buildHomePage(), fullFrame());
        pageHost.addView(buildRecordsPage(), fullFrame());
        pageHost.addView(buildCompanionPage(), fullFrame());
        pageHost.addView(buildSettingsPage(), fullFrame());
        chrome.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        chrome.addView(buildBottomNavigation(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
        screen.addView(chrome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        switchPage(0);
        if (showStartupAnimation) screen.addView(buildStartupOverlay(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return screen;
    }

    private View buildHomePage() {
        LinearLayout content = pageContent("TODAY OVERVIEW", "把常用动作留在第一屏，不再向下找功能。");
        FrameLayout hero = new FrameLayout(this);
        hero.setPadding(dp(19), dp(18), dp(16), dp(16));
        hero.setBackground(heroSurface());
        LinearLayout copy = vertical();
        copy.addView(text(greeting(), 29, TEXT, true));
        copy.addView(space(5));
        copy.addView(text("费雾已连接，提醒、记录和语音随时可用。", 13, TEXT_MUTED, false));
        TextView online = text("●  DESKTOP PET ONLINE", 10, SIGNAL, true);
        online.setPadding(0, dp(18), 0, 0);
        copy.addView(online);
        hero.addView(copy, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));
        ImageView mascot = new ImageView(this);
        mascot.setImageResource(R.drawable.skin_mist_blob);
        mascot.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mascot.setContentDescription("费雾雾狐桌宠");
        FrameLayout.LayoutParams mascotParams = new FrameLayout.LayoutParams(dp(150), dp(150));
        mascotParams.gravity = Gravity.END | Gravity.BOTTOM;
        mascotParams.setMargins(0, dp(22), dp(-14), dp(-8));
        hero.addView(mascot, mascotParams);
        content.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(218)));

        content.addView(sectionTitle("快速操作"));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(primaryButton("新建待办", v -> showTaskDialog()), weighted());
        actions.addView(spaceHorizontal(8));
        actions.addView(secondaryButton("写笔记", v -> showNoteDialog()), weighted());
        content.addView(actions, matchWrap());
        content.addView(space(8));
        Button voice = secondaryButton("开始语音唤醒", v -> toggleVoiceControl());
        content.addView(voice, matchWrap());

        content.addView(sectionTitle("运行状态"));
        LinearLayout status = card();
        status.addView(text("桌宠与系统权限", 16, TEXT, true));
        status.addView(space(7));
        permissionStatus = text("正在检查权限…", 13, TEXT_MUTED, false);
        status.addView(permissionStatus);
        status.addView(space(13));
        petToggleButton = primaryButton("开启桌宠", v -> togglePet());
        status.addView(petToggleButton, matchWrap());
        content.addView(status, matchWrap());
        return scrollPage(content);
    }

    private View buildRecordsPage() {
        LinearLayout content = pageContent("LOGBOOK", "待办与笔记集中在这里，首页不再堆成长列表。");
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(primaryButton("新建待办", v -> showTaskDialog()), weighted());
        actions.addView(spaceHorizontal(8));
        actions.addView(secondaryButton("新建笔记", v -> showNoteDialog()), weighted());
        content.addView(actions, matchWrap());
        content.addView(sectionTitle("全部记录"));
        listContainer = vertical();
        content.addView(listContainer, matchWrap());
        return scrollPage(content);
    }

    private View buildCompanionPage() {
        LinearLayout content = pageContent("COMPANION", "形象和声音只在伙伴页配置，修改后立即同步桌宠。");
        content.addView(sectionTitle("桌宠形象"));
        content.addView(buildSkinCard(), matchWrap());
        content.addView(sectionTitle("语音互动"));
        content.addView(buildVoiceCard(), matchWrap());
        return scrollPage(content);
    }

    private View buildSettingsPage() {
        LinearLayout content = pageContent("SYSTEM SETTINGS", "管理模型连接、系统权限与后台运行策略。");
        content.addView(sectionTitle("模型连接"));
        content.addView(buildModelCard(), matchWrap());
        content.addView(sectionTitle("权限与后台"));
        LinearLayout settings = card();
        settings.addView(outlineButton("允许通知", v -> requestNotificationPermission()), matchWrap());
        settings.addView(space(8));
        settings.addView(outlineButton("允许精确提醒", v -> requestExactAlarmPermission()), matchWrap());
        settings.addView(space(8));
        settings.addView(outlineButton(isXiaomi() ? "红米 / 小米自启动管理" : "打开应用系统设置",
                v -> openVendorSettings()), matchWrap());
        settings.addView(space(8));
        settings.addView(outlineButton("电池与后台运行设置", v -> openBatterySettings()), matchWrap());
        settings.addView(space(10));
        CheckBox autoStart = new CheckBox(this);
        autoStart.setText("手机重启后恢复桌宠");
        autoStart.setTextColor(TEXT);
        autoStart.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{CYAN, TEXT_MUTED}));
        SharedPreferences prefs = getSharedPreferences("pet_settings", MODE_PRIVATE);
        autoStart.setChecked(prefs.getBoolean("auto_start", false));
        autoStart.setOnCheckedChangeListener((buttonView, checked) ->
                prefs.edit().putBoolean("auto_start", checked).apply());
        settings.addView(autoStart);
        TextView hint = text(isXiaomi()
                        ? "HyperOS 还需要允许自启动，并把省电策略设为“不限制”。"
                        : "如果桌宠容易消失，请关闭系统对本应用的电池限制。",
                12, TEXT_MUTED, false);
        hint.setPadding(0, dp(8), 0, 0);
        settings.addView(hint);
        content.addView(settings, matchWrap());
        return scrollPage(content);
    }

    private LinearLayout pageContent(String title, String subtitle) {
        LinearLayout content = vertical();
        content.setPadding(dp(4), dp(12), dp(4), dp(24));
        TextView eyebrow = text(title, 9, CYAN, true);
        eyebrow.setLetterSpacing(0.14f);
        content.addView(eyebrow);
        content.addView(space(3));
        content.addView(text(subtitle, 12, TEXT_MUTED, false));
        content.addView(space(16));
        return content;
    }

    private ScrollView scrollPage(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, matchWrap());
        return scroll;
    }

    private View buildBottomNavigation() {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable background = rounded(Color.argb(248, 7, 18, 34), 18);
        background.setStroke(dp(1), Color.argb(105, 82, 151, 181));
        navigation.setBackground(background);
        String[] labels = {"首页\nHOME", "记录\nLOG", "伙伴\nPET", "设置\nSET"};
        navigationButtons = new Button[labels.length];
        for (int index = 0; index < labels.length; index++) {
            final int pageIndex = index;
            Button button = new Button(this);
            button.setText(labels[index]);
            button.setAllCaps(false);
            button.setTextSize(11);
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(dp(56));
            button.setPadding(dp(3), 0, dp(3), 0);
            button.setStateListAnimator(null);
            button.setOnClickListener(v -> switchPage(pageIndex));
            navigationButtons[index] = button;
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(56), 1f));
        }
        return navigation;
    }

    private void switchPage(int index) {
        if (pageHost == null || navigationButtons == null) return;
        activePageIndex = Math.max(0, Math.min(index, pageHost.getChildCount() - 1));
        String[] titles = {"首页", "记录", "伙伴", "设置"};
        for (int page = 0; page < pageHost.getChildCount(); page++) {
            pageHost.getChildAt(page).setVisibility(page == activePageIndex ? View.VISIBLE : View.GONE);
            boolean selected = page == activePageIndex;
            navigationButtons[page].setTextColor(selected ? CYAN : TEXT_MUTED);
            GradientDrawable fill = rounded(selected
                    ? Color.argb(150, 26, 66, 88) : Color.TRANSPARENT, 13);
            navigationButtons[page].setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Color.argb(55, 50, 229, 242)), fill, null));
            navigationButtons[page].setContentDescription(titles[page] + (selected ? "，当前页面" : ""));
        }
        if (pageTitle != null) pageTitle.setText(titles[activePageIndex]);
    }

    private FrameLayout.LayoutParams fullFrame() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout buildStartupOverlay() {
        startupOverlay = new FrameLayout(this);
        GradientDrawable backdrop = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(1, 5, 16), Color.rgb(5, 24, 48), Color.rgb(2, 8, 24)});
        startupOverlay.setBackground(backdrop);
        startupOverlay.setClickable(true);
        startupOverlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        startupOverlay.setContentDescription("费雾启动动画");

        Button skip = outlineButton("跳过", v -> dismissStartup());
        skip.setMinWidth(dp(76));
        FrameLayout.LayoutParams skipParams = new FrameLayout.LayoutParams(dp(84), dp(48));
        skipParams.gravity = Gravity.END | Gravity.TOP;
        skipParams.setMargins(0, dp(16), dp(16), 0);
        startupOverlay.addView(skip, skipParams);

        LinearLayout center = vertical();
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setPadding(dp(26), 0, dp(26), 0);

        startupMascot = new ImageView(this);
        startupMascot.setImageResource(R.drawable.skin_mist_blob);
        startupMascot.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        startupMascot.setAlpha(0.35f);
        startupMascot.setScaleX(0.82f);
        startupMascot.setScaleY(0.82f);
        startupMascot.setContentDescription("正在苏醒的费雾雾狐");
        center.addView(startupMascot, new LinearLayout.LayoutParams(dp(210), dp(210)));
        center.addView(space(16));

        startupStage = text("灵核唤醒", 27, TEXT, true);
        startupStage.setGravity(Gravity.CENTER);
        center.addView(startupStage, matchWrap());
        center.addView(space(8));
        startupDetail = text("CORE AWAKENING · 00", 11, CYAN, true);
        startupDetail.setGravity(Gravity.CENTER);
        startupDetail.setLetterSpacing(0.13f);
        center.addView(startupDetail, matchWrap());
        center.addView(space(24));

        View progress = new View(this);
        GradientDrawable progressBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.TRANSPARENT, CYAN, Color.WHITE, CYAN, Color.TRANSPARENT});
        progress.setBackground(progressBg);
        center.addView(progress, new LinearLayout.LayoutParams(dp(236), dp(2)));
        center.addView(space(14));
        TextView hint = text("正在初始化本机感知、提醒与模型链路", 12, TEXT_MUTED, false);
        hint.setGravity(Gravity.CENTER);
        center.addView(hint, matchWrap());

        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        centerParams.gravity = Gravity.CENTER;
        startupOverlay.addView(center, centerParams);

        TextView footer = text("FEIWU · DEEPSEA CORE SYSTEM", 10, Color.argb(170, 151, 205, 221), true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.12f);
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.gravity = Gravity.BOTTOM;
        footerParams.setMargins(dp(16), 0, dp(16), dp(28));
        startupOverlay.addView(footer, footerParams);
        return startupOverlay;
    }

    private void startStartupSequence() {
        if (startupOverlay == null || startupStage == null) return;
        String[] stages = {
                "灵核唤醒", "正在建立感知", "身份确认", "请求本机权限",
                "读取服务状态", "建立灵核链路", "费雾已苏醒", "欢迎回来"
        };
        String[] details = {
                "CORE AWAKENING · 01", "SENSORY LINK · 02", "IDENTITY VERIFIED · 03",
                "LOCAL PERMISSION · 04", "SERVICE SCAN · 05", "CORE LINK · 06",
                "FEIWU ONLINE · 07", "SYSTEM READY · 08"
        };
        long[] at = {0L, 430L, 850L, 1210L, 1610L, 2020L, 2550L, 3040L};
        for (int i = 0; i < stages.length; i++) {
            final int index = i;
            startupHandler.postDelayed(() -> showStartupStage(stages[index], details[index], index), at[i]);
        }
        startupHandler.postDelayed(this::dismissStartup, 3500L);
    }

    private void showStartupStage(String stage, String detail, int index) {
        if (startupStage == null || startupDetail == null || startupOverlay == null) return;
        startupStage.animate().cancel();
        startupDetail.animate().cancel();
        startupStage.animate().alpha(0f).setDuration(70L).withEndAction(() -> {
            startupStage.setText(stage);
            startupDetail.setText(detail);
            startupStage.animate().alpha(1f).translationY(0f).setDuration(180L).start();
            startupDetail.animate().alpha(1f).setDuration(180L).start();
        }).start();
        startupStage.setTranslationY(dp(5));
        startupDetail.setAlpha(0f);
        if (startupMascot != null) {
            float scale = index < 2 ? 0.84f : index < 6 ? 0.92f : 1f;
            startupMascot.animate()
                    .alpha(index < 2 ? 0.38f : index < 6 ? 0.62f : 1f)
                    .scaleX(scale).scaleY(scale)
                    .setDuration(index == 2 ? 180L : 320L)
                    .start();
        }
        if (index == 2) {
            startupOverlay.animate().alpha(0.66f).setDuration(85L)
                    .withEndAction(() -> startupOverlay.animate().alpha(1f).setDuration(160L).start())
                    .start();
        }
        startupOverlay.announceForAccessibility(stage);
    }

    private void dismissStartup() {
        startupHandler.removeCallbacksAndMessages(null);
        if (startupOverlay == null) return;
        FrameLayout overlay = startupOverlay;
        startupOverlay = null;
        overlay.animate().alpha(0f).setDuration(260L).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) parent.removeView(overlay);
        }).start();
    }

    private String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 6) return "夜深了";
        if (hour < 11) return "早上好";
        if (hour < 14) return "中午好";
        if (hour < 18) return "下午好";
        return "晚上好";
    }

    private GradientDrawable heroSurface() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(248, 9, 28, 49), Color.argb(244, 11, 41, 62)});
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), Color.argb(115, 65, 177, 204));
        return drawable;
    }

    private View buildSkinCard() {
        SharedPreferences prefs = getSharedPreferences("pet_settings", MODE_PRIVATE);
        LinearLayout layout = card();
        skinPreview = new ImageView(this);
        skinPreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        skinPreview.setAdjustViewBounds(true);
        layout.addView(skinPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));

        skinDescription = text("", 13, TEXT_MUTED, false);
        skinDescription.setGravity(Gravity.CENTER);
        layout.addView(skinDescription);
        layout.addView(space(8));

        Spinner skinSpinner = new Spinner(this);
        String[] builtInNames = SkinCatalog.names();
        boolean hasCustom = !prefs.getString("custom_skin_uri", "").isEmpty();
        String[] displayNames;
        if (hasCustom) {
            displayNames = new String[builtInNames.length + 1];
            System.arraycopy(builtInNames, 0, displayNames, 0, builtInNames.length);
            displayNames[builtInNames.length] = "自定义形象";
        } else {
            displayNames = builtInNames;
        }
        skinSpinner.setAdapter(darkSpinnerAdapter(displayNames));
        skinSpinner.setBackground(fieldSurface());
        int selected = prefs.getInt("selected_skin", 1);
        skinSpinner.setSelection(selected == -1 && hasCustom
                ? builtInNames.length : SkinCatalog.safeIndex(selected));
        showSkinPreview(selected);
        skinSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int selectedValue = position == SkinCatalog.BUILT_INS.length ? -1 : position;
                prefs.edit().putInt("selected_skin", selectedValue).apply();
                showSkinPreview(selectedValue);
                refreshRunningPet();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        layout.addView(skinSpinner, matchWrap());
        layout.addView(space(8));

        Button importButton = outlineButton("导入自己的透明 PNG / 图片", v -> openCustomSkinPicker());
        layout.addView(importButton, matchWrap());
        layout.addView(space(12));

        TextView sizeLabel = text("桌宠大小", 14, TEXT, true);
        layout.addView(sizeLabel);
        SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMin(70);
        sizeBar.setMax(150);
        sizeBar.setProgress(prefs.getInt("pet_size_percent", 100));
        sizeBar.setProgressTintList(ColorStateList.valueOf(CYAN));
        sizeBar.setThumbTintList(ColorStateList.valueOf(CYAN));
        TextView sizeValue = text(sizeBar.getProgress() + "%", 12, TEXT_MUTED, false);
        sizeValue.setGravity(Gravity.CENTER_HORIZONTAL);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sizeValue.setText(String.format(Locale.CHINA, "%d%%", progress));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt("pet_size_percent", seekBar.getProgress()).apply();
                refreshRunningPet();
            }
        });
        layout.addView(sizeBar, matchWrap());
        layout.addView(sizeValue);
        return layout;
    }

    private View buildVoiceCard() {
        SharedPreferences prefs = getSharedPreferences("pet_settings", MODE_PRIVATE);
        LinearLayout layout = card();
        layout.addView(text("桌宠启动后自动听候唤醒", 16, TEXT, true));
        layout.addView(space(5));
        layout.addView(text("示例：你好费雾，提醒我明天下午三点交水费\n" +
                        "示例：你好费雾，记一下周末买猫粮\n" +
                        "示例：你好费雾，今天有什么待办\n" +
                        "也可以问时间、问它会做什么，或换一种自然说法。",
                13, TEXT_MUTED, false));
        layout.addView(space(12));
        voiceToggleButton = primaryButton("开启持续语音唤醒", v -> toggleVoiceControl());
        layout.addView(voiceToggleButton, matchWrap());
        layout.addView(space(10));
        layout.addView(text("回复音色", 14, TEXT, true));
        Spinner voiceStyle = new Spinner(this);
        String[] styleLabels = {"自然（自动选择高质量中文声线）", "温柔舒缓", "活泼明亮",
                "纯离线", "跟随系统默认"};
        String[] styleValues = {"natural", "gentle", "bright", "offline", "system"};
        voiceStyle.setAdapter(darkSpinnerAdapter(styleLabels));
        voiceStyle.setBackground(fieldSurface());
        String currentStyle = prefs.getString("tts_profile", "natural");
        int currentIndex = 0;
        for (int i = 0; i < styleValues.length; i++) {
            if (styleValues[i].equals(currentStyle)) currentIndex = i;
        }
        voiceStyle.setSelection(currentIndex);
        voiceStyle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString("tts_profile", styleValues[position]).apply();
                if (VoiceControlService.isRunning()) {
                    startService(new Intent(MainActivity.this, VoiceControlService.class)
                            .setAction(VoiceControlService.ACTION_REFRESH_SETTINGS));
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        layout.addView(voiceStyle, matchWrap());
        Button previewVoice = secondaryButton("试听当前音色", v -> {
            if (!VoiceControlService.isRunning()) {
                toast("请先开启语音监听，再试听音色");
                return;
            }
            startService(new Intent(this, VoiceControlService.class)
                    .setAction(VoiceControlService.ACTION_PREVIEW_TTS));
        });
        layout.addView(previewVoice, matchWrap());
        return layout;
    }

    private View buildModelCard() {
        SharedPreferences prefs = getSharedPreferences("pet_settings", MODE_PRIVATE);
        LinearLayout layout = card();
        layout.addView(text("电脑中转服务", 16, TEXT, true));
        layout.addView(space(5));
        layout.addView(text("手机只保存中转地址和访问令牌，第三方 API Key 始终留在电脑端。",
                12, TEXT_MUTED, false));
        layout.addView(space(12));
        CheckBox modelEnabled = new CheckBox(this);
        modelEnabled.setText("启用智能对话");
        modelEnabled.setTextColor(TEXT);
        modelEnabled.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{CYAN, TEXT_MUTED}));
        modelEnabled.setChecked(prefs.getBoolean("model_enabled", true));
        layout.addView(modelEnabled);
        EditText endpoint = input("电脑服务地址，例如 http://192.168.1.20:8765");
        endpoint.setSingleLine(true);
        endpoint.setText(prefs.getString("model_endpoint", ""));
        EditText clientToken = input("电脑服务访问令牌（不是第三方 API Key）");
        clientToken.setSingleLine(true);
        clientToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        clientToken.setText(prefs.getString("bridge_client_token", ""));
        layout.addView(endpoint, matchWrap());
        layout.addView(clientToken, matchWrap());
        Button saveModel = outlineButton("保存智能对话设置", v -> {
            String address = endpoint.getText().toString().trim();
            if (modelEnabled.isChecked() &&
                    !address.startsWith("http://") && !address.startsWith("https://")) {
                endpoint.setError("请填写以 http:// 或 https:// 开头的地址");
                return;
            }
            prefs.edit()
                    .putBoolean("model_enabled", modelEnabled.isChecked())
                    .putString("model_endpoint", address)
                    .putString("bridge_client_token", clientToken.getText().toString().trim())
                    .apply();
            toast("智能对话设置已保存；普通问题会自动交给模型回答");
        });
        layout.addView(saveModel, matchWrap());
        layout.addView(space(7));
        layout.addView(text("开发环境可填写局域网地址；正式发布建议使用受信任的 HTTPS 服务。",
                12, TEXT_MUTED, false));
        return layout;
    }

    private void showSkinPreview(int selected) {
        if (skinPreview == null || skinDescription == null) return;
        SharedPreferences prefs = getSharedPreferences("pet_settings", MODE_PRIVATE);
        if (selected == -1) {
            String value = prefs.getString("custom_skin_uri", "");
            if (!value.isEmpty()) skinPreview.setImageURI(Uri.parse(value));
            skinDescription.setText("当前使用：自定义形象");
            return;
        }
        int index = SkinCatalog.safeIndex(selected);
        SkinCatalog.Skin skin = SkinCatalog.BUILT_INS[index];
        skinPreview.setImageResource(skin.resourceId);
        skinDescription.setText(String.format(Locale.CHINA, "%s · %s", skin.name, skin.description));
    }

    private void openCustomSkinPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CUSTOM_SKIN);
    }

    private void refreshRunningPet() {
        if (!PetOverlayService.isRunning()) return;
        startService(new Intent(this, PetOverlayService.class)
                .setAction(PetOverlayService.ACTION_REFRESH_SKIN));
    }

    private void toggleVoiceControl() {
        if (VoiceControlService.isRunning()) {
            getSharedPreferences("pet_settings", MODE_PRIVATE).edit()
                    .putBoolean("voice_enabled", false).apply();
            startService(new Intent(this, VoiceControlService.class)
                    .setAction(VoiceControlService.ACTION_STOP));
            voiceToggleButton.postDelayed(this::updateVoiceButton, 300L);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("pet_settings", MODE_PRIVATE).edit()
                    .putBoolean("voice_enabled", true).apply();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        getSharedPreferences("pet_settings", MODE_PRIVATE).edit()
                .putBoolean("voice_enabled", true).apply();
        startVoiceControl(true);
    }

    private void startVoiceControl(boolean showToast) {
        if (VoiceControlService.isRunning()) return;
        startForegroundService(new Intent(this, VoiceControlService.class));
        if (voiceToggleButton != null) voiceToggleButton.setText("关闭持续语音唤醒");
        if (showToast) toast("正在加载离线语音模型，加载后请说：你好费雾");
    }

    private void updateVoiceButton() {
        if (voiceToggleButton == null) return;
        voiceToggleButton.setText(VoiceControlService.isRunning()
                ? "关闭持续语音唤醒" : "开启持续语音唤醒");
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
        SharedPreferences prefs = getSharedPreferences("pet_settings", MODE_PRIVATE);
        if (prefs.getBoolean("voice_enabled", true)) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startVoiceControl(false);
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            }
        }
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
        repeat.setAdapter(darkSpinnerAdapter(repeatLabels));
        repeat.setBackground(fieldSurface());
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
                    TEXT_MUTED, false));
            listContainer.addView(empty, matchWrap());
            return;
        }
        for (Item item : items) {
            LinearLayout card = card();
            TextView tag = text("TASK".equals(item.type) ? "待办" : "笔记", 12,
                    "TASK".equals(item.type) ? CYAN : Color.rgb(157, 139, 255), true);
            card.addView(tag);
            TextView title = text((item.done ? "✓ " : "") + item.title, 17,
                    item.done ? Color.GRAY : TEXT, true);
            card.addView(title);
            if ("TASK".equals(item.type) && item.dueAt > 0) {
                String repeatText = "DAILY".equals(item.repeatType) ? " · 每天" :
                        "WEEKLY".equals(item.repeatType) ? " · 每周" : "";
                card.addView(text(formatTime(item.dueAt) + repeatText, 12,
                        TEXT_MUTED, false));
            }
            if (!item.content.isEmpty()) {
                TextView content = text(item.content, 14, TEXT_MUTED, false);
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
        boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(String.format(Locale.CHINA, "悬浮窗：%s　通知：%s　麦克风：%s%s",
                overlay ? "已允许" : "未允许",
                notifications ? "已允许" : "未允许",
                microphone ? "已允许" : "未允许",
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
        layout.setPadding(dp(17), dp(16), dp(17), dp(16));
        GradientDrawable surface = rounded(Color.argb(246, 8, 21, 38), 16);
        surface.setStroke(dp(1), Color.argb(95, 78, 142, 170));
        layout.setBackground(surface);
        return layout;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 17, TEXT, true);
        view.setCompoundDrawablePadding(dp(8));
        view.setPadding(dp(2), dp(20), 0, dp(10));
        view.setLetterSpacing(0.03f);
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
        edit.setHintTextColor(Color.rgb(126, 158, 184));
        edit.setSingleLine(false);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setTextSize(14);
        edit.setPadding(dp(14), dp(11), dp(14), dp(11));
        edit.setMinHeight(dp(52));
        edit.setBackground(fieldSurface());
        return edit;
    }

    private Button primaryButton(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.rgb(2, 18, 31));
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        GradientDrawable content = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{CYAN, Color.rgb(99, 207, 255)});
        content.setCornerRadius(dp(14));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), content, null));
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setStateListAnimator(null);
        if (listener != null) button.setOnClickListener(listener);
        return button;
    }

    private Button secondaryButton(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(TEXT);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        GradientDrawable content = rounded(Color.argb(235, 13, 47, 80), 14);
        content.setStroke(dp(1), Color.argb(170, 63, 191, 224));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(70, 50, 229, 242)), content, null));
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        if (listener != null) button.setOnClickListener(listener);
        return button;
    }

    private Button outlineButton(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(TEXT);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        GradientDrawable bg = rounded(Color.argb(198, 7, 28, 52), 12);
        bg.setStroke(dp(1), LINE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(65, 50, 229, 242)), bg, null));
        button.setMinHeight(dp(48));
        button.setStateListAnimator(null);
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

    private GradientDrawable fieldSurface() {
        GradientDrawable drawable = rounded(Color.argb(230, 5, 24, 47), 13);
        drawable.setStroke(dp(1), Color.argb(135, 73, 166, 201));
        return drawable;
    }

    private ArrayAdapter<String> darkSpinnerAdapter(String[] values) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                styleSpinnerText(view, false);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                styleSpinnerText(view, true);
                return view;
            }
        };
    }

    private void styleSpinnerText(TextView view, boolean dropdown) {
        view.setTextColor(TEXT);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (dropdown) view.setBackgroundColor(SURFACE_HIGH);
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

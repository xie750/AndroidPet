package com.example.desktoppet.feature.voice;

import android.content.Context;
import android.content.Intent;

import com.example.desktoppet.data.AppDatabase;
import com.example.desktoppet.feature.pet.PetOverlayService;
import com.example.desktoppet.feature.pet.PetView;
import com.example.desktoppet.feature.reminder.ReminderScheduler;
import com.example.desktoppet.model.Item;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VoiceCommandProcessor {
    static final class Result {
        final String reply;
        final boolean stopVoice;
        final PetView.Action action;

        Result(String reply, boolean stopVoice, PetView.Action action) {
            this.reply = reply;
            this.stopVoice = stopVoice;
            this.action = action;
        }
    }

    private final Context context;
    private final AppDatabase db;

    VoiceCommandProcessor(Context context) {
        this.context = context.getApplicationContext();
        this.db = new AppDatabase(this.context);
    }

    Result process(String original) {
        String text = normalize(original);
        if (text.isEmpty()) return unknown();

        if (containsAny(text, "停止语音", "关闭语音", "别听了", "停止监听")) {
            return new Result("好的，语音监听已关闭", true, PetView.Action.IDLE);
        }

        if (containsAny(text, "隐藏桌宠", "你去睡觉", "先睡觉", "隐藏费雾")) {
            context.startService(new Intent(context, PetOverlayService.class)
                    .setAction(PetOverlayService.ACTION_STOP));
            return new Result("好，我先休息，需要时再叫我", false, PetView.Action.IDLE);
        }

        if (containsAny(text, "今天有什么待办", "今天的待办", "今日待办", "还有什么事")) {
            return todayTasks();
        }

        if (containsAny(text, "记个笔记", "添加笔记", "记一下", "帮我记住")) {
            String content = stripPrefix(text,
                    "帮我记个笔记", "记个笔记", "添加笔记", "帮我记一下", "记一下", "帮我记住");
            if (content.isEmpty()) return new Result("要记什么内容呢？", false, PetView.Action.LISTEN);
            db.insert("NOTE", makeTitle(content), content, 0L, "NONE");
            return new Result("记好了：" + shorten(content), false, PetView.Action.CELEBRATE);
        }

        if (containsAny(text, "提醒我", "添加待办", "添加一个待办", "新建待办", "加个待办", "记个待办", "帮我安排")) {
            String content = stripPrefix(text,
                    "语音帮我", "帮我提醒我", "提醒我", "帮我添加一个待办", "添加一个待办",
                    "帮我添加待办", "添加待办", "新建待办",
                    "加个待办", "记个待办", "帮我安排");
            if (content.isEmpty()) return new Result("待办内容是什么？", false, PetView.Action.LISTEN);
            long dueAt = parseDueAt(content);
            String title = cleanTaskTitle(content);
            if (title.isEmpty()) title = content;
            long id = db.insert("TASK", title, "语音创建：" + content, dueAt, "NONE");
            ReminderScheduler.schedule(context, id, dueAt);
            String time = new SimpleDateFormat("MM月dd日 HH点mm分", Locale.CHINA)
                    .format(new Date(dueAt));
            return new Result("已添加待办，" + time + "提醒你" + shorten(title),
                    false, PetView.Action.CELEBRATE);
        }

        return unknown();
    }

    void close() {
        db.close();
    }

    private Result todayTasks() {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_YEAR, 1);
        List<String> titles = new ArrayList<>();
        for (Item item : db.getPendingTasks()) {
            if (item.dueAt >= start.getTimeInMillis() && item.dueAt < end.getTimeInMillis()) {
                titles.add(item.title);
            }
        }
        if (titles.isEmpty()) {
            return new Result("今天没有未完成的定时待办", false, PetView.Action.TAP);
        }
        StringBuilder reply = new StringBuilder("今天有").append(titles.size()).append("项：");
        for (int i = 0; i < Math.min(3, titles.size()); i++) {
            if (i > 0) reply.append("；");
            reply.append(titles.get(i));
        }
        return new Result(reply.toString(), false, PetView.Action.REMIND);
    }

    private long parseDueAt(String text) {
        long now = System.currentTimeMillis();
        Matcher relative = Pattern.compile("([零〇一二两三四五六七八九十百\\d]+)\\s*(分钟|小时)后")
                .matcher(text);
        if (relative.find()) {
            int amount = parseChineseNumber(relative.group(1));
            long unit = "小时".equals(relative.group(2)) ? 60L * 60L * 1000L : 60L * 1000L;
            return now + Math.max(1, amount) * unit;
        }
        if (text.contains("半小时后")) return now + 30L * 60L * 1000L;

        Calendar calendar = Calendar.getInstance();
        if (text.contains("后天")) calendar.add(Calendar.DAY_OF_YEAR, 2);
        else if (text.contains("明天")) calendar.add(Calendar.DAY_OF_YEAR, 1);

        Matcher clock = Pattern.compile("([零〇一二两三四五六七八九十\\d]{1,3})点(?:(半)|([零〇一二两三四五六七八九十\\d]{1,3})分?)?")
                .matcher(text);
        boolean foundClock = clock.find();
        if (foundClock) {
            int hour = parseChineseNumber(clock.group(1));
            int minute = clock.group(2) != null ? 30 :
                    clock.group(3) == null ? 0 : parseChineseNumber(clock.group(3));
            if ((text.contains("下午") || text.contains("晚上")) && hour < 12) hour += 12;
            if (text.contains("中午") && hour < 11) hour += 12;
            if (text.contains("凌晨") && hour == 12) hour = 0;
            calendar.set(Calendar.HOUR_OF_DAY, Math.min(23, Math.max(0, hour)));
            calendar.set(Calendar.MINUTE, Math.min(59, Math.max(0, minute)));
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            if (!containsAny(text, "今天", "明天", "后天") && calendar.getTimeInMillis() <= now) {
                calendar.add(Calendar.DAY_OF_YEAR, 1);
            }
            return calendar.getTimeInMillis();
        }

        return now + 60L * 60L * 1000L;
    }

    private String cleanTaskTitle(String text) {
        return text
                .replaceAll("(今天|明天|后天|上午|中午|下午|晚上|凌晨)", "")
                .replaceAll("[零〇一二两三四五六七八九十\\d]{1,3}点(半|[零〇一二两三四五六七八九十\\d]{1,3}分?)?", "")
                .replaceAll("([零〇一二两三四五六七八九十百\\d]+)(分钟|小时)后", "")
                .replace("的时候", "")
                .replace("帮我", "")
                .trim();
    }

    private int parseChineseNumber(String value) {
        if (value == null || value.isEmpty()) return 0;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) {}
        value = value.replace('两', '二').replace('〇', '零');
        if ("十".equals(value)) return 10;
        int tenIndex = value.indexOf('十');
        if (tenIndex >= 0) {
            int tens = tenIndex == 0 ? 1 : digit(value.charAt(tenIndex - 1));
            int ones = tenIndex == value.length() - 1 ? 0 : digit(value.charAt(tenIndex + 1));
            return tens * 10 + ones;
        }
        int result = 0;
        for (int i = 0; i < value.length(); i++) result = result * 10 + digit(value.charAt(i));
        return result;
    }

    private int digit(char c) {
        String digits = "零一二三四五六七八九";
        int index = digits.indexOf(c);
        return Math.max(0, index);
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.CHINA)
                .replace("代办", "待办")
                .replace("飞舞", "费雾")
                .replace("飞雾", "费雾")
                .replace("废物", "费雾")
                .replaceAll("[，。！？,.!? ]", "")
                .replace("你好费雾", "")
                .replace("费雾费雾", "")
                .replace("费雾", "")
                .trim();
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private String stripPrefix(String value, String... prefixes) {
        String result = value;
        for (String prefix : prefixes) result = result.replace(prefix, "");
        return result.trim();
    }

    private String makeTitle(String content) {
        return content.length() <= 12 ? content : content.substring(0, 12) + "…";
    }

    private String shorten(String content) {
        return content.length() <= 18 ? content : content.substring(0, 18) + "…";
    }

    private Result unknown() {
        return new Result("我没听懂。可以说：提醒我明天下午三点交水费",
                false, PetView.Action.THINK);
    }
}

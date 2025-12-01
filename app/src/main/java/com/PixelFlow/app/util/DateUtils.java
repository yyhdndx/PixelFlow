package com.PixelFlow.app.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class DateUtils {

    /**
     * 将秒级时间戳格式化为相对时间
     * @param timestampSeconds 秒级时间戳
     * @return 格式化后的时间字符串
     */
    public static String formatRelativeTime(long timestampSeconds) {
        // 将秒转换为毫秒
        long timestampMillis = timestampSeconds * 1000L;
        long now = System.currentTimeMillis();
        long diff = now - timestampMillis;

        // 时间常量
        long oneSecond = 1000L;
        long oneMinute = 60 * oneSecond;
        long oneHour = 60 * oneMinute;
        long oneDay = 24 * oneHour;
        long sevenDays = 7 * oneDay;

        // 创建日期对象
        Date date = new Date(timestampMillis);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);

        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DATE, -1);

        if (diff < oneMinute) {
            // 刚刚
            return "刚刚";
        } else if (diff < oneHour) {
            // X分钟前
            long minutes = diff / oneMinute;
            return minutes + "分钟前";
        } else if (diff < oneDay) {
            // X小时前
            long hours = diff / oneHour;
            return hours + "小时前";
        } else if (isSameDay(calendar, today)) {
            // 今天 HH:mm
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            return "今天 " + sdf.format(date);
        } else if (isSameDay(calendar, yesterday)) {
            // 昨天 HH:mm
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            return "昨天 " + sdf.format(date);
        } else if (diff < sevenDays) {
            // X天前
            long days = diff / oneDay;
            return days + "天前";
        } else {
            // MM-dd
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd", Locale.getDefault());
            return sdf.format(date);
        }
    }

    /**
     * 将秒级时间戳格式化为详细时间
     * @param timestampSeconds 秒级时间戳
     * @return 详细时间字符串
     */
    public static String formatDetailTime(long timestampSeconds) {
        long timestampMillis = timestampSeconds * 1000L;
        Date date = new Date(timestampMillis);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(date);
    }

    /**
     * 判断两个Calendar是否在同一天
     */
    private static boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH) &&
                cal1.get(Calendar.DAY_OF_MONTH) == cal2.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * 获取年份
     */
    public static int getYear(long timestampSeconds) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestampSeconds * 1000L);
        return calendar.get(Calendar.YEAR);
    }

    /**
     * 获取月份（1-12）
     */
    public static int getMonth(long timestampSeconds) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestampSeconds * 1000L);
        return calendar.get(Calendar.MONTH) + 1; // Calendar.MONTH 从0开始
    }

    /**
     * 获取日期（1-31）
     */
    public static int getDay(long timestampSeconds) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestampSeconds * 1000L);
        return calendar.get(Calendar.DAY_OF_MONTH);
    }
}
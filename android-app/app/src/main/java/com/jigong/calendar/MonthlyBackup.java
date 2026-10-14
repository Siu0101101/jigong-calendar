package com.jigong.calendar;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class MonthlyBackup {
    private static final String PREFS = "monthly_backup";
    private static final String FOLDER = "folder";
    private static final String SNAPSHOT = "snapshot";
    private static final String LAST_MONTH = "last_month";
    private static final String LAST_ERROR = "last_error";
    private static final int JOB_ID = 1107;

    private MonthlyBackup() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return !prefs(context).getString(FOLDER, "").isEmpty();
    }

    static void saveSnapshot(Context context, String json) {
        if (json != null && json.length() < 8_000_000) {
            prefs(context).edit().putString(SNAPSHOT, json).apply();
        }
    }

    static void selectFolder(Context context, Uri folder) {
        prefs(context).edit().putString(FOLDER, folder.toString()).putString(LAST_MONTH, "").remove(LAST_ERROR).apply();
        schedule(context);
    }

    static void schedule(Context context) {
        if (!isEnabled(context)) return;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, MonthlyBackupJob.class))
                .setPeriodic(24L * 60 * 60 * 1000)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }

    static synchronized boolean backupIfDue(Context context) {
        SharedPreferences state = prefs(context);
        String folder = state.getString(FOLDER, "");
        String json = state.getString(SNAPSHOT, "");
        if (folder.isEmpty() || json.isEmpty()) return false;
        String month = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        if (month.equals(state.getString(LAST_MONTH, ""))) return true;
        Uri document = null;
        try {
            Uri tree = Uri.parse(folder);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            document = DocumentsContract.createDocument(context.getContentResolver(), parent,
                    "application/json", "记工日历-自动备份-" + month + ".json");
            if (document == null) throw new IllegalStateException("无法创建备份文件");
            try (OutputStream out = context.getContentResolver().openOutputStream(document, "w")) {
                if (out == null) throw new IllegalStateException("无法写入备份文件");
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            state.edit().putString(LAST_MONTH, month).remove(LAST_ERROR).apply();
            return true;
        } catch (Exception error) {
            if (document != null) {
                try { DocumentsContract.deleteDocument(context.getContentResolver(), document); }
                catch (Exception ignored) {}
            }
            state.edit().putString(LAST_ERROR, "备份失败，请重新选择文件夹或手动导出").apply();
            return false;
        }
    }

    static String status(Context context) {
        SharedPreferences state = prefs(context);
        if (!isEnabled(context)) return "未开启。请先选择一个备份文件夹。";
        String error = state.getString(LAST_ERROR, "");
        String month = state.getString(LAST_MONTH, "");
        if (!error.isEmpty()) return error;
        return month.isEmpty() ? "已开启，正在准备第一份备份。" : "已开启 · 最近成功备份：" + month;
    }
}


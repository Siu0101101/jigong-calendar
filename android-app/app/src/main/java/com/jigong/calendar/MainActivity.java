package com.jigong.calendar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int SAVE_DOCUMENT = 701;
    private static final int PICK_DOCUMENT = 702;
    private static final int PICK_BACKUP_FOLDER = 703;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingExport;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(244, 247, 242));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(244, 247, 242));
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new AppBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"appassets.androidplatform.net".equals(uri.getHost()) || !"https".equals(uri.getScheme())) return null;
                String path = uri.getPath();
                if (path == null || !path.startsWith("/assets/")) return missing();
                String name = path.substring("/assets/".length());
                if (name.isEmpty() || name.contains("..") || name.contains("\\") || name.contains("/")) return missing();
                try { return new WebResourceResponse(mime(name), "UTF-8", getAssets().open(name)); }
                catch (FileNotFoundException ex) { return missing(); }
                catch (Exception ex) { return missing(); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith(ORIGIN + "/assets/");
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("确定", (dialog, which) -> result.confirm()).setNegativeButton("取消", (dialog, which) -> result.cancel()).setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), PICK_DOCUMENT); return true; }
                catch (Exception ex) { fileCallback = null; callback.onReceiveValue(null); return false; }
            }
        });
        MonthlyBackup.schedule(this);
        webView.loadUrl(ORIGIN + "/assets/index.html");
    }

    private WebResourceResponse missing() { return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0])); }
    private String mime(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".json") || name.endsWith(".webmanifest")) return "application/json";
        return "application/octet-stream";
    }

    public class AppBridge {
        @JavascriptInterface public void syncBackup(String json) {
            MonthlyBackup.saveSnapshot(getApplicationContext(), json);
            if (!MonthlyBackup.isEnabled(getApplicationContext())) return;
            new Thread(() -> {
                MonthlyBackup.backupIfDue(getApplicationContext());
                runOnUiThread(() -> webView.evaluateJavascript("window.onAutoBackupChanged && window.onAutoBackupChanged()", null));
            }, "backup-after-save").start();
        }

        @JavascriptInterface public String getBackupStatus() {
            return MonthlyBackup.status(getApplicationContext());
        }

        @JavascriptInterface public void chooseBackupFolder() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { startActivityForResult(intent, PICK_BACKUP_FOLDER); }
                catch (Exception ex) {
                    new AlertDialog.Builder(MainActivity.this).setMessage("无法打开文件夹选择界面").setPositiveButton("确定", null).show();
                }
            });
        }

        @JavascriptInterface public void saveText(String filename, String contents, String mimeType) {
            runOnUiThread(() -> {
                pendingExport = contents.getBytes(StandardCharsets.UTF_8);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mimeType.split(";", 2)[0]);
                intent.putExtra(Intent.EXTRA_TITLE, filename);
                try { startActivityForResult(intent, SAVE_DOCUMENT); }
                catch (Exception ex) { pendingExport = null; new AlertDialog.Builder(MainActivity.this).setMessage("无法打开手机的文件保存界面").setPositiveButton("确定", null).show(); }
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_BACKUP_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException("文件夹没有写入权限");
                    getContentResolver().takePersistableUriPermission(data.getData(), flags);
                    MonthlyBackup.selectFolder(this, data.getData());
                    new Thread(() -> {
                        MonthlyBackup.backupIfDue(getApplicationContext());
                        runOnUiThread(() -> webView.evaluateJavascript("window.onAutoBackupChanged && window.onAutoBackupChanged()", null));
                    }, "backup-first-copy").start();
                } catch (Exception ex) {
                    new AlertDialog.Builder(this).setMessage("无法使用这个文件夹，请换一个位置重试").setPositiveButton("确定", null).show();
                }
            }
        } else if (requestCode == PICK_DOCUMENT) {
            if (fileCallback != null) { fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCallback = null; }
        } else if (requestCode == SAVE_DOCUMENT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    if (output != null) output.write(pendingExport);
                } catch (Exception ex) {
                    new AlertDialog.Builder(this).setMessage("保存失败，请换一个位置重试").setPositiveButton("确定", null).show();
                }
            }
            pendingExport = null;
        }
    }

    @Override public void onBackPressed() {
        webView.evaluateJavascript("(function(){var x=document.querySelector('.overlay.open [data-close]');if(x){x.click();return true}return false})()", result -> { if (!"true".equals(result)) new AlertDialog.Builder(this).setMessage("退出记工日历吗？").setPositiveButton("退出", (dialog, which) -> finish()).setNegativeButton("继续使用", null).show(); });
    }

    @Override protected void onDestroy() { if (webView != null) webView.destroy(); super.onDestroy(); }
}


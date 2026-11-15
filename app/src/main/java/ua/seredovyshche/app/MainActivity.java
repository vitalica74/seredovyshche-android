package ua.seredovyshche.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.JavaScriptReplyProxy;
import java.io.OutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String HOME = "https://appassets.androidplatform.net/assets/web/index.html";
    private static final int IMPORT_CODE = 41, EXPORT_CODE = 42;
    private WebView web;
    private ValueCallback<Uri[]> uploadCallback;
    private byte[] pendingExport;
    private String pendingProjectId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setOnApplyWindowInsetsListener(
            (view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                );
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            }
        );
        web = new WebView(this);
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return !request.getUrl().toString().startsWith("https://appassets.androidplatform.net/assets/web/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = callback;
                try {
                    Intent i = params.createIntent();
                    startActivityForResult(i, IMPORT_CODE);
                    return true;
                } catch (Exception e) {
                    uploadCallback = null;
                    return false;
                }
            }
        });
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "SeredovyshcheAndroid",
                    Collections.singleton("https://appassets.androidplatform.net"),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (!isMainFrame || message.getData() == null) return;
                        try {
                            JSONObject data = new JSONObject(message.getData());
                            if (!"export".equals(data.optString("type"))) return;
                            String filename = data.optString("filename", "project.zip");
                            if (!filename.matches("[a-zA-Zа-яА-ЯіІїЇєЄґҐ0-9 _().-]{1,120}\\.(zip|json)")) filename = "project.zip";
                            byte[] bytes = Base64.decode(data.getString("base64"), Base64.DEFAULT);
                            if (bytes.length > 10_000_000) throw new IllegalArgumentException("File too large");
                            final String exportFilename = filename;
                            runOnUiThread(() -> {
                                pendingExport = bytes;
                                pendingProjectId = data.optString("projectId", "");
                                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                i.addCategory(Intent.CATEGORY_OPENABLE);
                                i.setType(exportFilename.endsWith(".json") ? "application/json" : "application/zip");
                                i.putExtra(Intent.EXTRA_TITLE, exportFilename);
                                try {
                                    startActivityForResult(i, EXPORT_CODE);
                                } catch (Exception ex) {
                                    pendingExport = null;
                                    Toast.makeText(this, "Не вдалося відкрити вибір файлу", Toast.LENGTH_LONG).show();
                                }
                            });
                        } catch (Exception e) { Toast.makeText(this, "Помилка експорту: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                    });
        }
        web.loadUrl(HOME);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMPORT_CODE) {
            if (uploadCallback != null) {
                uploadCallback.onReceiveValue(resultCode == RESULT_OK && data != null && data.getData() != null ? new Uri[]{data.getData()} : null);
                uploadCallback = null;
            }
        } else if (requestCode == EXPORT_CODE) {
            boolean verified = false;
            String reason = "Експорт скасовано";
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
                Uri uri = data.getData();
                try {
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new Exception("No output stream");
                        out.write(pendingExport);
                        out.flush();
                    }
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] expected = digest.digest(pendingExport);
                    digest.reset();
                    int count = 0;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new Exception("Readback unavailable");
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            digest.update(buffer, 0, n);
                            count += n;
                        }
                    }
                    verified = count == pendingExport.length && Arrays.equals(expected, digest.digest());
                    if (!verified) throw new Exception("Checksum mismatch");
                    Toast.makeText(this, "ZIP записано та перевірено", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    reason = e.getMessage();
                    Toast.makeText(this, "Не вдалося перевірити копію", Toast.LENGTH_LONG).show();
                }
            }
            if (pendingProjectId != null && !pendingProjectId.isEmpty()) {
                if (verified) {
                    String escapedId = JSONObject.quote(pendingProjectId);
                    web.evaluateJavascript("window.seredovyshcheBackupVerified("+escapedId+")", null);
                } else {
                    web.evaluateJavascript("window.seredovyshcheBackupFailed("+JSONObject.quote(reason)+")", null);
                }
            }
            pendingExport = null;
            pendingProjectId = null;
        }
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}

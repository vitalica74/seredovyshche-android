package ua.seredovyshche.app;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
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
import java.util.Map;
import java.util.HashMap;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import android.graphics.Color;
import android.util.TypedValue;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import org.json.JSONArray;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String HOME = "https://appassets.androidplatform.net/assets/web/index.html";
    private static final int IMPORT_CODE = 41, EXPORT_CODE = 42, RUNTIME_EXPORT_CODE = 43;
    private WebView web;
    private WebView runtime;
    private FrameLayout root;
    private LinearLayout runtimeShell;
    private int runtimeInitialHistoryLength = 1;
    private final Map<String, byte[]> runtimeFiles = new HashMap<>();
    private String runtimeHost = "";
    private String runtimeProjectId = "";
    private LinearLayout runtimePanel;
    private SharedPreferences sessionPrefs;
    private ValueCallback<Uri[]> uploadCallback;
    private byte[] pendingExport;
    private String pendingProjectId;
    private byte[] pendingRuntimeExport;
    private String pendingRuntimeFilename;

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
        sessionPrefs = getSharedPreferences("runtime_sessions_v1", MODE_PRIVATE);
        root = new FrameLayout(this);
        setContentView(root);
        web = new WebView(this);
        root.addView(web);
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
                Uri uri = request.getUrl();
                if (uri.toString().startsWith("https://appassets.androidplatform.net/assets/web/")) return false;
                if (request.isForMainFrame() && "https".equals(uri.getScheme())) openExternal(uri.toString());
                return true;
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
                            if ("launch".equals(data.optString("type"))) {
                                String projectId = data.optString("projectId", "");
                                JSONObject files = data.getJSONObject("files");
                                if (!projectId.matches("[0-9a-fA-F-]{36}") || files.length() > 300) return;
                                Map<String, byte[]> prepared = new HashMap<>();
                                int total = 0;
                                java.util.Iterator<String> keys = files.keys();
                                while (keys.hasNext()) {
                                    String path = keys.next();
                                    if (!safePath(path)) throw new IllegalArgumentException("Unsafe path");
                                    String value = files.getString(path);
                                    byte[] bytes = value.startsWith("__SEREDOVYSHCHE_BASE64__:")
                                        ? Base64.decode(value.substring("__SEREDOVYSHCHE_BASE64__:".length()), Base64.DEFAULT)
                                        : value.getBytes(StandardCharsets.UTF_8);
                                    total += bytes.length;
                                    if (total > 3_000_000) throw new IllegalArgumentException("Project too large");
                                    prepared.put(path, bytes);
                                }
                                if (!prepared.containsKey("index.html")) throw new IllegalArgumentException("No index.html");
                                String host = "p" + projectId.replace("-", "").toLowerCase() + ".seredovyshche.invalid";
                                runOnUiThread(() -> launchRuntime(projectId, host, prepared));
                                return;
                            }
                            if ("openExternal".equals(data.optString("type"))) {
                                String url = data.optString("url", "");
                                if (url.startsWith("https://") && url.length() < 2048)
                                    runOnUiThread(() -> openExternal(url));
                                return;
                            }
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
        // External links with target=_blank do not navigate the current WebView.
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript("(function(){document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[href]');if(!a)return;var u;try{u=new URL(a.href,location.href)}catch(_){return}if(u.protocol==='https:'&&u.origin!==location.origin){e.preventDefault();window.SeredovyshcheAndroid&&window.SeredovyshcheAndroid.postMessage(JSON.stringify({type:'openExternal',url:u.href}))}},true)})()",null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri uri=r.getUrl();
                if(uri.toString().startsWith("https://appassets.androidplatform.net/assets/web/"))return false;
                if(r.isForMainFrame()&&"https".equals(uri.getScheme()))openExternal(uri.toString());
                return true;
            }
        });
        web.loadUrl(HOME);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMPORT_CODE) {
            if (uploadCallback != null) {
                uploadCallback.onReceiveValue(resultCode == RESULT_OK && data != null && data.getData() != null ? new Uri[]{data.getData()} : null);
                uploadCallback = null;
            }
        } else if (requestCode == RUNTIME_EXPORT_CODE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingRuntimeExport != null) {
                try {
                    Uri uri = data.getData();
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new Exception("Немає доступу до файлу");
                        out.write(pendingRuntimeExport);
                        out.flush();
                    }
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] expected = digest.digest(pendingRuntimeExport);
                    digest.reset(); int count = 0;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new Exception("Неможливо прочитати файл для перевірки");
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) != -1) { digest.update(buf, 0, n); count += n; }
                    }
                    if (count != pendingRuntimeExport.length || !Arrays.equals(expected, digest.digest()))
                        throw new Exception("Контрольна сума не збігається");
                    Toast.makeText(this, "Файл збережено та перевірено", Toast.LENGTH_LONG).show();
                } catch (Exception ex) {
                    Toast.makeText(this, "Помилка збереження: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else if (resultCode != RESULT_OK) {
                Toast.makeText(this, "Збереження скасовано", Toast.LENGTH_SHORT).show();
            }
            pendingRuntimeExport = null;
            pendingRuntimeFilename = null;
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

    private void openExternal(String url) {
        try {
            Uri uri = Uri.parse(url);
            if (!"https".equals(uri.getScheme())) return;
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (Exception e) {
            Toast.makeText(this, "Не вдалося відкрити посилання", Toast.LENGTH_LONG).show();
        }
    }
    private void saveRuntimeFile(String name, String encoded) {
        try {
            if (pendingRuntimeExport != null) {
                Toast.makeText(this, "Спочатку завершіть попереднє збереження", Toast.LENGTH_LONG).show();
                return;
            }
            if (encoded.length() > 16_000_000) throw new Exception("Файл завеликий");
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            if (bytes.length > 12_000_000) throw new Exception("Файл завеликий");
            String filename = name.replaceAll("[^a-zA-Zа-яА-ЯіІїЇєЄґҐ0-9 _().-]", "_");
            if (filename.isEmpty() || filename.length() > 120) filename = "export.json";
            pendingRuntimeExport = bytes;
            pendingRuntimeFilename = filename;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(filename.endsWith(".json") ? "application/json" : "application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, filename);
            startActivityForResult(intent, RUNTIME_EXPORT_CODE);
        } catch (Exception ex) {
            pendingRuntimeExport = null;
            Toast.makeText(this, "Помилка експорту: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    private boolean safePath(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("\u0000")) return false;
        for (String part : path.split("/")) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
    private String mime(String path) {
        String name = path.toLowerCase();
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "text/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".json") || name.endsWith(".webmanifest")) return "application/json";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }
    private static final String RUNTIME_DOWNLOAD_SCRIPT =
        "(function(){"  +
        "if(window.__svFileBridgeV2)return;"  +
        "window.__svFileBridgeV2=true;"  +
        "function notifyError(msg){"  +
        " try{window.SeredovyshcheFiles&&window.SeredovyshcheFiles.postMessage(JSON.stringify({type:'error',message:String(msg)}))}catch(_){}"  +
        "}"  +
        "function saveLink(a){"  +
        " if(!a||!a.hasAttribute('download'))return false;"  +
        " var url=a.href;"  +
        " if(!url||!(url.indexOf('blob:')===0||url.indexOf('data:')===0))return false;"  +
        " try{"  +
        "  fetch(url).then(function(r){if(!r.ok)throw Error('Не вдалося прочитати Blob');return r.blob()})"  +
        "  .then(function(blob){"  +
        "   var reader=new FileReader();"  +
        "   reader.onerror=function(){notifyError('Не вдалося прочитати файл')};"  +
        "   reader.onload=function(){"  +
        "    var result=String(reader.result||'');var comma=result.indexOf(',');"  +
        "    if(comma<0){notifyError('Неправильний формат файлу');return}"  +
        "    if(!window.SeredovyshcheFiles){notifyError('Файловий міст недоступний');return}"  +
        "    window.SeredovyshcheFiles.postMessage(JSON.stringify({type:'save',filename:a.download||'export.json',base64:result.substring(comma+1)}));"  +
        "   };"  +
        "   reader.readAsDataURL(blob);"  +
        "  }).catch(function(err){notifyError(err&&err.message||err)});"  +
        " }catch(err){notifyError(err&&err.message||err)}"  +
        " return true;"  +
        "}"  +
        "var original=HTMLAnchorElement.prototype.click;"  +
        "HTMLAnchorElement.prototype.click=function(){"  +
        " if(saveLink(this))return;"  +
        " return original.apply(this,arguments);"  +
        "};"  +
        "document.addEventListener('click',function(e){"  +
        " var a=e.target&&e.target.closest&&e.target.closest('a[download]');"  +
        " if(a&&saveLink(a)){e.preventDefault();e.stopImmediatePropagation()}"  +
        "},true);"  +
        "})()" ;
    private void launchRuntime(String projectId, String host, Map<String, byte[]> files) {
        // Keep the existing WebView alive when the same program is reopened.
        // This preserves SPA state, unfinished forms and scroll position in memory.
        if (runtime != null && host.equals(runtimeHost)) {
            showRuntime();
            return;
        }
        closeRuntime();
        runtimeHost = host;
        runtimeInitialHistoryLength = 1;
        runtimeProjectId = projectId;
        runtimeFiles.clear(); runtimeFiles.putAll(files);
        runtime = new WebView(this);
        WebSettings s = runtime.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        runtime.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), IMPORT_CODE);
                    return true;
                } catch (Exception e) {
                    uploadCallback = null;
                    Toast.makeText(MainActivity.this, "Не вдалося відкрити вибір файлу", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });
        // A message listener is restricted to the selected PWA's HTTPS origin.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(runtime, "SeredovyshcheFiles",
                Collections.singleton("https://" + host),
                (view, message, origin, mainFrame, reply) -> {
                    if (!mainFrame || message.getData() == null || view != runtime) return;
                    try {
                        JSONObject obj = new JSONObject(message.getData());
                        if ("error".equals(obj.optString("type"))) {
                            String exportErrorMessage = obj.optString("message", "Невідома помилка");
                            runOnUiThread(() -> Toast.makeText(this, "Помилка експорту: " + exportErrorMessage, Toast.LENGTH_LONG).show());
                            return;
                        }
                        if (!"save".equals(obj.optString("type"))) return;
                        String filename = obj.optString("filename", "export.json");
                        String b64 = obj.getString("base64");
                        runOnUiThread(() -> saveRuntimeFile(filename, b64));
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "Помилка файлового мосту", Toast.LENGTH_LONG).show());
                    }
                });
        }
        runtimePanel = new LinearLayout(this);
        runtimePanel.setOrientation(LinearLayout.VERTICAL);
        runtimePanel.setBackgroundColor(Color.rgb(18, 25, 40));
        TextView homeButton = new TextView(this);
        homeButton.setText("‹  Середовище");
        homeButton.setTextColor(Color.WHITE);
        homeButton.setTextSize(13);
        homeButton.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = (int)(12 * getResources().getDisplayMetrics().density);
        homeButton.setPadding(pad, 0, pad, 0);
        homeButton.setContentDescription("Повернутися до Середовища");
        homeButton.setOnClickListener(v -> closeRuntime());
        runtimePanel.addView(homeButton, new LinearLayout.LayoutParams(-1, (int)(34 * getResources().getDisplayMetrics().density)));
        runtime.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                // Capture downloads created by a PWA via Blob URL + anchor.click().
                // FileReader reads the Blob inside the PWA origin; Android saves via SAF.
                view.evaluateJavascript(RUNTIME_DOWNLOAD_SCRIPT, null);
                if (url.equals("https://" + host + "/index.html")) {
                    view.evaluateJavascript("String(history.length)", value -> {
                        try { runtimeInitialHistoryLength = Math.max(1, Integer.parseInt(value.replace("\"", ""))); }
                        catch (Exception ignored) { }
                    });
                }
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!"https".equals(u.getScheme()) || !host.equals(u.getHost()))
                    return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
                String path = u.getEncodedPath();
                try { path = URLDecoder.decode(path.startsWith("/") ? path.substring(1) : path, "UTF-8"); }
                catch (Exception e) { return null; }
                if (path.isEmpty()) path = "index.html";
                if (!safePath(path)) return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
                byte[] data = runtimeFiles.get(path);
                if (data == null) return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
                Map<String,String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-store");
                headers.put("Access-Control-Allow-Origin", "https://" + host);
                return new WebResourceResponse(mime(path), "UTF-8", 200, "OK", headers, new ByteArrayInputStream(data));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                return !"https".equals(u.getScheme()) || !host.equals(u.getHost());
            }
        });
        runtimeShell = new LinearLayout(this);
        runtimeShell.setOrientation(LinearLayout.VERTICAL);
        runtimeShell.setBackgroundColor(Color.rgb(15, 23, 42));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.rgb(25, 35, 53));
        TextView exit = new TextView(this);
        exit.setText("‹  Середовище");
        exit.setTextColor(Color.WHITE);
        exit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        exit.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int exitPad = (int) (12 * getResources().getDisplayMetrics().density);
        exit.setPadding(exitPad, 0, exitPad, 0);
        exit.setContentDescription("Повернутися до Середовища");
        exit.setOnClickListener(v -> hideRuntime());
        bar.addView(exit, new LinearLayout.LayoutParams(-2, (int)(42 * getResources().getDisplayMetrics().density)));
        runtimeShell.addView(bar);
        runtimeShell.addView(runtime, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(runtimeShell);
        showRuntime();
        runtime.loadUrl("https://" + host + "/index.html");

    }
    private void showRuntime() {
        if (runtimeShell != null) runtimeShell.setVisibility(View.VISIBLE);
        if (web != null) web.setVisibility(View.GONE);

    }
    private void hideRuntime() {
        if (runtimeShell != null) runtimeShell.setVisibility(View.GONE);
        if (web != null) web.setVisibility(View.VISIBLE);

    }
    private void closeRuntime() {
        if (runtime != null) {
            if (runtimeShell != null) root.removeView(runtimeShell);
            runtime.stopLoading(); runtime.destroy(); runtime = null;
            runtimeShell = null;
        }
        if (web != null) web.setVisibility(android.view.View.VISIBLE);
        runtimeFiles.clear(); runtimeHost = ""; runtimeProjectId = ""; 
    }
    @Override public void onBackPressed() {
        if (runtime != null && runtimeShell != null && runtimeShell.getVisibility() == View.VISIBLE) {
            // SPA navigation with pushState does not always appear in canGoBack().
            // history.length includes same-document history entries.
            runtime.evaluateJavascript("(function(){return history.length > " + runtimeInitialHistoryLength + " ? 'back' : 'home'})()", result -> {
                if (runtime == null || runtimeShell == null || runtimeShell.getVisibility() != View.VISIBLE) return;
                if ("\"back\"".equals(result)) runtime.evaluateJavascript("history.back()", null);
                else hideRuntime();
            });
            return;
        }
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        closeRuntime();
        web.destroy();
        super.onDestroy();
    }
}

package io.github.shinhnu98.lunchnearby;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Full-screen WebView running the bundled index.html, plus the native bits a web page can't do (background visit check, notifications). */
public class MainActivity extends Activity {
    /* The bundled page is served under the website's own address, so Google/Firebase key restrictions
       and sign-in domains that allow the website also allow the app. These requests never leave the phone. */
    static final String HOST = "shinhnu98.github.io", BASE = "/lunchnearby/";
    private static final int RQ_FILE = 1, RQ_GEO = 2, RQ_TRACK = 3;
    private static MainActivity live;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private Uri camUri;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;
    private boolean askedTrack;
    private volatile String lastTrack = "";

    /** Called by VisitService when a visit state changes: redraw the page so it picks it up. */
    static void refresh() {
        final MainActivity a = live;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                a.web.evaluateJavascript("window.render&&render()", null);
            }
        });
    }

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLoc() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        getWindow().setStatusBarColor(dark ? Color.rgb(0x1c, 0x17, 0x12) : Color.rgb(0xfa, 0xf6, 0xf0));
        getWindow().setNavigationBarColor(dark ? Color.rgb(0x27, 0x20, 0x19) : Color.WHITE);
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        if (b != null) web.restoreState(b);
        else web.loadUrl("https://" + HOST + BASE);
        live = this;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override protected void onResume() {
        super.onResume();
        lastTrack = ""; /* re-check the background service on every return */
        js("window.appShow&&appShow()");
        refresh();
    }

    @Override protected void onPause() {
        js("window.appHide&&appHide()");
        super.onPause();
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    /** Google's sign-in page in a Chrome Custom Tab (plain browser if Chrome isn't there) */
    void openTab(Uri u) {
        final Intent i = new Intent(Intent.ACTION_VIEW, u);
        Bundle b = new Bundle();
        b.putBinder("android.support.customtabs.extra.SESSION", null);
        i.putExtras(b);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    js("window.appLoginErr&&appLoginErr('no browser')");
                }
            }
        });
    }

    private void loginDone(final String token, final String err) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                /* bring the app back over the sign-in tab (singleTask clears the tab above us) */
                startActivity(new Intent(MainActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
                js(token != null ? "window.appLogin&&appLogin(" + JSONObject.quote(token) + ")"
                        : "window.appLoginErr&&appLoginErr(" + JSONObject.quote(err) + ")");
            }
        });
    }

    @Override protected void onDestroy() {
        if (live == this) live = null;
        web.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    private void openExternal(Uri u) {
        try {
            Intent i = "intent".equals(u.getScheme()) ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, u);
            if ("tel".equals(u.getScheme())) i = new Intent(Intent.ACTION_DIAL, u);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private static boolean ours(Uri u) {
        String p = u.getPath();
        return "https".equals(u.getScheme()) && HOST.equals(u.getHost()) && p != null && p.startsWith(BASE);
    }

    private class Client extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            String p = u.getPath();
            if (!ours(u) || p == null) return null;
            p = "/" + p.substring(BASE.length());
            if (p.equals("/")) p = "/index.html";
            String mime = p.endsWith(".html") ? "text/html" : p.endsWith(".json") ? "application/json" : p.endsWith(".js") ? "text/javascript" : p.endsWith(".png") ? "image/png" : "application/octet-stream";
            try {
                InputStream in = getAssets().open("www" + p);
                return new WebResourceResponse(mime, "utf-8", in);
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(), null);
            }
        }

        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!r.isForMainFrame() || ours(u)) return false;
            openExternal(u); /* maps, phone, websites → their own apps */
            return true;
        }
    }

    private class Chrome extends WebChromeClient {
        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            if (hasLoc()) {
                cb.invoke(origin, true, false);
                return;
            }
            geoCb = cb;
            geoOrigin = origin;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, RQ_GEO);
        }

        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            camUri = null;
            Intent i;
            if (p.isCaptureEnabled()) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, "lunch_" + System.currentTimeMillis() + ".jpg");
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                try {
                    camUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                } catch (Exception ignored) {
                }
                i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (camUri != null) i.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else {
                i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(i, RQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCb = null;
                return false;
            }
            return true;
        }
    }

    @Override protected void onActivityResult(int rq, int res, Intent data) {
        if (rq != RQ_FILE || fileCb == null) return;
        Uri[] out = null;
        if (res == RESULT_OK) {
            if (camUri != null) out = new Uri[]{camUri};
            else if (data != null) {
                ClipData cd = data.getClipData();
                if (cd != null) {
                    out = new Uri[cd.getItemCount()];
                    for (int i = 0; i < out.length; i++) out[i] = cd.getItemAt(i).getUri();
                } else if (data.getData() != null) out = new Uri[]{data.getData()};
            }
        } else if (camUri != null) {
            try {
                getContentResolver().delete(camUri, null, null);
            } catch (Exception ignored) {
            }
        }
        fileCb.onReceiveValue(out);
        fileCb = null;
        camUri = null;
    }

    /** Ask for what the background visit check needs, then start it. */
    private void startTracking() {
        List<String> need = new ArrayList<>();
        if (!hasLoc()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty() && !askedTrack) {
            askedTrack = true;
            requestPermissions(need.toArray(new String[0]), RQ_TRACK);
            return;
        }
        if (hasLoc()) VisitService.sync(this);
    }

    @Override public void onRequestPermissionsResult(int rq, String[] perms, int[] res) {
        if (rq == RQ_GEO && geoCb != null) {
            geoCb.invoke(geoOrigin, hasLoc(), false);
            geoCb = null;
        } else if (rq == RQ_TRACK && hasLoc()) {
            VisitService.sync(this);
        }
    }

    /** window.AndroidApp in the page */
    private class Bridge {
        /* the page calls this on every redraw; only act when the list actually changed */
        @JavascriptInterface public void track(final String json, final String lang, final boolean notify) {
            if ((json + lang + notify).equals(lastTrack)) return;
            lastTrack = json + lang + notify;
            VisitStore.setTargets(MainActivity.this, json, lang, notify);
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (VisitStore.active(MainActivity.this).isEmpty()) VisitService.sync(MainActivity.this);
                    else startTracking();
                }
            });
        }

        @JavascriptInterface public void googleLogin(String clientId, String secret) {
            GoogleLogin.start(MainActivity.this, clientId, secret, new GoogleLogin.Done() {
                @Override public void done(String idToken, String error) {
                    loginDone(idToken, error);
                }
            });
        }

        /** visit notifications switched on in Settings: make sure Android lets us show them */
        @JavascriptInterface public void askNotify() {
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4);
        }

        @JavascriptInterface public String state() {
            return VisitStore.state(MainActivity.this);
        }
    }
}

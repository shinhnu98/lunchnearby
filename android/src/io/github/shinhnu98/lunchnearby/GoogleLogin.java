package io.github.shinhnu98.lunchnearby;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Google sign-in for the app. Google refuses sign-in inside a WebView, so this runs the standard
 * "installed app" OAuth flow: Chrome shows Google's page, Google redirects to a one-shot server on
 * 127.0.0.1 inside this app, and the code is exchanged (with PKCE) for an ID token that the page
 * hands to Firebase. Needs a "Desktop app" OAuth client ID from the same Google Cloud project.
 */
final class GoogleLogin {
    private static ServerSocket current;

    private GoogleLogin() {}

    interface Done {
        /** idToken on success; otherwise error ("" = cancelled / timed out) */
        void done(String idToken, String error);
    }

    static synchronized void start(final MainActivity a, final String clientId, final String secret, final Done cb) {
        stop();
        final ServerSocket ss;
        try {
            ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            ss.setSoTimeout(5 * 60_000);
        } catch (IOException e) {
            cb.done(null, e.getMessage());
            return;
        }
        current = ss;
        final String redirect = "http://127.0.0.1:" + ss.getLocalPort();
        final String verifier = rand(48), state = rand(16);
        Uri auth = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", "openid email profile")
                .appendQueryParameter("code_challenge", b64(sha256(verifier)))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .appendQueryParameter("prompt", "select_account")
                .build();
        a.openTab(auth);
        new Thread(new Runnable() {
            @Override public void run() {
                String token = null, err = "";
                try {
                    Uri q = waitForRedirect(ss, a.getPackageName());
                    if (q == null) err = "";
                    else if (q.getQueryParameter("error") != null) err = "access_denied".equals(q.getQueryParameter("error")) ? "" : q.getQueryParameter("error");
                    else if (!state.equals(q.getQueryParameter("state"))) err = "state mismatch";
                    else token = exchange(q.getQueryParameter("code"), clientId, secret, redirect, verifier);
                } catch (SocketTimeoutException e) {
                    err = "";
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                } finally {
                    try {
                        ss.close();
                    } catch (IOException ignored) {
                    }
                }
                cb.done(token, token == null ? err : null);
            }
        }).start();
    }

    static synchronized void stop() {
        if (current != null) try {
            current.close();
        } catch (IOException ignored) {
        }
        current = null;
    }

    /** Serve exactly one request (Google's redirect) and return its query, or null if the server was closed. */
    private static Uri waitForRedirect(ServerSocket ss, String pkg) throws IOException {
        while (true) {
            Socket s;
            try {
                s = ss.accept();
            } catch (SocketTimeoutException e) {
                throw e;
            } catch (IOException closed) {
                return null;
            }
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                String line = r.readLine();
                if (line == null || !line.startsWith("GET ")) continue;
                String path = line.split(" ")[1];
                if (!path.startsWith("/?")) {
                    reply(s, 404, "");
                    continue;
                }
                String back = "intent://login#Intent;scheme=lunchroulette;package=" + pkg + ";end";
                reply(s, 200, "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                        + "<body style='font-family:system-ui,sans-serif;text-align:center;padding:48px 20px;color:#2b2118;background:#faf6f0'>"
                        + "<div style='font-size:56px'>🎲</div><h2>로그인 확인 중… / Signing in…</h2>"
                        + "<p><a href='" + back + "' style='display:inline-block;background:#e8590c;color:#fff;padding:14px 22px;border-radius:99px;text-decoration:none;font-weight:700'>앱으로 돌아가기 · Back to the app</a></p>"
                        + "<script>setTimeout(function(){location.href='" + back + "'},400)</script>");
                return Uri.parse("http://127.0.0.1" + path);
            } finally {
                s.close();
            }
        }
    }

    private static void reply(Socket s, int code, String html) throws IOException {
        byte[] b = html.getBytes("UTF-8");
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        o.write(b);
        o.flush();
    }

    private static String exchange(String code, String clientId, String secret, String redirect, String verifier) throws Exception {
        String body = "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirect)
                + "&code_verifier=" + enc(verifier) + (secret.isEmpty() ? "" : "&client_secret=" + enc(secret));
        HttpURLConnection c = (HttpURLConnection) new URL("https://oauth2.googleapis.com/token").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        OutputStream o = c.getOutputStream();
        o.write(body.getBytes("UTF-8"));
        o.close();
        int st = c.getResponseCode();
        JSONObject j = new JSONObject(read(st < 400 ? c.getInputStream() : c.getErrorStream()));
        if (st >= 400 || !j.has("id_token"))
            throw new IOException(j.optString("error_description", j.optString("error", "HTTP " + st)));
        return j.getString("id_token");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String rand(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b64(b);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes("US-ASCII"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}

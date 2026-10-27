package dev.adrialemany.resonode;

import android.util.Base64;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import android.content.Context;
import android.content.SharedPreferences;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;


public class UrlFetcher {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1500;
    private static final long HARD_WATCHDOG_MS = 15000;

    private static final String KEY_LAST_URL = "last_server_url";
    private static final String KEY_LAST_CODE = "last_server_code";

    private static SharedPreferences prefs;
    private static volatile long gistBlockedUntil = 0;

    private static final String KEY_GIST_OWNER = "gist_owner";
    private static Context appContext;
    private static volatile String blockedOnNetwork = null;

    private static class NoRetryException extends Exception {
        NoRetryException(String msg) { super(msg); }
    }
    private static final OkHttpClient gistClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(hostname -> {
                List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
                List<InetAddress> ipv4First = new ArrayList<>();
                for (InetAddress addr : addresses) {
                    if (addr instanceof Inet4Address) ipv4First.add(0, addr);
                    else ipv4First.add(addr);
                }
                return ipv4First;
            })
            .build();

    public interface UrlCallback {
        void onUrlFound(String url);
        void onError(Exception e);
    }

    public static void fetchLatestUrl(String invitationCode, UrlCallback callback) {
        new Thread(() -> {
            try {
                applySecretKey(invitationCode);
            } catch (Exception e) {
                callback.onError(e);
                return;
            }

            String known = Config.SERVER_URL;
            if ((known == null || known.isEmpty()) && prefs != null) known = prefs.getString(KEY_LAST_URL, "");
            String knownCode = prefs != null ? prefs.getString(KEY_LAST_CODE, null) : null;
            boolean sameServer = invitationCode.trim().equals(knownCode);

            if (sameServer && known != null && !known.isEmpty() && isServerAlive(known)) {
                callback.onUrlFound(known);
                return;
            }

            String rawUrl = fetchFromRawGist(invitationCode);
            if (rawUrl != null && !rawUrl.equals(known) && isServerAlive(rawUrl)) {
                saveFoundUrl(rawUrl, invitationCode);
                callback.onUrlFound(rawUrl);
                return;
            }

            if (System.currentTimeMillis() < gistBlockedUntil && currentNetworkKey().equals(blockedOnNetwork)) {
                callback.onError(new Exception("GitHub rate limit en esta red: esperando al reinicio"));
                return;
            }

            fetchLatestUrlWithRetry(invitationCode, MAX_RETRIES, callback);
        }).start();
    }

    public static void init(Context ctx) {
        appContext = ctx.getApplicationContext();
        prefs = ctx.getApplicationContext().getSharedPreferences("ResoNodePrefs", Context.MODE_PRIVATE);
        if (Config.SERVER_URL == null || Config.SERVER_URL.isEmpty()) {
            Config.SERVER_URL = prefs.getString(KEY_LAST_URL, "");
        }
        try {
            applySecretKey(new SessionManager(ctx).getInvitationCode());
        } catch (Exception ignored) {
        }
    }

    private static void applySecretKey(String invitationCode) throws Exception {
        if (invitationCode == null || invitationCode.trim().isEmpty()) {
            throw new Exception("Codi d'Invitació buit");
        }
        String decoded = new String(Base64.decode(invitationCode.trim(), Base64.DEFAULT), "UTF-8");
        String[] parts = decoded.split("\\|");
        if (parts.length != 2) throw new Exception("Format de Codi d'Invitació invàlid");
        Config.API_SECRET_KEY = parts[1].trim();
    }

    private static boolean isServerAlive(String baseUrl) {
        Request request = new Request.Builder()
                .url(baseUrl + "/")
                .header("Connection", "close")
                .build();
        try (Response r = gistClient.newCall(request).execute()) {
            return r.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private static void fetchLatestUrlWithRetry(String invitationCode, int retriesLeft, UrlCallback callback) {
        final AtomicBoolean callbackFired = new AtomicBoolean(false);

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(HARD_WATCHDOG_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            if (callbackFired.compareAndSet(false, true)) {
                callback.onError(new Exception("Timeout: UrlFetcher no ha respost a temps"));
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();

        new Thread(() -> {
            try {
                if (invitationCode == null || invitationCode.trim().isEmpty()) {
                    throw new Exception("Codi d'Invitació buit en SessionManager!");
                }

                String decodedCode = new String(Base64.decode(invitationCode.trim(), Base64.DEFAULT), "UTF-8");
                String[] parts = decodedCode.split("\\|");
                if (parts.length != 2) throw new Exception("Format de Codi d'Invitació invàlid");

                String gistId = parts[0].trim();
                String secretKey = parts[1].trim();

                Config.API_SECRET_KEY = secretKey;

                // El paràmetre ?t= força la CDN de GitHub (Fastly) a no retornar el Gist cachejat antic
                String gistUrl = "https://api.github.com/gists/" + gistId + "?t=" + System.currentTimeMillis();

                // Purguem connexions velles abans de demanar el Gist
                gistClient.connectionPool().evictAll();

                Request request = new Request.Builder()
                        .url(gistUrl)
                        .header("User-Agent", "ResoNode-Android-Client")
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("Cache-Control", "no-cache, no-store, must-revalidate")
                        .header("Connection", "close")
                        .build();

                try (Response response = gistClient.newCall(request).execute()) {
                    if (response.code() == 403 || response.code() == 429) {
                        long resetMs = System.currentTimeMillis() + 10 * 60_000L;
                        String reset = response.header("X-RateLimit-Reset");
                        try {
                            if (reset != null) resetMs = Long.parseLong(reset) * 1000L;
                        } catch (NumberFormatException ignored) {}
                        gistBlockedUntil = resetMs;
                        blockedOnNetwork = currentNetworkKey();
                        throw new NoRetryException("GitHub rate limit");
                    }
                    if (response.code() == 404) {
                        throw new NoRetryException("Gist no encontrado");
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new Exception("GitHub HTTP Error: " + response.code());
                    }

                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    String content = json.getJSONObject("files").getJSONObject("resonode_url.json").getString("content");
                    String urlXifrada = new JSONObject(content).getString("url");

                    byte[] cipherData = Base64.decode(urlXifrada, Base64.DEFAULT);
                    byte[] iv = new byte[16];
                    byte[] cipherText = new byte[cipherData.length - 16];
                    System.arraycopy(cipherData, 0, iv, 0, 16);
                    System.arraycopy(cipherData, 16, cipherText, 0, cipherText.length);

                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] keyBytes = digest.digest(secretKey.getBytes("UTF-8"));

                    SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
                    IvParameterSpec ivSpec = new IvParameterSpec(iv);

                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

                    byte[] decryptedData = cipher.doFinal(cipherText);
                    String finalUrl = new String(decryptedData, "UTF-8").trim();

                    saveFoundUrl(finalUrl, invitationCode);
                    JSONObject ownerObj = json.optJSONObject("owner");
                    if (ownerObj != null && prefs != null) {
                        prefs.edit().putString(KEY_GIST_OWNER, ownerObj.optString("login", null)).apply();
                    }

                    if (callbackFired.compareAndSet(false, true)) {
                        watchdog.interrupt();
                        callback.onUrlFound(finalUrl);
                    }
                }

            } catch (Exception e) {
                if (retriesLeft > 0 && !(e instanceof NoRetryException)) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ignored) {}

                    watchdog.interrupt();
                    fetchLatestUrlWithRetry(invitationCode, retriesLeft - 1, callback);
                } else {
                    if (callbackFired.compareAndSet(false, true)) {
                        watchdog.interrupt();
                        callback.onError(e);
                    }
                }
            }
        }).start();
    }

    private static void saveFoundUrl(String url, String invitationCode) {
        Config.SERVER_URL = url;
        if (prefs != null) {
            prefs.edit()
                    .putString(KEY_LAST_URL, url)
                    .putString(KEY_LAST_CODE, invitationCode.trim())
                    .apply();
        }
    }

    private static String fetchFromRawGist(String invitationCode) {
        String owner = prefs != null ? prefs.getString(KEY_GIST_OWNER, null) : null;
        if (owner == null || owner.isEmpty()) return null;
        try {
            String decoded = new String(Base64.decode(invitationCode.trim(), Base64.DEFAULT), "UTF-8");
            String[] parts = decoded.split("\\|");
            if (parts.length != 2) return null;
            String gistId = parts[0].trim();
            String secretKey = parts[1].trim();

            Request request = new Request.Builder()
                    .url("https://gist.githubusercontent.com/" + owner + "/" + gistId
                            + "/raw/resonode_url.json?t=" + System.currentTimeMillis())
                    .header("Cache-Control", "no-cache")
                    .header("Connection", "close")
                    .build();
            try (Response r = gistClient.newCall(request).execute()) {
                if (!r.isSuccessful() || r.body() == null) return null;
                String encrypted = new JSONObject(r.body().string()).getString("url");
                return decryptUrl(encrypted, secretKey);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String decryptUrl(String encryptedB64, String secretKey) throws Exception {
        byte[] cipherData = Base64.decode(encryptedB64, Base64.DEFAULT);
        byte[] iv = new byte[16];
        byte[] cipherText = new byte[cipherData.length - 16];
        System.arraycopy(cipherData, 0, iv, 0, 16);
        System.arraycopy(cipherData, 16, cipherText, 0, cipherText.length);
        byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(secretKey.getBytes("UTF-8"));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(cipherText), "UTF-8").trim();
    }

    private static String currentNetworkKey() {
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network n = cm.getActiveNetwork();
                return n == null ? "none" : n.toString();
            }
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni == null ? "none" : String.valueOf(ni.getType());
        } catch (Exception e) {
            return "unknown";
        }
    }
}
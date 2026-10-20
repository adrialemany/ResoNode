package com.example.resonode;

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

public class UrlFetcher {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1500;
    private static final long HARD_WATCHDOG_MS = 15000;

    // Client OkHttp dedicat que prioritza IPv4 per evitar penjats en Dades Mòbils (IPv6/CGNAT)
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
        fetchLatestUrlWithRetry(invitationCode, MAX_RETRIES, callback);
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


                    if (callbackFired.compareAndSet(false, true)) {
                        watchdog.interrupt();
                        callback.onUrlFound(finalUrl);
                    }
                }

            } catch (Exception e) {
                if (retriesLeft > 0) {
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
}
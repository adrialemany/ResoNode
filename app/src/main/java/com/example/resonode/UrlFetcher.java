package com.example.resonode;

import android.util.Base64;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class UrlFetcher {

    public interface UrlCallback {
        void onUrlFound(String url);
        void onError(Exception e);
    }

    public static void fetchLatestUrl(String invitationCode, UrlCallback callback) {
        new Thread(() -> {
            try {
                String decodedCode = new String(Base64.decode(invitationCode, Base64.DEFAULT), "UTF-8");
                String[] parts = decodedCode.split("\\|");
                if (parts.length != 2) throw new Exception("Codi d'Invitació invàlid");

                String gistId = parts[0];
                String secretKey = parts[1];

                Config.API_SECRET_KEY = secretKey;

                String gistUrl = "https://api.github.com/gists/" + gistId + "?nocache=" + System.currentTimeMillis();
                HttpURLConnection conn = (HttpURLConnection) new URL(gistUrl).openConnection();
                conn.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();

                JSONObject json = new JSONObject(response.toString());
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
                String finalUrl = new String(decryptedData, "UTF-8");

                callback.onUrlFound(finalUrl);

            } catch (Exception e) {
                e.printStackTrace();
                callback.onError(e);
            }
        }).start();
    }
}
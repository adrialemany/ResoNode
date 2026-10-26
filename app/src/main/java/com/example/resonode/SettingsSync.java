package com.example.resonode;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sincroniza con el servidor los ajustes que deben ser iguales en todos los dispositivos:
 * idioma, Wrapped (activado / público) y foto de perfil. Los ajustes estéticos NO se tocan.
 *
 * Regla: gana el último cambio. Un cambio hecho aquí queda "pendiente" hasta que el servidor
 * lo confirma; mientras esté pendiente, lo que diga el servidor no lo sobrescribe.
 */
public final class SettingsSync {

    private SettingsSync() {}

    public static final String PREFS = "ResoNodePrefs";
    public static final String KEY_PROFILE_URI = "profile_pic_uri";
    private static final String KEY_AVATAR_V = "avatar_v";
    private static final String DIRTY_LANGUAGE = "sync_dirty_language";
    private static final String DIRTY_WRAPPED = "sync_dirty_wrapped";
    private static final String DIRTY_AVATAR = "sync_dirty_avatar";
    private static final int AVATAR_SIZE = 512;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final OkHttpClient client = Tls12SocketFactory.enableTls12OnPreLollipop(
                    new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                                    .header("x-secret-key", Config.API_SECRET_KEY).build())))
            .build();

    public interface PullCallback {
        void onLanguageChanged();
        void onAvatarChanged();
    }

    public interface AvatarCallback {
        void onDone(String newUriOrNull);
    }

    // ================= Cambios hechos en este dispositivo =================

    public static void languageChanged(Context ctx) {
        markDirty(ctx, DIRTY_LANGUAGE);
        pushPending(ctx);
    }

    public static void wrappedChanged(Context ctx) {
        markDirty(ctx, DIRTY_WRAPPED);
        pushPending(ctx);
    }

    /** Foto nueva elegida en Ajustes: se recorta a 512x512, se guarda dentro de la app y se sube. */
    public static void setNewAvatar(Context ctx, Uri picked, AvatarCallback cb) {
        final Context app = ctx.getApplicationContext();
        executor.execute(() -> {
            String newUri = null;
            try {
                byte[] jpeg = renderAvatarJpeg(app, picked);
                File file = writeAvatarFile(app, "avatar_" + System.currentTimeMillis() + ".jpg", jpeg);
                newUri = Uri.fromFile(file).toString();
                prefs(app).edit().putString(KEY_PROFILE_URI, newUri).apply();
                markDirty(app, DIRTY_AVATAR);
                pushAll(app);
            } catch (Exception e) {
                e.printStackTrace();
            }
            final String result = newUri;
            mainHandler.post(() -> { if (cb != null) cb.onDone(result); });
        });
    }

    /** Al cerrar sesión: la foto y los pendientes son del usuario que se va. */
    public static void clearLocalProfile(Context ctx) {
        Context app = ctx.getApplicationContext();
        prefs(app).edit()
                .remove(KEY_PROFILE_URI).remove(KEY_AVATAR_V)
                .remove(DIRTY_LANGUAGE).remove(DIRTY_WRAPPED).remove(DIRTY_AVATAR)
                .apply();
        File[] files = new File(app.getFilesDir(), "avatar").listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    // ================= Descargar del servidor (al abrir la app) =================

    public static void pull(Context ctx, PullCallback cb) {
        final Context app = ctx.getApplicationContext();
        executor.execute(() -> {
            if (!canSync(app)) return;
            pushAll(app); // primero, lo que este móvil tenga pendiente

            String user = new SessionManager(app).getUsername();
            boolean languageChanged = false;
            boolean avatarChanged = false;

            try (Response r = client.newCall(new Request.Builder()
                    .url(url("/user/settings", "username", user)).build()).execute()) {
                if (!r.isSuccessful() || r.body() == null) return;
                JSONObject s = new JSONObject(r.body().string());
                SharedPreferences p = prefs(app);

                // --- Idioma ---
                if (!p.getBoolean(DIRTY_LANGUAGE, false)) {
                    String serverLang = s.isNull("language") ? null : s.optString("language", null);
                    if (serverLang == null) {
                        markDirty(app, DIRTY_LANGUAGE); // el servidor no tiene dato: se sube el de aquí
                    } else if (!serverLang.equals(LocaleHelper.getLanguage(app))) {
                        LocaleHelper.setLocale(app, serverLang);
                        languageChanged = true;
                    }
                }

                // --- Wrapped ---
                if (!p.getBoolean(DIRTY_WRAPPED, false)) {
                    SessionManager session = new SessionManager(app);
                    if (s.optBoolean("wrapped_configured", false)) {
                        session.setWrappedEnabled(s.optBoolean("wrapped_enabled", false));
                        session.setWrappedPublic(s.optBoolean("wrapped_public", false));
                    } else if (session.isWrappedEnabled()) {
                        markDirty(app, DIRTY_WRAPPED);
                    }
                }

                // --- Foto ---
                if (!p.getBoolean(DIRTY_AVATAR, false)) {
                    long serverV = s.optLong("avatar_v", 0);
                    long localV = p.getLong(KEY_AVATAR_V, 0);
                    if (serverV > 0 && serverV != localV) {
                        avatarChanged = downloadAvatar(app, user, serverV);
                    } else if (serverV == 0 && p.getString(KEY_PROFILE_URI, null) != null) {
                        markDirty(app, DIRTY_AVATAR); // foto de antes de la sincronización: se sube
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }

            pushAll(app); // sube lo que el servidor aún no tenía

            final boolean lang = languageChanged;
            final boolean avatar = avatarChanged;
            mainHandler.post(() -> {
                if (cb == null) return;
                if (avatar) cb.onAvatarChanged();
                if (lang) cb.onLanguageChanged();
            });
        });
    }

    /** URL de la foto de otro usuario para Glide (con la clave en la cabecera). */
    public static GlideUrl avatarUrl(String owner, String viewer, long version) {
        return new GlideUrl(
                url("/user/avatar", "username", owner, "viewer", viewer, "v", String.valueOf(version)),
                new LazyHeaders.Builder().addHeader("x-secret-key", Config.API_SECRET_KEY).build());
    }

    // ================= Subir al servidor =================

    private static void pushPending(Context ctx) {
        final Context app = ctx.getApplicationContext();
        executor.execute(() -> pushAll(app));
    }

    /** Se ejecuta siempre en el hilo de SettingsSync. */
    private static void pushAll(Context app) {
        if (!canSync(app)) return;
        String user = new SessionManager(app).getUsername();
        SharedPreferences p = prefs(app);
        if (p.getBoolean(DIRTY_LANGUAGE, false) && pushLanguage(app, user)) clearDirty(app, DIRTY_LANGUAGE);
        if (p.getBoolean(DIRTY_WRAPPED, false) && pushWrapped(app, user)) clearDirty(app, DIRTY_WRAPPED);
        if (p.getBoolean(DIRTY_AVATAR, false) && pushAvatar(app, user)) clearDirty(app, DIRTY_AVATAR);
    }

    private static boolean pushLanguage(Context app, String user) {
        try {
            JSONObject j = new JSONObject();
            j.put("username", user);
            j.put("language", LocaleHelper.getLanguage(app));
            return postJson("/user/settings", j);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean pushWrapped(Context app, String user) {
        try {
            SessionManager session = new SessionManager(app);
            JSONObject j = new JSONObject();
            j.put("username", user);
            j.put("enabled", session.isWrappedEnabled());
            j.put("is_public", session.isWrappedPublic());
            return postJson("/stats/config", j);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean pushAvatar(Context app, String user) {
        try {
            String uri = prefs(app).getString(KEY_PROFILE_URI, null);
            if (uri == null) return true;
            byte[] jpeg = renderAvatarJpeg(app, Uri.parse(uri));
            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("username", user)
                    .addFormDataPart("file", "avatar.jpg",
                            RequestBody.create(MediaType.parse("image/jpeg"), jpeg))
                    .build();
            Request request = new Request.Builder().url(Config.SERVER_URL + "/user/avatar").post(body).build();
            try (Response r = client.newCall(request).execute()) {
                if (!r.isSuccessful() || r.body() == null) return false;
                long v = new JSONObject(r.body().string()).optLong("avatar_v", 0);
                prefs(app).edit().putLong(KEY_AVATAR_V, v).apply();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean downloadAvatar(Context app, String user, long version) {
        Request request = new Request.Builder()
                .url(url("/user/avatar", "username", user, "viewer", user, "v", String.valueOf(version)))
                .build();
        try (Response r = client.newCall(request).execute()) {
            if (!r.isSuccessful() || r.body() == null) return false;
            File f = writeAvatarFile(app, "avatar_" + version + ".jpg", r.body().bytes());
            prefs(app).edit()
                    .putString(KEY_PROFILE_URI, Uri.fromFile(f).toString())
                    .putLong(KEY_AVATAR_V, version)
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ================= Utilidades =================

    private static boolean postJson(String endpoint, JSONObject j) throws Exception {
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), j.toString());
        Request request = new Request.Builder().url(Config.SERVER_URL + endpoint).post(body).build();
        try (Response r = client.newCall(request).execute()) {
            return r.isSuccessful();
        }
    }

    /** Glide hace el trabajo difícil: reduce la imagen, respeta la rotación EXIF y recorta al centro. */
    private static byte[] renderAvatarJpeg(Context app, Uri source) throws Exception {
        Bitmap bmp = Glide.with(app).asBitmap().load(source)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .centerCrop()
                .submit(AVATAR_SIZE, AVATAR_SIZE)
                .get();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return out.toByteArray();
    }

    /** Cada versión tiene un nombre de archivo distinto, así Glide nunca muestra una foto vieja de su caché. */
    private static File writeAvatarFile(Context app, String name, byte[] data) throws Exception {
        File dir = new File(app.getFilesDir(), "avatar");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) if (!f.getName().equals(name)) f.delete();
        return file;
    }

    private static boolean canSync(Context app) {
        if (AppPrefs.isLocalMode(app)) return false;
        SessionManager s = new SessionManager(app);
        return s.isLoggedIn() && s.getUsername() != null
                && Config.SERVER_URL != null && !Config.SERVER_URL.isEmpty()
                && Config.API_SECRET_KEY != null && !Config.API_SECRET_KEY.isEmpty();
    }

    private static String url(String endpoint, String... query) {
        Uri.Builder b = Uri.parse(Config.SERVER_URL + endpoint).buildUpon();
        for (int i = 0; i + 1 < query.length; i += 2) b.appendQueryParameter(query[i], query[i + 1]);
        return b.build().toString();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void markDirty(Context c, String key) { prefs(c).edit().putBoolean(key, true).apply(); }

    private static void clearDirty(Context c, String key) { prefs(c).edit().remove(key).apply(); }
}
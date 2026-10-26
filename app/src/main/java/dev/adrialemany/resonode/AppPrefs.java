package dev.adrialemany.resonode;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPrefs {
    private static final String FILE = "ResoNodeStartup";
    private static final String KEY_LOCAL_MODE = "local_mode";
    private static final String KEY_INVITATION = "last_invitation_code";

    private AppPrefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isLocalMode(Context c) {
        return prefs(c).getBoolean(KEY_LOCAL_MODE, false);
    }

    public static void setLocalMode(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_LOCAL_MODE, enabled).apply();
    }

    public static String getInvitationCode(Context c) {
        return prefs(c).getString(KEY_INVITATION, "");
    }

    public static void saveInvitationCode(Context c, String code) {
        if (code == null || code.trim().isEmpty()) return;
        prefs(c).edit().putString(KEY_INVITATION, code.trim()).apply();
    }
}
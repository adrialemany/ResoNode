package com.example.resonode;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {

    private static final String PREF_NAME = "ResoNodeSession";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";

    private static final String KEY_DEVICE_MODEL = "device_model";

    private static final String KEY_WRAPPED_ENABLED = "wrapped_enabled";
    private static final String KEY_WRAPPED_PUBLIC = "wrapped_public";

    private SharedPreferences pref;
    private SharedPreferences.Editor editor;
    private Context context;

    public SessionManager(Context context) {
        this.context = context;
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = pref.edit();
    }

    public void createLoginSession(String username) {
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_USERNAME, username);
        editor.commit();
    }

    public boolean isLoggedIn() {
        return pref.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    public String getUsername() {
        return pref.getString(KEY_USERNAME, null);
    }

    public void logoutUser() {
        editor.clear();
        editor.commit();
    }

    public void saveDeviceModel(String model) {
        editor.putString(KEY_DEVICE_MODEL, model);
        editor.commit();
    }

    public String getDeviceModel() {
        return pref.getString(KEY_DEVICE_MODEL, "Desconegut");
    }

    public void setWrappedEnabled(boolean enabled) {
        editor.putBoolean(KEY_WRAPPED_ENABLED, enabled);
        editor.commit();
    }

    public boolean isWrappedEnabled() {
        return pref.getBoolean(KEY_WRAPPED_ENABLED, false);
    }

    public void setWrappedPublic(boolean isPublic) {
        editor.putBoolean(KEY_WRAPPED_PUBLIC, isPublic);
        editor.commit();
    }

    public boolean isWrappedPublic() {
        return pref.getBoolean(KEY_WRAPPED_PUBLIC, false);
    }
}
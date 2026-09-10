package com.example.resonode;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

public class LocaleHelper {

    private static final String PREFS_NAME = "ResoNodePrefs";
    private static final String KEY_LANGUAGE = "app_language";

    public static final String LANG_ENGLISH = "en";
    public static final String LANG_SPANISH = "es";
    public static final String LANG_CATALAN = "ca";

    private static final String DEFAULT_LANGUAGE = LANG_ENGLISH;

    public static Context onAttach(Context context) {
        String lang = getPersistedLanguage(context);
        return applyLocale(context, lang);
    }

    public static String getLanguage(Context context) {
        return getPersistedLanguage(context);
    }

    public static Context setLocale(Context context, String language) {
        persist(context, language);
        return applyLocale(context, language);
    }

    private static Context applyLocale(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList localeList = new LocaleList(locale);
            LocaleList.setDefault(localeList);
            config.setLocales(localeList);
        }

        return context.createConfigurationContext(config);
    }

    private static String getPersistedLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE);
    }

    private static void persist(Context context, String language) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, language).apply();
    }
}
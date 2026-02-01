package com.example.spotifly;


import androidx.multidex.MultiDexApplication;
import android.os.Build;
import android.util.Log;
import androidx.appcompat.app.AppCompatDelegate;


public class MyApplication extends MultiDexApplication {

    

    @Override
    public void onCreate() {
        super.onCreate();

        
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);

        
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                Log.e("CRASH_REPORT", "------------------------------------------------");
                Log.e("CRASH_REPORT", "🔥 ERROR FATAL DETECTADO 🔥");
                Log.e("CRASH_REPORT", "Mensaje: " + e.getMessage());
                Log.getStackTraceString(e);
                e.printStackTrace();
                Log.e("CRASH_REPORT", "------------------------------------------------");

                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, e);
                } else {
                    System.exit(2);
                }
            }
        });
    }
}
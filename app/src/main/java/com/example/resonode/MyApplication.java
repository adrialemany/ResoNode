package com.example.resonode;

import androidx.multidex.MultiDexApplication;
import android.os.Build;
import android.util.Log;
import androidx.appcompat.app.AppCompatDelegate;

public class MyApplication extends MultiDexApplication {

    @Override
    public void onCreate() {
        super.onCreate();

        UrlFetcher.init(this);

        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);

        
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {

                Log.getStackTraceString(e);
                e.printStackTrace();

                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, e);
                } else {
                    System.exit(2);
                }
            }
        });
    }
}
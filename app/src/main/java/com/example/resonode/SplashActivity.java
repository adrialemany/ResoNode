package com.example.resonode;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.atomic.AtomicBoolean;

public class SplashActivity extends AppCompatActivity {

    private static final long WATCHDOG_TIMEOUT_MS = 2500;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View logoContainer = findViewById(R.id.logo_container);
        final TextView statusText = findViewById(R.id.tv_status);

        AlphaAnimation fadeIn = new AlphaAnimation(0.0f, 1.0f);
        fadeIn.setDuration(1000);
        logoContainer.startAnimation(fadeIn);

        final SessionManager session = new SessionManager(this);

        if (session.hasInvitationCode()) {
            AppPrefs.saveInvitationCode(this, session.getInvitationCode());
        }

        if (AppPrefs.isLocalMode(this)) {
            if (statusText != null) statusText.setText(getString(R.string.status_starting_offline));
            new Handler(Looper.getMainLooper()).postDelayed(() -> navigateToNextScreen(session), 800);
            return;
        }

        if (!NetworkReceiver.isConnected(this)) {
            if (statusText != null) statusText.setText(getString(R.string.status_starting_offline));
            new Handler().postDelayed(() -> navigateToNextScreen(session), 800);
            return;
        }

        if (session.hasInvitationCode()) {
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            final AtomicBoolean hasNavigated = new AtomicBoolean(false);

            Runnable watchdog = () -> {
                if (hasNavigated.compareAndSet(false, true)) {
                    android.util.Log.w("SplashActivity", "Watchdog: UrlFetcher no ha respost a temps, forçant navegació");
                    if (statusText != null) statusText.setText(getString(R.string.status_starting_offline));
                    navigateToNextScreen(session);
                }
            };
            mainHandler.postDelayed(watchdog, WATCHDOG_TIMEOUT_MS);

            UrlFetcher.fetchLatestUrl(session.getInvitationCode(), new UrlFetcher.UrlCallback() {
                @Override
                public void onUrlFound(String url) {
                    if (!hasNavigated.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(watchdog);
                    runOnUiThread(() -> {
                        if (statusText != null) statusText.setText(getString(R.string.status_syncing));
                    });
                    Config.SERVER_URL = url;
                    navigateToNextScreen(session);
                }

                @Override
                public void onError(Exception e) {
                    if (!hasNavigated.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(watchdog);
                    runOnUiThread(() -> {
                        if (statusText != null) statusText.setText(getString(R.string.status_starting_offline));
                    });
                    navigateToNextScreen(session);
                }
            });
        } else {
            new Handler().postDelayed(() -> navigateToNextScreen(session), 1500);
        }
    }

    private void navigateToNextScreen(SessionManager session) {
        runOnUiThread(() -> {
            Intent intent;
            if (AppPrefs.isLocalMode(SplashActivity.this)) {
                intent = new Intent(SplashActivity.this, MainActivity.class);
                intent.putExtra("LOCAL_MODE", true);
            } else if (session.isLoggedIn() && session.hasInvitationCode()) {
                intent = new Intent(SplashActivity.this, MainActivity.class);
            } else {
                intent = new Intent(SplashActivity.this, LoginActivity.class);
            }

            if (getIntent() != null && getIntent().getData() != null) {
                intent.setAction(getIntent().getAction());
                intent.setData(getIntent().getData());
            }

            startActivity(intent);
            overridePendingTransition(R.anim.fade_in_activity, R.anim.fade_out_activity);
            finish();
        });
    }
}
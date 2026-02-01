package com.example.spotifly;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import android.view.View;
import android.view.animation.AlphaAnimation;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        
        View logoContainer = findViewById(R.id.logo_container);
        View statusText = findViewById(R.id.tv_status);

        
        AlphaAnimation fadeIn = new AlphaAnimation(0.0f, 1.0f);
        fadeIn.setDuration(1000);
        logoContainer.startAnimation(fadeIn);

        
        UrlFetcher.fetchLatestUrl(new UrlFetcher.UrlCallback() {
            @Override
            public void onUrlFound(String url) {
                
                runOnUiThread(() -> ((android.widget.TextView)statusText).setText("Sincronizando..."));

                Config.SERVER_URL = url;
                navigateToNextScreen();
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> ((android.widget.TextView)statusText).setText("Iniciando offline..."));
                navigateToNextScreen();
            }
        });
    }

    private void navigateToNextScreen() {
        
        runOnUiThread(() -> {
            SessionManager session = new SessionManager(SplashActivity.this);
            Intent intent;

            if (session.isLoggedIn()) {
                
                intent = new Intent(SplashActivity.this, MainActivity.class);
            } else {
                
                intent = new Intent(SplashActivity.this, LoginActivity.class);
            }

            startActivity(intent);
            finish(); 
        });
    }
}
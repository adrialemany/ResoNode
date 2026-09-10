package com.example.resonode;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LoginActivity extends AppCompatActivity {

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    private EditText etUsername, etPassword, etInvitationCode;
    private Button btnAction;
    private ProgressBar progressBar;
    private SessionManager session;

    private OkHttpClient createClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request original = chain.request();
                        Request request = original.newBuilder()
                                .header("x-secret-key", Config.API_SECRET_KEY)
                                .method(original.method(), original.body())
                                .build();
                        return chain.proceed(request);
                    }
                });
        return Tls12SocketFactory.enableTls12OnPreLollipop(builder).build();
    }

    private final OkHttpClient client = createClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        session = new SessionManager(this);

        if (session.isLoggedIn() && session.hasInvitationCode()) {
            UrlFetcher.fetchLatestUrl(session.getInvitationCode(), new UrlFetcher.UrlCallback() {
                @Override
                public void onUrlFound(String url) {
                    Config.SERVER_URL = url;
                    goToMainActivity();
                }
                @Override
                public void onError(Exception e) {
                }
            });
        }

        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        etInvitationCode = findViewById(R.id.et_invitation_code);
        btnAction = findViewById(R.id.btn_login_register);
        progressBar = findViewById(R.id.progress_bar);

        if (session.hasInvitationCode() && etInvitationCode != null) {
            etInvitationCode.setText(session.getInvitationCode());
        }

        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handleAuth(); }
        });
    }

    private void handleAuth() {
        final String username = etUsername.getText().toString().trim();
        final String password = etPassword.getText().toString().trim();
        final String invCodeInput = etInvitationCode.getText().toString().trim();

        if (TextUtils.isEmpty(username)) { etUsername.setError(getString(R.string.error_missing_username)); return; }
        if (TextUtils.isEmpty(password)) { etPassword.setError(getString(R.string.error_missing_password)); return; }

        String codeToUse = invCodeInput;
        if (TextUtils.isEmpty(codeToUse)) {
            if (session.hasInvitationCode()) {
                codeToUse = session.getInvitationCode();
            } else {
                etInvitationCode.setError(getString(R.string.error_missing_invitation_code));
                return;
            }
        }

        setLoading(true);
        final String finalCode = codeToUse;

        UrlFetcher.fetchLatestUrl(finalCode, new UrlFetcher.UrlCallback() {
            @Override
            public void onUrlFound(final String url) {
                Config.SERVER_URL = url;
                session.saveInvitationCode(finalCode);

                proceedWithServerAuth(username, password);
            }

            @Override
            public void onError(Exception e) {
                showError(getString(R.string.error_invalid_invitation_code));
            }
        });
    }

    private void proceedWithServerAuth(final String username, final String password) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String loginUrl = Config.SERVER_URL + "/auth/login";

                    String manufacturer = android.os.Build.MANUFACTURER;
                    String model = android.os.Build.MODEL;
                    String deviceName = manufacturer.substring(0, 1).toUpperCase() + manufacturer.substring(1) + " " + model;

                    RequestBody body = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("username", username)
                            .addFormDataPart("password", password)
                            .addFormDataPart("device_model", deviceName)
                            .build();

                    Request loginRequest = new Request.Builder().url(loginUrl).post(body).build();
                    Response loginResponse = client.newCall(loginRequest).execute();

                    if (loginResponse.isSuccessful()) {
                        finishLogin(username);
                    }
                    else if (loginResponse.code() == 404) {
                        attemptRegister(username, password);
                    }
                    else if (loginResponse.code() == 401) {
                        showError(getString(R.string.error_wrong_password));
                    }
                    else if (loginResponse.code() == 403) {
                        showError(getString(R.string.error_security_invalid_key));
                    }
                    else {
                        showError(getString(R.string.error_server_code, loginResponse.code()));
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    showError(getString(R.string.error_connection, e.getMessage()));
                }
            }
        });
    }

    private void attemptRegister(final String username, final String password) {
        try {
            String registerUrl = Config.SERVER_URL + "/auth/register";

            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("username", username)
                    .addFormDataPart("password", password)
                    .build();

            Request request = new Request.Builder().url(registerUrl).post(body).build();
            Response response = client.newCall(request).execute();

            if (response.isSuccessful()) {
                mainHandler.post(new Runnable() {
                    @Override public void run() { Toast.makeText(LoginActivity.this, getString(R.string.toast_account_created), Toast.LENGTH_SHORT).show(); }
                });
                finishLogin(username);
            } else {
                showError(getString(R.string.error_register_failed, response.body().string()));
            }

        } catch (Exception e) {
            showError(getString(R.string.error_register_generic, e.getMessage()));
        }
    }

    private void finishLogin(final String username) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                session.createLoginSession(username);
                goToMainActivity();
            }
        });
    }

    private void goToMainActivity() {
        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(final boolean loading) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
                btnAction.setEnabled(!loading);
                etUsername.setEnabled(!loading);
                etPassword.setEnabled(!loading);
                if (etInvitationCode != null) etInvitationCode.setEnabled(!loading);
                if (loading) btnAction.setText(getString(R.string.btn_connecting));
                else btnAction.setText(getString(R.string.btn_login_register));
            }
        });
    }

    private void showError(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                setLoading(false);
                Toast.makeText(LoginActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
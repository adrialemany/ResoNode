package com.example.spotifly;

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

import org.json.JSONObject;

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

    private static final String API_SECRET_KEY = Config.API_SECRET_KEY;

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    private EditText etUsername, etPassword;
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
                                .header("x-secret-key", API_SECRET_KEY)
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

        

        
        UrlFetcher.fetchLatestUrl(new UrlFetcher.UrlCallback() {
            @Override
            public void onUrlFound(final String url) {
                Config.SERVER_URL = url;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if(!isFinishing()) Toast.makeText(LoginActivity.this, "Servidor conectado", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            @Override public void onError(Exception e) {}
        });

        session = new SessionManager(this);
        if (session.isLoggedIn()) { goToMainActivity(); return; }

        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnAction = findViewById(R.id.btn_login_register);
        progressBar = findViewById(R.id.progress_bar);

        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handleAuth(); }
        });
    }

    private void handleAuth() {
        final String username = etUsername.getText().toString().trim();
        final String password = etPassword.getText().toString().trim();

        if (TextUtils.isEmpty(username)) { etUsername.setError("Falta usuario"); return; }
        if (TextUtils.isEmpty(password)) { etPassword.setError("Falta contraseña"); return; }

        setLoading(true);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    
                    String loginUrl = Config.SERVER_URL + "/auth/login";

                    RequestBody body = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("username", username)
                            .addFormDataPart("password", password)
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
                        showError("Contraseña incorrecta");
                    }
                    else if (loginResponse.code() == 403) {
                        showError("Error de seguridad (Secret Key inválida)");
                    }
                    else {
                        showError("Error Servidor: " + loginResponse.code());
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    showError("Error conexión: " + e.getMessage());
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
                    @Override public void run() { Toast.makeText(LoginActivity.this, "¡Cuenta creada!", Toast.LENGTH_SHORT).show(); }
                });
                finishLogin(username);
            } else {
                showError("No se pudo registrar: " + response.body().string());
            }

        } catch (Exception e) {
            showError("Error registro: " + e.getMessage());
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
                if (loading) btnAction.setText("Conectando...");
                else btnAction.setText("ENTRAR / REGISTRAR");
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
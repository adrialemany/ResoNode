package com.example.resonode;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.media.audiofx.AudioEffect;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import android.content.SharedPreferences;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

public class SettingsActivity extends AppCompatActivity {

    private TextView tvDeviceInfo;
    private TextView tvOtherLabel;
    private LinearLayout llDevicesList;
    private ProgressBar pbLoading;

    private TextView tvStorageUsed;
    private Button btnClearMusic;
    private TextView btnOpenEq;

    private SwitchCompat switchWrapped;
    private RadioGroup rgPrivacy;
    private RadioButton rbPublic, rbPrivate;

    private TextView tvVersion;

    private SessionManager session;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar_settings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Configuració");
        }

        session = new SessionManager(this);

        tvDeviceInfo = findViewById(R.id.tv_device_info);
        tvOtherLabel = findViewById(R.id.tv_other_devices_label);
        llDevicesList = findViewById(R.id.ll_devices_list);
        pbLoading = findViewById(R.id.pb_loading_devices);

        tvStorageUsed = findViewById(R.id.tv_storage_used);
        btnClearMusic = findViewById(R.id.btn_clear_music);
        btnOpenEq = findViewById(R.id.btn_open_eq);

        SharedPreferences prefs = getSharedPreferences("ResoNodePrefs", MODE_PRIVATE);

        Spinner spinPlay = findViewById(R.id.spinner_play_style);
        Spinner spinNav = findViewById(R.id.spinner_nav_style);
        Spinner spinColor = findViewById(R.id.spinner_icon_color);

        final String[] stylesDisplay = {"Visor Tàctic Kiroshi (HUD)", "Maquinària Industrial (Hex)", "Dades Corruptes (Glitch)", "Clàssic (Material)"};
        final String[] stylesKeys = {"hud", "hex", "glitch", "original"};

        final String[] colorsDisplay = {"ResoNode (Groc Daurat)", "Groc Night City", "Roig Arasaka", "Blanc (Clàssic)", "Verd (SpotiFly)", "Cian (Elèctric)"};
        final String[] colorsKeys = {"resonode", "cyber_yellow", "cyber_red", "white", "green", "cyan"};

        ArrayAdapter<String> adapterStyles = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, stylesDisplay);
        adapterStyles.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinPlay.setAdapter(adapterStyles);
        spinNav.setAdapter(adapterStyles);

        ArrayAdapter<String> adapterColors = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, colorsDisplay);
        adapterColors.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinColor.setAdapter(adapterColors);

        // Llegir les configuracions guardades actualment
        String currentPlay = prefs.getString("play_style", "hud");
        String currentNav = prefs.getString("nav_style", "hud");
        String currentColor = prefs.getString("icon_color", "resonode");

        for (int i=0; i<stylesKeys.length; i++) {
            if (stylesKeys[i].equals(currentPlay)) spinPlay.setSelection(i);
            if (stylesKeys[i].equals(currentNav)) spinNav.setSelection(i);
        }
        for (int i=0; i<colorsKeys.length; i++) {
            if (colorsKeys[i].equals(currentColor)) spinColor.setSelection(i);
        }

        // Escoltar quan l'usuari canvia qualsevol dropdown i guardar-ho
        AdapterView.OnItemSelectedListener spinnerListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Per a evitar que el text negre del dropdown no es veja bé sobre fons negre:
                if (view != null && view instanceof TextView) ((TextView) view).setTextColor(0xFFFFFFFF);

                SharedPreferences.Editor editor = prefs.edit();
                if (parent == spinPlay) editor.putString("play_style", stylesKeys[position]);
                else if (parent == spinNav) editor.putString("nav_style", stylesKeys[position]);
                else if (parent == spinColor) editor.putString("icon_color", colorsKeys[position]);
                editor.apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };

        spinPlay.setOnItemSelectedListener(spinnerListener);
        spinNav.setOnItemSelectedListener(spinnerListener);
        spinColor.setOnItemSelectedListener(spinnerListener);

        switchWrapped = findViewById(R.id.switch_wrapped);
        rgPrivacy = findViewById(R.id.rg_privacy);
        rbPublic = findViewById(R.id.rb_public);
        rbPrivate = findViewById(R.id.rb_private);

        tvVersion = findViewById(R.id.tv_version);

        String currentModel = session.getDeviceModel();
        tvDeviceInfo.setText(currentModel);
        fetchLinkedDevices(currentModel);

        calculateStorageUsage();
        btnClearMusic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showClearConfirmation();
            }
        });

        btnOpenEq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
                    intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
                    intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
                    startActivityForResult(intent, 101);
                } catch (Exception e) {
                    Toast.makeText(SettingsActivity.this, "No s'ha trobat equalitzador al sistema.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        boolean isWrapped = session.isWrappedEnabled();
        switchWrapped.setChecked(isWrapped);
        rgPrivacy.setVisibility(isWrapped ? View.VISIBLE : View.GONE);

        if(session.isWrappedPublic()) rbPublic.setChecked(true);
        else rbPrivate.setChecked(true);

        switchWrapped.setOnCheckedChangeListener((buttonView, isChecked) -> {
            rgPrivacy.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            updateWrappedConfig(isChecked, rbPublic.isChecked());
        });

        rgPrivacy.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isPublic = (checkedId == R.id.rb_public);
            updateWrappedConfig(switchWrapped.isChecked(), isPublic);
        });

        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            tvVersion.setText("ResoNode v" + pInfo.versionName);
        } catch (Exception e) {
            tvVersion.setText("ResoNode v1.0");
        }
    }

    private void fetchLinkedDevices(final String currentModel) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    OkHttpClient client = new OkHttpClient();
                    RequestBody body = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("username", session.getUsername())
                            .build();

                    Request request = new Request.Builder()
                            .url(Config.SERVER_URL + "/auth/get_devices")
                            .header("x-secret-key", Config.API_SECRET_KEY)
                            .post(body)
                            .build();

                    Response response = client.newCall(request).execute();
                    if (response.isSuccessful()) {
                        String jsonStr = response.body().string();
                        JSONObject json = new JSONObject(jsonStr);
                        final JSONArray devices = json.getJSONArray("devices");

                        runOnUiThread(() -> updateDevicesList(devices, currentModel));
                    } else {
                        runOnUiThread(() -> pbLoading.setVisibility(View.GONE));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> pbLoading.setVisibility(View.GONE));
                }
            }
        });
    }

    private void updateDevicesList(JSONArray devices, String currentModel) {
        pbLoading.setVisibility(View.GONE);
        llDevicesList.removeAllViews();
        boolean foundOthers = false;

        try {
            for (int i = 0; i < devices.length(); i++) {
                String deviceName = devices.getString(i);
                if (deviceName.equals(currentModel)) continue;

                foundOthers = true;
                addDeviceView(deviceName);
            }

            if (foundOthers) {
                tvOtherLabel.setVisibility(View.VISIBLE);
            } else {
                TextView noDevices = new TextView(SettingsActivity.this);
                noDevices.setText("Cap altre dispositiu vinculat.");
                noDevices.setTextColor(0xFF888888);
                noDevices.setPadding(0, 16, 0, 0);
                llDevicesList.addView(noDevices);
                tvOtherLabel.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void addDeviceView(String deviceName) {
        TextView tv = new TextView(this);
        tv.setText("📱 " + deviceName);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(16);
        tv.setPadding(0, 16, 0, 16);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(0xFF333333);

        llDevicesList.addView(tv);
        llDevicesList.addView(divider);
    }

    private void calculateStorageUsage() {
        executor.execute(() -> {
            File musicDir = getDir("offline_music", Context.MODE_PRIVATE);
            long size = getFolderSize(musicDir);
            final String sizeText = String.format("%.2f MB", size / (1024.0 * 1024.0));
            runOnUiThread(() -> tvStorageUsed.setText(sizeText));
        });
    }

    private long getFolderSize(File dir) {
        long length = 0;
        if (dir != null && dir.exists()) {
            for (File file : dir.listFiles()) {
                if (file.isFile()) length += file.length();
                else length += getFolderSize(file);
            }
        }
        return length;
    }

    private void showClearConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar música offline?")
                .setMessage("S'esborraran totes les cançons descarregades en aquest dispositiu.")
                .setPositiveButton("ELIMINAR", (dialog, which) -> deleteOfflineMusic())
                .setNegativeButton("CANCEL·LAR", null)
                .show();
    }

    private void deleteOfflineMusic() {
        executor.execute(() -> {
            File musicDir = getDir("offline_music", Context.MODE_PRIVATE);
            if (musicDir.exists()) {
                for (File file : musicDir.listFiles()) {
                    file.delete();
                }
            }
            OfflineDB db = new OfflineDB(SettingsActivity.this);
            try {
                db.getWritableDatabase().execSQL("DELETE FROM songs");
            } catch (Exception e) {}
            db.close();

            runOnUiThread(() -> {
                Toast.makeText(SettingsActivity.this, "Música eliminada.", Toast.LENGTH_SHORT).show();
                calculateStorageUsage();
            });
        });
    }

    private void updateWrappedConfig(boolean enabled, boolean isPublic) {
        session.setWrappedEnabled(enabled);
        session.setWrappedPublic(isPublic);

        executor.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("username", session.getUsername());
                json.put("enabled", enabled);
                json.put("is_public", isPublic);

                OkHttpClient client = new OkHttpClient();
                RequestBody body = RequestBody.create(MediaType.parse("application/json"), json.toString());
                Request request = new Request.Builder()
                        .url(Config.SERVER_URL + "/stats/config")
                        .header("x-secret-key", Config.API_SECRET_KEY)
                        .post(body)
                        .build();

                client.newCall(request).execute();
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
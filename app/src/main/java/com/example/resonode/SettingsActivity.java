package com.example.resonode;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

import com.bumptech.glide.Glide;

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

    private ImageView ivProfile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Lògica per a rebre la foto de la galeria
    private final ActivityResultLauncher<Intent> pickProfileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();

                    if (imageUri != null) {
                        // Concedir permís persistent per a que la foto no desaparega en reiniciar
                        try {
                            getContentResolver().takePersistableUriPermission(imageUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        // Guardar la ruta en SharedPreferences
                        getSharedPreferences("ResoNodePrefs", MODE_PRIVATE)
                                .edit()
                                .putString("profile_pic_uri", imageUri.toString())
                                .apply();

                        // Actualitzar la imatge en la pantalla actual
                        loadProfileImage(imageUri.toString());
                        Toast.makeText(this, "Foto de perfil actualitzada!", Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 1. Configuració de la Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar_settings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Configuració");
        }

        session = new SessionManager(this);

        // 2. Inicialització de la secció Perfil
        ivProfile = findViewById(R.id.iv_settings_profile);
        Button btnChangePhoto = findViewById(R.id.btn_change_photo);

        // Carregar foto guardada si existeix
        String currentUri = getSharedPreferences("ResoNodePrefs", MODE_PRIVATE)
                .getString("profile_pic_uri", null);
        loadProfileImage(currentUri);

        btnChangePhoto.setOnClickListener(v -> openGallery());

        // 3. Inicialització de la resta de components
        tvDeviceInfo = findViewById(R.id.tv_device_info);
        tvOtherLabel = findViewById(R.id.tv_other_devices_label);
        llDevicesList = findViewById(R.id.ll_devices_list);
        pbLoading = findViewById(R.id.pb_loading_devices);
        tvStorageUsed = findViewById(R.id.tv_storage_used);
        btnClearMusic = findViewById(R.id.btn_clear_music);
        btnOpenEq = findViewById(R.id.btn_open_eq);
        switchWrapped = findViewById(R.id.switch_wrapped);
        rgPrivacy = findViewById(R.id.rg_privacy);
        rbPublic = findViewById(R.id.rb_public);
        rbPrivate = findViewById(R.id.rb_private);
        tvVersion = findViewById(R.id.tv_version);

        // 4. Configuració dels Spinners d'estil
        setupSpinners();

        // 5. Càrrega de dades del dispositiu i llista vinculada
        String currentModel = session.getDeviceModel();
        tvDeviceInfo.setText(currentModel);
        fetchLinkedDevices(currentModel);

        // 6. Emmagatzematge i Equalitzador
        calculateStorageUsage();
        btnClearMusic.setOnClickListener(v -> showClearConfirmation());
        btnOpenEq.setOnClickListener(v -> openEqualizer());

        // 7. Configuració de ResoNode Wrapped
        setupWrappedLogic();

        // 8. Versió de l'App
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            tvVersion.setText("ResoNode v" + pInfo.versionName);
        } catch (Exception e) {
            tvVersion.setText("ResoNode v1.0");
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        pickProfileLauncher.launch(intent);
    }

    private void loadProfileImage(String uriString) {
        if (uriString != null && ivProfile != null) {
            Glide.with(this)
                    .load(Uri.parse(uriString))
                    .placeholder(R.mipmap.ic_launcher)
                    .error(R.mipmap.ic_launcher)
                    .centerCrop()
                    .into(ivProfile);
        }
    }

    private void setupSpinners() {
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

        // Carregar seleccions guardades
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

        AdapterView.OnItemSelectedListener spinnerListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
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
    }

    private void openEqualizer() {
        try {
            Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
            intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
            startActivityForResult(intent, 101);
        } catch (Exception e) {
            Toast.makeText(this, "No s'ha trobat equalitzador al sistema.", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupWrappedLogic() {
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
    }

    private void fetchLinkedDevices(final String currentModel) {
        executor.execute(() -> {
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
                runOnUiThread(() -> pbLoading.setVisibility(View.GONE));
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
                TextView noDevices = new TextView(this);
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
        if (dir != null && dir.exists() && dir.listFiles() != null) {
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
                .setMessage("S'esborraran totes les cançons descarregades.")
                .setPositiveButton("ELIMINAR", (dialog, which) -> deleteOfflineMusic())
                .setNegativeButton("CANCEL·LAR", null)
                .show();
    }

    private void deleteOfflineMusic() {
        executor.execute(() -> {
            File musicDir = getDir("offline_music", Context.MODE_PRIVATE);
            if (musicDir.exists() && musicDir.listFiles() != null) {
                for (File file : musicDir.listFiles()) {
                    file.delete();
                }
            }
            OfflineDB db = new OfflineDB(this);
            try {
                db.getWritableDatabase().execSQL("DELETE FROM songs");
            } catch (Exception e) {}
            db.close();

            runOnUiThread(() -> {
                Toast.makeText(this, "Música eliminada.", Toast.LENGTH_SHORT).show();
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
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
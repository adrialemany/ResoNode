package com.example.resonode;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.tabs.TabLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WrappedActivity extends AppCompatActivity {

    private TextView tvTotalTime, tvNoData, tvTotalLabel;
    private LinearLayout llTopSongs;
    private ProgressBar pbLoading;
    private TabLayout tabLayout;
    private SessionManager session;
    private final OkHttpClient client = new OkHttpClient();

    private String viewingUser;
    private String currentPeriod = "week";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wrapped);

        session = new SessionManager(this);
        viewingUser = session.getUsername();

        Toolbar toolbar = findViewById(R.id.toolbar_wrapped);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            updateTitle();
        }

        tvTotalTime = findViewById(R.id.tv_total_time);
        tvNoData = findViewById(R.id.tv_no_data);
        tvTotalLabel = findViewById(R.id.tv_total_label);
        llTopSongs = findViewById(R.id.ll_top_songs);
        pbLoading = findViewById(R.id.pb_loading);
        tabLayout = findViewById(R.id.tabs_period);

        tabLayout.addTab(tabLayout.newTab().setText("Setmana"));
        tabLayout.addTab(tabLayout.newTab().setText("Mes"));
        tabLayout.addTab(tabLayout.newTab().setText("Any"));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) currentPeriod = "week";
                else if (tab.getPosition() == 1) currentPeriod = "month";
                else if (tab.getPosition() == 2) currentPeriod = "year";
                loadStats();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        if (!session.isWrappedEnabled()) {
            Toast.makeText(this, "Activa el Wrapped a Configuració primer.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        MusicService.syncHistory(this);

        loadStats();
    }

    private void updateTitle() {
        if (getSupportActionBar() != null) {
            if (viewingUser.equals(session.getUsername())) {
                getSupportActionBar().setTitle("El meu Wrapped");
            } else {
                getSupportActionBar().setTitle("Wrapped de " + viewingUser);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem item = menu.add(Menu.NONE, 1, Menu.NONE, "Comunitat");
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        item.setIcon(android.R.drawable.ic_menu_myplaces);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (!viewingUser.equals(session.getUsername())) {
                viewingUser = session.getUsername();
                updateTitle();
                loadStats();
                return true;
            }
            finish();
            return true;
        }
        else if (item.getItemId() == 1) {
            showCommunityDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showCommunityDialog() {
        pbLoading.setVisibility(View.VISIBLE);
        String url = Config.SERVER_URL + "/stats/community?period=" + currentPeriod;

        Request request = new Request.Builder().url(url).header("x-secret-key", Config.API_SECRET_KEY).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    pbLoading.setVisibility(View.GONE);
                    Toast.makeText(WrappedActivity.this, "Error de connexió", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> pbLoading.setVisibility(View.GONE));
                    return;
                }
                try {
                    String jsonStr = response.body().string();
                    JSONObject json = new JSONObject(jsonStr);
                    JSONArray users = json.getJSONArray("users");

                    final List<String> userNames = new ArrayList<>();
                    final List<String> displayNames = new ArrayList<>();

                    userNames.add(session.getUsername());
                    displayNames.add("👤 El meu perfil");

                    for(int i=0; i<users.length(); i++) {
                        JSONObject u = users.getJSONObject(i);
                        String name = u.getString("username");
                        int mins = u.getInt("minutes");

                        if (!name.equals(session.getUsername())) {
                            userNames.add(name);
                            displayNames.add(name + " (" + mins + " min)");
                        }
                    }

                    runOnUiThread(() -> {
                        pbLoading.setVisibility(View.GONE);
                        new AlertDialog.Builder(WrappedActivity.this)
                                .setTitle("Rànquing Comunitat (" + currentPeriod + ")")
                                .setItems(displayNames.toArray(new String[0]), (dialog, which) -> {
                                    viewingUser = userNames.get(which);
                                    updateTitle();
                                    loadStats();
                                })
                                .show();
                    });

                } catch (Exception e) { e.printStackTrace(); }
            }
        });
    }

    private void loadStats() {
        pbLoading.setVisibility(View.VISIBLE);
        tvNoData.setVisibility(View.GONE);
        llTopSongs.removeAllViews();
        tvTotalTime.setText("...");

        if (!viewingUser.equals(session.getUsername()) && !NetworkReceiver.isConnected(this)) {
            pbLoading.setVisibility(View.GONE);
            tvNoData.setText("Sense connexió. No es poden veure dades d'altres usuaris.");
            tvNoData.setVisibility(View.VISIBLE);
            return;
        }

        String url = Config.SERVER_URL + "/stats/get?username=" + viewingUser + "&period=" + currentPeriod;
        Request request = new Request.Builder().url(url).header("x-secret-key", Config.API_SECRET_KEY).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                loadLocalDataFallback();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    loadLocalDataFallback();
                    return;
                }

                try {
                    String jsonData = response.body().string();
                    JSONObject json = new JSONObject(jsonData);

                    if (json.has("enabled") && !json.getBoolean("enabled")) {
                        runOnUiThread(() -> {
                            pbLoading.setVisibility(View.GONE);
                            tvTotalTime.setText("Privat");
                            tvNoData.setText("Aquest usuari té el perfil privat.");
                            tvNoData.setVisibility(View.VISIBLE);
                        });
                        return;
                    }

                    final int totalMinutes = json.optInt("total_minutes", 0);
                    final double totalHours = json.optDouble("total_hours", 0.0);
                    final JSONArray topSongs = json.optJSONArray("top_5");

                    runOnUiThread(() -> {
                        pbLoading.setVisibility(View.GONE);
                        updateUI(totalMinutes, totalHours, topSongs);
                    });

                } catch (Exception e) {
                    loadLocalDataFallback();
                }
            }
        });
    }

    private void loadLocalDataFallback() {
        if (!viewingUser.equals(session.getUsername())) {
            runOnUiThread(() -> {
                pbLoading.setVisibility(View.GONE);
                Toast.makeText(WrappedActivity.this, "Error de connexió", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        runOnUiThread(() -> {
            try {
                OfflineDB db = new OfflineDB(WrappedActivity.this);
                JSONObject localStats = db.getLocalStats(currentPeriod);
                db.close();

                int mins = localStats.optInt("total_minutes");
                double hours = localStats.optDouble("total_hours");
                JSONArray top = localStats.optJSONArray("top_5");

                pbLoading.setVisibility(View.GONE);
                Toast.makeText(WrappedActivity.this, "Mode Offline: Mostrant dades locals", Toast.LENGTH_SHORT).show();
                updateUI(mins, hours, top);

            } catch (Exception e) {
                pbLoading.setVisibility(View.GONE);
            }
        });
    }

    private void updateUI(int minutes, double hours, JSONArray topSongs) {
        if (minutes < 60) tvTotalTime.setText(minutes + " min");
        else tvTotalTime.setText(String.format("%.1f h", hours));

        if (minutes == 0) {
            tvNoData.setText("No hi ha dades suficients per a aquest període.");
            tvNoData.setVisibility(View.VISIBLE);
            return;
        }

        if (topSongs != null) {
            for (int i = 0; i < topSongs.length(); i++) {
                JSONObject item = topSongs.optJSONObject(i);
                if (item != null) {
                    String rawName = item.optString("name");
                    String cleanName = rawName
                            .replace(".mp3", "").replace(".MP3", "")
                            .replaceAll("^(\\d+[\\s_\\-]*)+", "");
                    addSongRow(i + 1, cleanName, item.optInt("plays"));
                }
            }
        }
    }

    private void addSongRow(int rank, String name, int plays) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 16, 0, 16);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvRank = new TextView(this);
        tvRank.setText(String.valueOf(rank));
        tvRank.setTextSize(18);
        tvRank.setTextColor(0xFF1DB954);
        tvRank.setGravity(Gravity.CENTER);
        tvRank.setWidth(80);
        row.addView(tvRank);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.weight = 1;
        info.setLayoutParams(params);

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(16);
        tvName.setMaxLines(1);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView tvPlays = new TextView(this);
        tvPlays.setText(plays + " reproduccions");
        tvPlays.setTextColor(0xFFAAAAAA);
        tvPlays.setTextSize(12);

        info.addView(tvName);
        info.addView(tvPlays);
        row.addView(info);

        llTopSongs.addView(row);
    }
}
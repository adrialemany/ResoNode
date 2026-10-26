package dev.adrialemany.resonode;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.activity.OnBackPressedCallback;

import com.bumptech.glide.Glide;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class EditPlaylistActivity extends AppCompatActivity {

    public static final String EXTRA_NAME = "playlist_name";
    public static final String EXTRA_PATH = "playlist_path";
    private static final int MENU_SAVE = 1;

    private static final String STATE_COVER = "state_cover";
    private static final String STATE_ORDER_CHANGED = "state_order_changed";
    private static final String STATE_ORDER = "state_order";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    private String originalName;
    private String playlistPath;
    private SessionManager session;

    private ImageView ivCover;
    private TextInputEditText etName;
    private QueueAdapter songsAdapter;

    private Uri pendingCoverUri = null;
    private boolean orderChanged = false;
    private JSONArray restoredOrder = null;
    private boolean saving = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final OkHttpClient client = Tls12SocketFactory.enableTls12OnPreLollipop(
                    new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                                    .header("x-secret-key", Config.API_SECRET_KEY).build())))
            .build();

    private final ActivityResultLauncher<Intent> pickCoverLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    pendingCoverUri = result.getData().getData();
                    Glide.with(this).load(pendingCoverUri).centerCrop().into(ivCover);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_playlist);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmDiscard();
            }
        });

        session = new SessionManager(this);
        originalName = getIntent().getStringExtra(EXTRA_NAME);
        playlistPath = getIntent().getStringExtra(EXTRA_PATH);
        if (originalName == null || playlistPath == null) { finish(); return; }

        boolean pureBlack = getSharedPreferences("ResoNodePrefs", MODE_PRIVATE).getBoolean("pure_black", false);
        int bgColor = pureBlack ? 0xFF000000 : 0xFF121212;
        int barColor = pureBlack ? 0xFF000000 : 0xFF191414;
        getWindow().getDecorView().setBackgroundColor(bgColor);
        View root = findViewById(R.id.edit_playlist_root);
        if (root != null) root.setBackgroundColor(bgColor);

        Toolbar toolbar = findViewById(R.id.toolbar_edit_playlist);
        toolbar.setBackgroundColor(barColor);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.title_edit_playlist));
        }

        ivCover = findViewById(R.id.iv_edit_cover);
        findViewById(R.id.card_edit_cover).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            pickCoverLauncher.launch(Intent.createChooser(intent, getString(R.string.chooser_select_cover)));
        });

        if (savedInstanceState != null) {
            String cover = savedInstanceState.getString(STATE_COVER);
            if (cover != null) pendingCoverUri = Uri.parse(cover);
            orderChanged = savedInstanceState.getBoolean(STATE_ORDER_CHANGED, false);
            ArrayList<String> keys = savedInstanceState.getStringArrayList(STATE_ORDER);
            if (orderChanged && keys != null) restoredOrder = new JSONArray(keys);
        }

        Object coverModel = pendingCoverUri != null
                ? pendingCoverUri
                : buildUrl("/cover", "username", session.getUsername(), "path", playlistPath);
        Glide.with(this).load(coverModel)
                .placeholder(R.mipmap.ic_launcher)
                .error(R.mipmap.ic_launcher)
                .centerCrop()
                .into(ivCover);

        etName = findViewById(R.id.et_playlist_name);
        if (savedInstanceState == null) etName.setText(originalName);

        RecyclerView rv = findViewById(R.id.rv_edit_songs);
        rv.setLayoutManager(new LinearLayoutManager(this));
        songsAdapter = new QueueAdapter(new ArrayList<>());
        rv.setAdapter(songsAdapter);

        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {

            @Override
            public boolean isLongPressDragEnabled() { return false; }

            @Override
            public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int f = from.getAdapterPosition(), t = to.getAdapterPosition();
                if (f == RecyclerView.NO_POSITION || t == RecyclerView.NO_POSITION) return false;
                songsAdapter.moveItem(f, t);
                orderChanged = true;
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) { }
        });
        helper.attachToRecyclerView(rv);
        songsAdapter.setDragStarter(helper::startDrag);

        loadSongs();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingCoverUri != null) outState.putString(STATE_COVER, pendingCoverUri.toString());
        outState.putBoolean(STATE_ORDER_CHANGED, orderChanged);
        if (orderChanged) {
            ArrayList<String> keys = new ArrayList<>();
            for (MusicItem m : songsAdapter.getItems()) keys.add(PlaylistOrder.keyOf(m));
            outState.putStringArrayList(STATE_ORDER, keys);
        }
    }


    private void loadSongs() {
        final String user = session.getUsername();
        executor.execute(() -> {
            Request request = new Request.Builder()
                    .url(buildUrl("/browse", "username", user, "folder", playlistPath))
                    .build();
            try (Response r = client.newCall(request).execute()) {
                if (!r.isSuccessful() || r.body() == null) throw new Exception("HTTP " + r.code());
                JSONObject json = new JSONObject(r.body().string());
                JSONArray items = json.getJSONArray("items");

                final List<MusicItem> songs = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    if (!"file".equals(o.getString("type"))) continue;
                    songs.add(new MusicItem(o.getString("name"), "file", o.getString("path"), o.optString("artist", "")));
                }
                for (MusicItem local : new OfflineDB(getApplicationContext()).getSongsInPlaylist(originalName)) {
                    if (local.getPath().startsWith("content://")) songs.add(local);
                }

                PlaylistOrder.sort(songs, json.optJSONArray("order"));
                if (restoredOrder != null) PlaylistOrder.sort(songs, restoredOrder);

                mainHandler.post(() -> songsAdapter.setItems(songs));
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, getString(R.string.error_loading_playlists), Toast.LENGTH_SHORT).show());
            }
        });
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem save = menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, getString(R.string.action_save));
        save.setIcon(R.drawable.ic_check);
        save.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_SAVE) {
            save();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        confirmDiscard();
        return true;
    }

    private String currentName() {
        return etName.getText() == null ? "" : etName.getText().toString().trim();
    }

    private boolean hasChanges() {
        return pendingCoverUri != null || orderChanged || !currentName().equals(originalName);
    }

    private void confirmDiscard() {
        if (saving) return;
        if (!hasChanges()) { finish(); return; }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_discard_changes))
                .setMessage(getString(R.string.msg_discard_changes))
                .setPositiveButton(getString(R.string.btn_yes), (d, w) -> finish())
                .setNegativeButton(getString(R.string.btn_no), null)
                .show();
    }


    private void save() {
        if (saving) return;
        final String newName = currentName();
        if (newName.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_enter_playlist_name), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasChanges()) { finish(); return; }

        saving = true;
        final boolean renameNeeded = !newName.equals(originalName);
        final Uri coverUri = pendingCoverUri;
        final List<MusicItem> orderedSongs = orderChanged ? songsAdapter.getItems() : null;

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.progress_saving_playlist));
        pd.setCancelable(false);
        pd.show();

        executor.execute(() -> {
            String error = null;
            try {
                if (orderedSongs != null) saveOrder(orderedSongs);
                if (coverUri != null) uploadCover(coverUri);
                if (renameNeeded) rename(newName);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : getString(R.string.error_server_generic);
            }
            final String err = error;
            mainHandler.post(() -> {
                if (isFinishing()) return;
                pd.dismiss();
                saving = false;
                setResult(RESULT_OK);
                if (err == null) {
                    Toast.makeText(this, getString(R.string.toast_playlist_saved), Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(this, getString(R.string.toast_playlist_save_error, err), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void saveOrder(List<MusicItem> songs) throws Exception {
        JSONObject j = new JSONObject();
        j.put("username", session.getUsername());
        j.put("playlist_path", playlistPath);
        j.put("order", PlaylistOrder.toJson(songs));
        postJson("/playlist/reorder", j);
    }

    private void uploadCover(Uri imageUri) throws Exception {
        byte[] imageBytes;
        try (InputStream is = getContentResolver().openInputStream(imageUri)) {
            if (is == null) throw new Exception(getString(R.string.error_server_generic));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[16384];
            int n;
            while ((n = is.read(data, 0, data.length)) != -1) buffer.write(data, 0, n);
            imageBytes = buffer.toByteArray();
        }

        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("username", session.getUsername())
                .addFormDataPart("playlist_path", playlistPath)
                .addFormDataPart("file", "cover.jpg", RequestBody.create(MediaType.parse("image/jpeg"), imageBytes))
                .build();
        Request request = new Request.Builder().url(Config.SERVER_URL + "/playlist/upload_cover").post(body).build();
        try (Response r = client.newCall(request).execute()) {
            if (!r.isSuccessful()) throw new Exception(getString(R.string.error_server_code, r.code()));
        }

        File localCover = new File(getDir("offline_music", MODE_PRIVATE),
                "cover_" + originalName.replaceAll("[^a-zA-Z0-9.-]", "_") + ".jpg");
        if (localCover.exists()) localCover.delete();
        Glide.get(getApplicationContext()).clearDiskCache();
        mainHandler.post(() -> Glide.get(getApplicationContext()).clearMemory());
    }

    private void rename(String newName) throws Exception {
        JSONObject j = new JSONObject();
        j.put("username", session.getUsername());
        j.put("old_name", originalName);
        j.put("new_name", newName);
        JSONObject res = new JSONObject(postJson("/playlist/rename", j));
        if (!res.optBoolean("success", false)) {
            throw new Exception(res.optString("error", getString(R.string.error_server_generic)));
        }
        new OfflineDB(getApplicationContext()).renameLocalSongsPlaylist(originalName, newName);
    }

    private String postJson(String endpoint, JSONObject j) throws Exception {
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), j.toString());
        Request request = new Request.Builder().url(Config.SERVER_URL + endpoint).post(body).build();
        try (Response r = client.newCall(request).execute()) {
            String text = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) throw new Exception(getString(R.string.error_server_code, r.code()));
            return text;
        }
    }

    private String buildUrl(String endpoint, String... query) {
        Uri.Builder b = Uri.parse(Config.SERVER_URL + endpoint).buildUpon();
        for (int i = 0; i + 1 < query.length; i += 2) b.appendQueryParameter(query[i], query[i + 1]);
        return b.build().toString();
    }
}
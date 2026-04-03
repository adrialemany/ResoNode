package com.example.resonode;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import android.content.pm.PackageManager;
import android.net.Uri;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private Intent pendingIntentToHandle = null;

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    private DrawerLayout drawerLayout;
    private ImageButton btnShuffle, btnRepeat;
    private ImageButton btnSearch, btnBulkMore;

    private int playIconRes = android.R.drawable.ic_media_play;
    private int pauseIconRes = android.R.drawable.ic_media_pause;
    private NetworkReceiver networkReceiver;
    private RecyclerView recyclerView;
    private TextView tvStatus, tvToolbarAction;
    private FloatingActionButton fabAdd;
    private SwipeRefreshLayout swipeRefresh;
    private ImageView ivPlaylistHeader;

    private View miniPlayerLayout;
    private LinearLayout fullPlayerLayout;
    private TextView tvMiniTitle, tvMiniArtist, tvFullTitle, tvFullArtist, tvCurrentTime, tvTotalTime;
    private ImageButton btnMiniPlay, btnFullPlay, btnNext, btnPrev, btnClosePlayer;
    private SeekBar seekBar;

    private SessionManager session;
    private PlaylistAdapter adapter;
    private List<MusicItem> musicList = new ArrayList<>();
    private String currentPath = "";
    private int currentSongIndex = -1;
    private int currentVersionCode = 0;

    private MusicService musicService;
    private boolean isBound = false;
    private boolean isRetryingConnection = false;
    private boolean isShuffle = false;
    private Handler seekBarHandler = new Handler();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Handler retryHandler = new Handler(Looper.getMainLooper());
    private Runnable retryRunnable;
    private OfflineDB offlineDB;

    private MusicItem playlistCoverTarget = null;

    private OkHttpClient createClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
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

    private final ActivityResultLauncher<Intent> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && playlistCoverTarget != null) {
                        Uri imageUri = result.getData().getData();
                        if (imageUri != null) {
                            uploadCoverImage(imageUri, playlistCoverTarget);
                        }
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> pickLocalSongLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri audioUri = result.getData().getData();
                    if (audioUri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(audioUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception e){}

                        String[] meta = getAudioMetadata(audioUri);
                        String displayName = meta[0];
                        String artistName = meta[1];

                        showPlaylistSelectorForLocalSong(audioUri, displayName, artistName);
                    }
                }
            }
    );

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.LocalBinder binder = (MusicService.LocalBinder) service;
            musicService = binder.getService();
            isBound = true;
            updateShuffleUI();
            updateRepeatUI();
            musicService.setCallback(new MusicService.OnSongChangedListener() {
                @Override public void onSongChanged(final MusicItem song, final boolean isPlaying) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { updatePlayerUI(song, isPlaying); }
                    });
                }
                @Override public void onPlaybackStateChanged(final boolean isPlaying) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { updatePlayIcons(isPlaying); }
                    });
                }
            });
            MusicItem savedSong = musicService.getCurrentSong();
            if (savedSong != null) {
                updatePlayerUI(savedSong, musicService.isPlaying());
                if (!musicService.isPlaying()) updatePlayIcons(false);
                fullPlayerLayout.setVisibility(View.GONE);
            }

            handlePendingIntent();
        }
        @Override public void onServiceDisconnected(ComponentName name) { isBound = false; }
    };

    private final ActivityResultLauncher<Intent> searchLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        String action = data.getStringExtra("action");
                        if ("navigate".equals(action)) {
                            String path = data.getStringExtra("path");
                            final String songToPlay = data.getStringExtra("play_song");
                            fetchMusicContent(Config.SERVER_URL, path);
                            if (songToPlay != null) mainHandler.postDelayed(new Runnable() {
                                @Override public void run() { playSongByName(songToPlay); }
                            }, 1000);
                        } else if ("add_to_playlist".equals(action)) {
                            String pathId = data.getStringExtra("path_id");
                            MusicItem searchItem = new MusicItem("Cerca", "file", pathId);
                            showPlaylistSelectorForVaultItem(searchItem);
                        }
                    }
                }
            }
    );

    private File pendingApkFile = null;
    private final ActivityResultLauncher<Intent> updatePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls() && pendingApkFile != null) {
                        finishInstallation(pendingApkFile);
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            File updateFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
            if (updateFile.exists()) {
                updateFile.delete();
            }
        } catch (Exception e) {}

        try {
            setContentView(R.layout.activity_main);
        } catch (Exception e) {
            e.printStackTrace();
            TextView errorView = new TextView(this);
            errorView.setText("ERROR DE DISENY:\n" + e.getMessage());
            errorView.setTextColor(android.graphics.Color.RED);
            setContentView(errorView);
            return;
        }

        session = new SessionManager(this);
        checkAndUninstallOldApp("com.example.spotifly");
        if (!session.isLoggedIn()) { logoutUser(); return; }

        String manufacturer = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;
        String fullDeviceName = manufacturer.substring(0, 1).toUpperCase() + manufacturer.substring(1) + " " + model;
        session.saveDeviceModel(fullDeviceName);
        android.util.Log.d("ResoNode", "Model guardat: " + fullDeviceName);

        setupUI();
        setupPlayer();
        updatePlayerIcons();

        Intent intent = new Intent(this, MusicService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            currentVersionCode = pInfo.versionCode;
        } catch (Exception e) {}

        offlineDB = new OfflineDB(this);

        checkForUpdates();
        fetchMusicContent(Config.SERVER_URL, "");
        pendingIntentToHandle = getIntent();
    }

    private void fetchMusicContent(final String baseUrl, final String folderPath) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(true);
                tvStatus.setVisibility(View.GONE);
                if (isRetryingConnection) {
                    tvStatus.setText("Servidor reiniciat. Cercant nova adreça...");
                    tvStatus.setVisibility(View.VISIBLE);
                }
            }
        });

        final String username = session.getUsername();
        String tempEncodedPath = "";
        try { tempEncodedPath = URLEncoder.encode(folderPath, "UTF-8"); } catch (Exception e) { tempEncodedPath = folderPath; }
        final String encodedPath = tempEncodedPath;

        final String currentTargetUrl = Config.SERVER_URL + "/browse?username=" + username + "&folder=" + encodedPath;
        final String currentFolderRequest = folderPath;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Request request = new Request.Builder().url(currentTargetUrl).build();
                    Response response = client.newCall(request).execute();

                    if (response.code() == 403) throw new Exception("Clave Incorrecta");
                    if (!response.isSuccessful()) throw new Exception("Error " + response.code());

                    isRetryingConnection = false;

                    JSONObject json = new JSONObject(response.body().string());
                    if (json.has("current_path")) currentPath = json.getString("current_path");

                    boolean isVault = json.optBoolean("is_vault", false);
                    JSONArray items = json.getJSONArray("items");

                    final List<MusicItem> temp = new ArrayList<>();
                    boolean foundFolders = false;

                    for (int i = 0; i < items.length(); i++) {
                        JSONObject o = items.getJSONObject(i);
                        String name = o.getString("name");
                        String type = o.getString("type");
                        String path = o.getString("path");
                        String artist = o.optString("artist", "");
                        MusicItem item = new MusicItem(name, type, path, artist);
                        temp.add(item);
                        if (item.isFolder()) foundFolders = true;
                    }

                    final boolean finalIsVault = isVault;

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                                if (!currentPath.isEmpty() && !currentPath.equals("General")) {
                                    List<MusicItem> offlineSongs = offlineDB.getSongsInPlaylist(currentPath);
                                    for (MusicItem os : offlineSongs) {
                                        if (os.getPath().startsWith("content://")) temp.add(os);
                                    }
                                }
                                musicList.clear();
                                musicList.addAll(temp);
                                updateTitleAndFab(currentPath, finalIsVault);
                                if (adapter != null) adapter.setCurrentPath(currentPath);
                                    updateHeaderImage();

                                if (currentPath.equals("General") || currentPath.startsWith("General/")) adapter.setMode(PlaylistAdapter.MODE_PUBLIC);
                                else if (finalIsVault) adapter.setMode(PlaylistAdapter.MODE_VAULT);
                                else adapter.setMode(PlaylistAdapter.MODE_PRIVATE);

                                adapter.notifyDataSetChanged();
                            } catch (Throwable t) {
                                tvStatus.setText("Error visual: " + t.getMessage());
                                tvStatus.setVisibility(View.VISIBLE);
                            }
                        }
                    });

                } catch (final Exception e) {
                    e.printStackTrace();

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (NetworkReceiver.isConnected(MainActivity.this)) {
                                if (!isRetryingConnection) {
                                    isRetryingConnection = true;
                                    attemptServerRediscovery(folderPath);
                                    return;
                                }
                            }

                            isRetryingConnection = false;
                            android.util.Log.e("Offline", "Fallo red final, carregant offline: " + e.getMessage());

                            loadOfflineContent(currentFolderRequest);

                            String msg = NetworkReceiver.isConnected(MainActivity.this) ? "Error Servidor (Offline?)" : "Sense Connexió";
                            tvStatus.setText(msg);
                            tvStatus.setVisibility(View.VISIBLE);
                            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                        }
                    });
                }
            }
        });
    }

    private void updateHeaderImage() {
        View cardHeader = findViewById(R.id.card_header);
        if (ivPlaylistHeader == null || cardHeader == null) return;

        if (currentPath.isEmpty() || currentPath.equals("General")) {
            cardHeader.setVisibility(View.GONE);
        } else {
            cardHeader.setVisibility(View.VISIBLE);
            String headerUrl = "";
            try {
                String encodedHeaderPath = URLEncoder.encode(currentPath, "UTF-8");
                headerUrl = Config.SERVER_URL + "/cover?username=" + session.getUsername() + "&path=" + encodedHeaderPath;
            } catch (Exception e) {}

            Glide.with(this)
                    .load(headerUrl)
                    .placeholder(R.mipmap.ic_launcher)
                    .error(R.mipmap.ic_launcher)
                    .centerCrop()
                    .into(ivPlaylistHeader);
        }
    }

    private void attemptServerRediscovery(final String pendingFolderPath) {
        ServerDiscovery.findServerUrl(new ServerDiscovery.DiscoveryCallback() {
            @Override
            public void onSuccess(final String newUrl) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (newUrl != null && !newUrl.isEmpty()) {
                            Config.SERVER_URL = newUrl;

                            getSharedPreferences("ResoNodePrefs", MODE_PRIVATE)
                                    .edit()
                                    .putString("last_server_url", newUrl)
                                    .apply();

                            Toast.makeText(MainActivity.this, "Servidor trobat! Reconnectant...", Toast.LENGTH_SHORT).show();

                            fetchMusicContent(Config.SERVER_URL, pendingFolderPath);
                        } else {
                            handleDiscoveryFailure(pendingFolderPath);
                        }
                    }
                });
            }

            @Override
            public void onFailure(Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleDiscoveryFailure(pendingFolderPath);
                    }
                });
            }
        });
    }

    private void handleDiscoveryFailure(String folderPath) {
        isRetryingConnection = false;
        loadOfflineContent(folderPath);
        tvStatus.setText("Servidor no disponible");
        tvStatus.setVisibility(View.VISIBLE);
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
    }

    private void loadOfflineContent(final String folderPath) {
        final List<MusicItem> offlineItems = new ArrayList<>();
        final String displayTitle;

        if (folderPath.isEmpty()) {
            offlineItems.addAll(offlineDB.getOfflinePlaylists());
            currentPath = "";
            displayTitle = "Mode Offline";
        } else {
            offlineItems.addAll(offlineDB.getSongsInPlaylist(folderPath));
            currentPath = folderPath;
            displayTitle = folderPath + " (Local)";
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                musicList.clear();
                musicList.addAll(offlineItems);

                if (getSupportActionBar() != null) getSupportActionBar().setTitle(displayTitle);
                fabAdd.hide();
                tvStatus.setText("Mode Sense Connexió");
                tvStatus.setVisibility(View.VISIBLE);

                if (adapter != null) adapter.setCurrentPath(currentPath);

                if (ivPlaylistHeader != null) ivPlaylistHeader.setVisibility(View.GONE);

                adapter.setMode(PlaylistAdapter.MODE_PRIVATE);
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void updateTitleAndFab(String path, boolean isVault) {
        TextView tvTitle = findViewById(R.id.tv_custom_title);
        if (tvTitle == null) return;

        String title = path.isEmpty() ? "RESONODE" : path;
        if (title.startsWith("General")) title = "PÚBLICA";

        tvTitle.setText(title.toUpperCase());
        if (btnSearch != null) {
            btnSearch.setVisibility(path.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (fabAdd != null) {
            if (path.isEmpty()) {
                fabAdd.show();
            } else {
                fabAdd.hide();
            }
        }
    }

    private void playSongByName(String songName) {
        for (int i = 0; i < musicList.size(); i++) {
            if (!musicList.get(i).isFolder() && musicList.get(i).getName().contains(songName)) {
                currentSongIndex = i;
                playServiceStream(musicList.get(i));
                break;
            }
        }
    }

    private void playServiceStream(MusicItem item) {
        if (!isBound || musicService == null) return;
        try {
            String urlToPlay;
            String username = session.getUsername();
            if (item.getPath().startsWith("/")) {
                urlToPlay = item.getPath();
            } else {
                String encodedPath = URLEncoder.encode(item.getPath(), "UTF-8");
                urlToPlay = Config.SERVER_URL + "/stream?username=" + username + "&path=" + encodedPath;
            }
            musicService.playUrl(urlToPlay, musicList, currentSongIndex, username);
        } catch (Exception e) {
            Toast.makeText(this, "Error al reproduir", Toast.LENGTH_SHORT).show();
        }
    }

    private void updatePlayerUI(MusicItem song, boolean isPlaying) {
        miniPlayerLayout.setVisibility(View.VISIBLE);

        String cleanTitle = song.getName()
                .replace(".mp3", "").replace(".MP3", "")
                .replaceAll("^(\\d+[\\s_\\-]*)+", "");

        String artistName = song.getArtist();
        if (artistName == null || artistName.isEmpty()) {
            if (song.isFolder()) artistName = "Playlist";
            else artistName = "ResoNode Music";
        }

        tvMiniTitle.setText(cleanTitle);
        tvFullTitle.setText(cleanTitle);
        tvMiniArtist.setText(artistName);
        tvFullArtist.setText(artistName);

        updatePlayIcons(isPlaying);

        ImageView fullCover = findViewById(R.id.iv_full_cover);
        ImageView realMiniCover = findViewById(R.id.iv_mini_cover);

        String urlToTry = null;
        File localBackup = null;

        if (song.getPath().startsWith("/")) {
            if (offlineDB != null) {
                String originalPath = offlineDB.getServerPathForLocalFile(song.getPath());
                if (originalPath != null) {
                    try {
                        urlToTry = Config.SERVER_URL + "/cover?username=" + session.getUsername() + "&path=" + URLEncoder.encode(originalPath, "UTF-8");
                    } catch (Exception e) {}
                }

                List<MusicItem> playlists = offlineDB.getOfflinePlaylists();
                for (MusicItem pl : playlists) {
                    List<MusicItem> songsInPl = offlineDB.getSongsInPlaylist(pl.getName());
                    for (MusicItem s : songsInPl) {
                        if (s.getPath().equals(song.getPath())) {
                            File dir = getDir("offline_music", Context.MODE_PRIVATE);
                            String safeName = "cover_" + pl.getName().replaceAll("[^a-zA-Z0-9.-]", "_") + ".jpg";
                            File f = new File(dir, safeName);
                            if (f.exists()) localBackup = f;
                            break;
                        }
                    }
                    if (localBackup != null) break;
                }
            }
        } else {
            try {
                urlToTry = Config.SERVER_URL + "/cover?username=" + session.getUsername() + "&path=" + URLEncoder.encode(song.getPath(), "UTF-8");
            } catch (Exception e) {}
        }

        if (fullCover != null) {
            Glide.with(this)
                    .load(urlToTry)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .error(Glide.with(this).load(localBackup))
                    .placeholder(R.mipmap.ic_launcher)
                    .into(fullCover);
        }

        if (realMiniCover != null) {
            Glide.with(this)
                    .load(urlToTry)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .error(Glide.with(this).load(localBackup))
                    .placeholder(R.mipmap.ic_launcher)
                    .into(realMiniCover);
        }
    }

    private String formatTime(int ms) {
        int s = (ms/1000)%60;
        int m = (ms/(1000*60))%60;
        return String.format("%d:%02d", m, s);
    }

    public void setShuffle(boolean enabled) {
        this.isShuffle = enabled;
    }

    private void setupUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        drawerLayout = findViewById(R.id.drawer_layout);
        NavigationView navView = findViewById(R.id.nav_view);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
        navView.setNavigationItemSelectedListener(this);

        if (navView.getHeaderCount() > 0) {
            View headerView = navView.getHeaderView(0);
            TextView tvUser = headerView.findViewById(R.id.tv_header_user);
            ImageView ivProfile = headerView.findViewById(R.id.iv_user_profile);

            if (tvUser != null) tvUser.setText(session.getUsername());

            String profileUri = getSharedPreferences("ResoNodePrefs", MODE_PRIVATE)
                    .getString("profile_pic_uri", null);

            if (ivProfile != null && profileUri != null) {
                Glide.with(this)
                        .load(Uri.parse(profileUri))
                        .placeholder(R.mipmap.ic_launcher)
                        .error(R.mipmap.ic_launcher)
                        .centerCrop()
                        .into(ivProfile);
            }
        }

        tvStatus = findViewById(R.id.tv_status);
        tvToolbarAction = findViewById(R.id.tv_toolbar_action);
        btnSearch = findViewById(R.id.btn_search);
        // NOU: Inicialitzem el botó de selecció massiva
        btnBulkMore = findViewById(R.id.btn_bulk_more);

        recyclerView = findViewById(R.id.recycler_view_playlists);
        fabAdd = findViewById(R.id.fab_add);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        ivPlaylistHeader = findViewById(R.id.iv_playlist_header);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        swipeRefresh.setColorSchemeColors(0xFF1DB954);
        swipeRefresh.setOnRefreshListener(() -> fetchMusicContent(Config.SERVER_URL, currentPath));

        adapter = new PlaylistAdapter(this, musicList, PlaylistAdapter.MODE_PRIVATE,
                item -> {
                    if (item.isFolder()) {
                        String target = item.getPath().equals("Playlists Públiques") ? "General" : item.getPath();
                        fetchMusicContent(Config.SERVER_URL, target);
                    } else {
                        currentSongIndex = musicList.indexOf(item);
                        playServiceStream(item);
                    }
                },
                (item, action) -> {
                    if (action.equals("Eliminar")) deleteItem(item);
                    else if (action.equals("Reanomenar")) showRenameDialog(item);
                    else if (action.equals("Afegir a Playlist")) showPlaylistSelectorForVaultItem(item);
                    else if (action.equals("Descarregar Offline")) downloadPlaylist(item);
                    else if (action.equals("Borrar Offline")) deleteOfflinePlaylist(item);
                    else if (action.equals("Canviar Portada")) {
                        playlistCoverTarget = item;
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("image/*");
                        pickImageLauncher.launch(Intent.createChooser(intent, "Selecciona una portada"));
                    }
                }
        );

        // NOU: Lògica de selecció actualitzada per a mostrar els 3 puntets quan toque
        adapter.setOnSelectionChangedListener(count -> {
            if (count > 0) {
                btnSearch.setVisibility(View.GONE);
                fabAdd.hide();

                // Si estem en una llista privada (on es pot esborrar), mostrem 3 punts
                if (adapter.getMode() == PlaylistAdapter.MODE_PRIVATE) {
                    btnBulkMore.setVisibility(View.VISIBLE);
                    tvToolbarAction.setVisibility(View.VISIBLE);
                    tvToolbarAction.setText(count + " sel.");
                } else {
                    // Si és la Pública, només mostrar "AFEGIR"
                    btnBulkMore.setVisibility(View.GONE);
                    tvToolbarAction.setVisibility(View.VISIBLE);
                    tvToolbarAction.setText("AFEGIR (" + count + ")");
                }
            } else {
                tvToolbarAction.setVisibility(View.GONE);
                btnBulkMore.setVisibility(View.GONE);
                btnSearch.setVisibility(currentPath.isEmpty() ? View.VISIBLE : View.GONE);
                if (currentPath.isEmpty()) fabAdd.show();
            }
        });

        recyclerView.setAdapter(adapter);

        fabAdd.setOnClickListener(v -> handleFabClick());
        btnSearch.setOnClickListener(v -> searchLauncher.launch(new Intent(MainActivity.this, SearchActivity.class)));

        // Seguirà servint per afegir al polsar el text groc
        tvToolbarAction.setOnClickListener(v -> {
            List<MusicItem> selected = adapter.getSelectedItems();
            if (!selected.isEmpty()) {
                showAddToPlaylistDialog(selected);
            }
        });

        // NOU: Clic als 3 puntets globals per a múltiples cançons
        btnBulkMore.setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(MainActivity.this, v);
            popup.getMenu().add("Afegir a Playlist");
            popup.getMenu().add("Eliminar");
            popup.setOnMenuItemClickListener(item -> {
                String action = item.getTitle().toString();
                List<MusicItem> selected = adapter.getSelectedItems();
                if (action.equals("Afegir a Playlist")) {
                    showAddToPlaylistDialog(selected);
                } else if (action.equals("Eliminar")) {
                    bulkDeleteItems(selected);
                }
                return true;
            });
            popup.show();
        });
    }

    private void bulkDeleteItems(List<MusicItem> items) {
        new AlertDialog.Builder(this)
                .setTitle("Borrar " + items.size() + " cançons?")
                .setMessage("Aquesta acció eliminarà les cançons seleccionades de la playlist.")
                .setPositiveButton("SI", (dialog, which) -> {
                    executor.execute(() -> {
                        for (MusicItem i : items) {
                            JSONObject j = new JSONObject();
                            try {
                                j.put("username", session.getUsername());
                                j.put("path", i.getPath());
                                RequestBody body = RequestBody.create(MediaType.parse("application/json"), j.toString());
                                Request request = new Request.Builder()
                                        .url(Config.SERVER_URL + "/playlist/delete_item")
                                        .header("x-secret-key", Config.API_SECRET_KEY)
                                        .post(body)
                                        .build();
                                client.newCall(request).execute();
                            } catch (Exception e) {}
                        }
                        mainHandler.post(() -> {
                            Toast.makeText(MainActivity.this, "S'han eliminat les cançons", Toast.LENGTH_SHORT).show();
                            adapter.setSelectionMode(false);
                            fetchMusicContent(Config.SERVER_URL, currentPath);
                        });
                    });
                })
                .setNegativeButton("NO", null)
                .show();
    }

    private void downloadPlaylist(final MusicItem playlistItem) {
        if (offlineDB.isPlaylistDownloaded(playlistItem.getName())) {
            Toast.makeText(this, "Esta playlist ja està descarregada", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Iniciant descàrrega...", Toast.LENGTH_SHORT).show();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String folderPath = playlistItem.getPath();
                    String encodedFolder = URLEncoder.encode(folderPath, "UTF-8");
                    String username = session.getUsername();

                    String urlList = Config.SERVER_URL + "/browse?username=" + username + "&folder=" + encodedFolder;
                    Request requestList = new Request.Builder().url(urlList).build();
                    Response responseList = client.newCall(requestList).execute();

                    if (!responseList.isSuccessful()) throw new Exception("Error al llegir playlist");

                    JSONObject json = new JSONObject(responseList.body().string());
                    JSONArray items = json.getJSONArray("items");

                    File privateDir = getDir("offline_music", Context.MODE_PRIVATE);
                    if (!privateDir.exists()) privateDir.mkdirs();

                    try {
                        String coverUrl = Config.SERVER_URL + "/cover?username=" + username + "&path=" + encodedFolder;
                        Request reqCover = new Request.Builder().url(coverUrl).build();
                        Response resCover = client.newCall(reqCover).execute();

                        if (resCover.isSuccessful()) {
                            String safeCoverName = "cover_" + playlistItem.getName().replaceAll("[^a-zA-Z0-9.-]", "_") + ".jpg";
                            File localCover = new File(privateDir, safeCoverName);
                            FileOutputStream fos = new FileOutputStream(localCover);
                            fos.write(resCover.body().bytes());
                            fos.close();
                        }
                        resCover.close();
                    } catch (Exception e) {
                        android.util.Log.e("DL_COVER", "No s'ha pogut baixar portada: " + e.getMessage());
                    }

                    int total = 0;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject o = items.getJSONObject(i);
                        if (!o.getString("type").equals("file")) continue;

                        String serverPath = o.getString("path");
                        String name = o.getString("name");
                        String artist = o.optString("artist", "Desconegut");

                        String encodedFilePath = URLEncoder.encode(serverPath, "UTF-8");
                        String streamUrl = Config.SERVER_URL + "/stream?username=" + username + "&path=" + encodedFilePath;

                        Request reqFile = new Request.Builder().url(streamUrl).header("x-secret-key", Config.API_SECRET_KEY).build();
                        Response resFile = client.newCall(reqFile).execute();

                        if (resFile.isSuccessful()) {
                            String safeFileName = name.replaceAll("[^a-zA-Z0-9.-]", "_");
                            File localFile = new File(privateDir, safeFileName);
                            FileOutputStream fos = new FileOutputStream(localFile);
                            fos.write(resFile.body().bytes());
                            fos.close();

                            offlineDB.saveSong(serverPath, name, playlistItem.getName(), localFile.getAbsolutePath(), artist);
                            total++;
                        }
                        resFile.close();
                    }

                    final int count = total;
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Descarregades " + count + " cançons", Toast.LENGTH_LONG).show();
                            if (adapter != null) adapter.notifyDataSetChanged();
                        }
                    });

                } catch (final Exception e) {
                    e.printStackTrace();
                    mainHandler.post(new Runnable() {
                        @Override public void run() { Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
                    });
                }
            }
        });
    }

    private void setupPlayer() {
        miniPlayerLayout = findViewById(R.id.mini_player_layout);
        tvMiniTitle = findViewById(R.id.tv_mini_title); tvMiniArtist = findViewById(R.id.tv_mini_artist);
        btnMiniPlay = findViewById(R.id.btn_mini_play); fullPlayerLayout = findViewById(R.id.full_player_layout);
        tvFullTitle = findViewById(R.id.tv_full_title);
        tvFullArtist = findViewById(R.id.tv_full_artist);
        tvCurrentTime = findViewById(R.id.tv_current_time); tvTotalTime = findViewById(R.id.tv_total_time);
        btnFullPlay = findViewById(R.id.btn_full_play); btnClosePlayer = findViewById(R.id.btn_close_player);
        seekBar = findViewById(R.id.seek_bar); btnNext = findViewById(R.id.btn_next); btnPrev = findViewById(R.id.btn_prev);

        try {
            android.graphics.drawable.Drawable progressDrawable = seekBar.getProgressDrawable().mutate();
            progressDrawable.setColorFilter(0xFFF2B327, android.graphics.PorterDuff.Mode.SRC_IN);
            seekBar.setProgressDrawable(progressDrawable);

            if (Build.VERSION.SDK_INT >= 16) {
                android.graphics.drawable.Drawable thumb = seekBar.getThumb().mutate();
                thumb.setColorFilter(0xFFF2B327, android.graphics.PorterDuff.Mode.SRC_IN);
                seekBar.setThumb(thumb);
            }
        } catch (Exception e) {}

        miniPlayerLayout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fullPlayerLayout.setVisibility(View.VISIBLE); }
        });
        btnClosePlayer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fullPlayerLayout.setVisibility(View.GONE); }
        });
        btnShuffle = findViewById(R.id.btn_shuffle);

        btnShuffle.setOnClickListener(v -> {
            if (isBound && musicService != null) {
                musicService.toggleShuffle();
                updateShuffleUI();
            }
        });

        btnRepeat = findViewById(R.id.btn_repeat);
        btnShuffle = findViewById(R.id.btn_shuffle);

        btnRepeat.setOnClickListener(v -> {
            if (isBound && musicService != null) {
                musicService.toggleRepeatOne();
                updateRepeatUI();
            }
        });

        btnShuffle.setOnClickListener(v -> {
            if (isBound && musicService != null) {
                musicService.toggleShuffle();
                updateShuffleUI();
            }
        });

        View.OnClickListener playToggle = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(isBound) {
                    if(musicService.isPlaying()) musicService.pauseMusic();
                    else musicService.resumeMusic();
                }
            }
        };
        btnMiniPlay.setOnClickListener(playToggle); btnFullPlay.setOnClickListener(playToggle);
        btnNext.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if(isBound) musicService.playNext(); } });
        btnPrev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if(isBound) musicService.playPrev(); } });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { if(f && isBound) musicService.seekTo(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        Runnable updateSeek = new Runnable() {
            @Override public void run() {
                if(isBound && musicService.isPlaying()) {
                    int c = musicService.getCurrentPosition(); int d = musicService.getDuration();
                    seekBar.setMax(d); seekBar.setProgress(c);
                    tvCurrentTime.setText(formatTime(c)); tvTotalTime.setText(formatTime(d));
                }
                seekBarHandler.postDelayed(this, 1000);
            }
        }; seekBarHandler.post(updateSeek);
    }

    private void updateShuffleUI() {
        if (isBound && musicService != null && btnShuffle != null) {
            int color = musicService.isShuffleEnabled() ? 0xFFF2B327 : 0xFFFFFFFF;
            btnShuffle.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    private void updateRepeatUI() {
        if (isBound && musicService != null && btnRepeat != null) {
            int color = musicService.isRepeatOneEnabled() ? 0xFFF2B327 : 0xFFFFFFFF;
            btnRepeat.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    private void uploadCoverImage(Uri imageUri, final MusicItem playlist) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Pujant portada...");
        pd.setCancelable(false);
        pd.show();

        final ProgressDialog finalPd = pd;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream is = getContentResolver().openInputStream(imageUri);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    int nRead;
                    byte[] data = new byte[16384];
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                    byte[] imageBytes = buffer.toByteArray();

                    RequestBody requestBody = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("username", session.getUsername())
                            .addFormDataPart("playlist_path", playlist.getPath())
                            .addFormDataPart("file", "cover.jpg",
                                    RequestBody.create(MediaType.parse("image/jpeg"), imageBytes))
                            .build();

                    Request request = new Request.Builder()
                            .url(Config.SERVER_URL + "/playlist/upload_cover")
                            .header("x-secret-key", Config.API_SECRET_KEY)
                            .post(requestBody)
                            .build();

                    final Response response = client.newCall(request).execute();

                    if (response.isSuccessful()) {

                        try {
                            File privateDir = getDir("offline_music", Context.MODE_PRIVATE);
                            String safeCoverName = "cover_" + playlist.getName().replaceAll("[^a-zA-Z0-9.-]", "_") + ".jpg";
                            File localCover = new File(privateDir, safeCoverName);
                            if (localCover.exists()) {
                                boolean deleted = localCover.delete();
                                android.util.Log.d("COVER", "Caràtula local esborrada: " + deleted);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Glide.get(MainActivity.this).clearMemory();
                            }
                        });
                        Glide.get(MainActivity.this).clearDiskCache();

                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                finalPd.dismiss();
                                Toast.makeText(MainActivity.this, "Portada Actualizada!", Toast.LENGTH_SHORT).show();

                                fetchMusicContent(Config.SERVER_URL, currentPath);
                            }
                        });
                    } else {
                        throw new Exception("Error servidor: " + response.code());
                    }
                } catch (final Exception e) {
                    e.printStackTrace();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            finalPd.dismiss();
                            Toast.makeText(MainActivity.this, "Fallo al subir: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void handleFabClick() {
        String[] opts = {"Crear Playlist", "Afegir de Playlist Pública", "Buscar en Bòveda", "Afegir Cançó Local"};
        new AlertDialog.Builder(this).setItems(opts, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                if(w==0) showCreatePlaylistDialog();
                else if(w==1) goToGeneralAndSelect();
                else if(w==2) searchLauncher.launch(new Intent(MainActivity.this, SearchActivity.class));
                else {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("audio/*");
                    pickLocalSongLauncher.launch(intent);
                }
            }
        }).show();
    }

    private void goToGeneralAndSelect() {
        fetchMusicContent(Config.SERVER_URL, "General");
    }

    private void postJson(final String endpoint, final String jsonBody, final Runnable onSuccess) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);
                    Request request = new Request.Builder().url(Config.SERVER_URL + endpoint).header("x-secret-key", Config.API_SECRET_KEY).post(body).build();
                    if(client.newCall(request).execute().isSuccessful()) {
                        if(onSuccess != null) onSuccess.run();
                    }
                } catch (Exception e) { showErrorOnUI("Error servidor"); }
            }
        });
    }

    private void showErrorOnUI(final String m) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if(swipeRefresh!=null)swipeRefresh.setRefreshing(false);
                tvStatus.setText(m); tvStatus.setVisibility(View.VISIBLE);
                retryHandler.postDelayed(retryRunnable,5000);
            }
        });
    }

    private void logoutUser() {
        session.logoutUser();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    @Override
    public void onBackPressed() {
        if(fullPlayerLayout.getVisibility()==View.VISIBLE) fullPlayerLayout.setVisibility(View.GONE);
        else if(drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START);
        else if(!adapter.getSelectedItems().isEmpty()){ adapter.setSelectionMode(false); fabAdd.show(); }
        else if(!currentPath.isEmpty()) {
            File f=new File(currentPath);
            String p=f.getParent();
            if(p==null)p="";
            fetchMusicContent(Config.SERVER_URL, p.replace("\\","/"));
        } else super.onBackPressed();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem i) {
        if(i.getItemId()==R.id.nav_home) fetchMusicContent(Config.SERVER_URL,"");
        else if (i.getItemId() == R.id.nav_wrapped) {
            startActivity(new Intent(MainActivity.this, WrappedActivity.class));
        }
        else if (i.getItemId() == R.id.nav_settings) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        }
        else if(i.getItemId()==R.id.nav_logout) logoutUser();
        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        NavigationView navView = findViewById(R.id.nav_view);
        if (navView != null && navView.getHeaderCount() > 0) {
            View header = navView.getHeaderView(0);
            ImageView ivProfile = header.findViewById(R.id.iv_user_profile);

            String profileUri = getSharedPreferences("ResoNodePrefs", MODE_PRIVATE).getString("profile_pic_uri", null);
            if (ivProfile != null && profileUri != null) {
                Glide.with(this).load(Uri.parse(profileUri)).placeholder(R.mipmap.ic_launcher).centerCrop().into(ivProfile);
            }
        }

        updatePlayerIcons();

        if (networkReceiver == null) {
            networkReceiver = new NetworkReceiver(this::handleNetworkChange);
            registerReceiver(networkReceiver, new android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
        }

        updatePlayerIcons();

        if (networkReceiver == null) {
            networkReceiver = new NetworkReceiver(new NetworkReceiver.ConnectivityListener() {
                @Override
                public void onNetworkChanged(boolean isConnected) {
                    handleNetworkChange(isConnected);
                }
            });
            registerReceiver(networkReceiver, new android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (networkReceiver != null) unregisterReceiver(networkReceiver);
        } catch (Exception e) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(isBound) unbindService(serviceConnection);
    }

    private void showCreatePlaylistDialog() {
        EditText i=new EditText(this);
        new AlertDialog.Builder(this)
                .setTitle("Nova")
                .setView(i)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        JSONObject j=new JSONObject();
                        try{
                            j.put("username",session.getUsername());
                            j.put("playlist_name",i.getText().toString());
                        }catch(Exception e){}
                        postJson("/playlist/create",j.toString(), new Runnable() {
                            @Override public void run() {
                                mainHandler.post(new Runnable() {
                                    @Override public void run() { fetchMusicContent(Config.SERVER_URL,""); }
                                });
                            }
                        });
                    }
                }).show();
    }

    private void deleteItem(final MusicItem i) {
        new AlertDialog.Builder(this)
                .setTitle("Borrar")
                .setPositiveButton("SI", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        JSONObject j=new JSONObject();
                        try{
                            j.put("username",session.getUsername());
                            j.put("path",i.getPath());
                        }catch(Exception e){}
                        postJson("/playlist/delete_item",j.toString(), new Runnable() {
                            @Override public void run() {
                                mainHandler.post(new Runnable() {
                                    @Override public void run() { fetchMusicContent(Config.SERVER_URL,currentPath); }
                                });
                            }
                        });
                    }
                }).show();
    }

    private void showRenameDialog(final MusicItem i) {
        EditText t=new EditText(this); t.setText(i.getName());
        new AlertDialog.Builder(this)
                .setTitle("Renombrar")
                .setView(t)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        JSONObject j=new JSONObject();
                        try{
                            j.put("username",session.getUsername());
                            j.put("old_name",i.getName());
                            j.put("new_name",t.getText().toString());
                        }catch(Exception e){}
                        postJson("/playlist/rename",j.toString(), new Runnable() {
                            @Override public void run() {
                                mainHandler.post(new Runnable() {
                                    @Override public void run() { fetchMusicContent(Config.SERVER_URL,""); }
                                });
                            }
                        });
                    }
                }).show();
    }

    private void showPlaylistSelectorForVaultItem(final MusicItem item) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try{
                    Response r=client.newCall(new Request.Builder().url(Config.SERVER_URL+"/browse?username="+session.getUsername()+"&folder=").build()).execute();
                    if(r.isSuccessful()){
                        JSONArray a=new JSONObject(r.body().string()).getJSONArray("items");
                        final List<String> l=new ArrayList<>();
                        for(int x=0;x<a.length();x++) if(a.getJSONObject(x).getString("type").equals("folder")) l.add(a.getJSONObject(x).getString("name"));
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                new AlertDialog.Builder(MainActivity.this).setItems(l.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        addToSpecificPlaylist(l.get(w), item);
                                    }
                                }).show();
                            }
                        });
                    }
                }catch(Exception e){}
            }
        });
    }

    private void addToSpecificPlaylist(final String playlistName, final MusicItem item) {
        JSONObject j = new JSONObject();
        try {
            j.put("username", session.getUsername());
            j.put("playlist_name", playlistName);

            JSONArray a = new JSONArray();
            a.put(item.getPath());

            // TRUC: Enviem l'array amb els dos noms ("items" i "songs")
            // Així el teu servidor segur que ho llig correctament sense donar error
            j.put("items", a);
            j.put("songs", a);

            // Tria la ruta de l'API correcta
            String endpoint = item.isFolder() ? "/playlist/add_from_vault" : "/playlist/add";

            postJson(endpoint, j.toString(), new Runnable() {
                @Override public void run() {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            // 1. Mostrem el missatge de confirmació
                            Toast.makeText(MainActivity.this, "Afegit a " + playlistName, Toast.LENGTH_SHORT).show();

                            // 2. Com que NO cridem a fetchMusicContent(""), la pantalla es queda on estava!
                        }
                    });
                }
            });
        } catch(Exception e){}
    }

    private void showAddToPlaylistDialog(final List<MusicItem> l) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try{
                    Response r=client.newCall(new Request.Builder().url(Config.SERVER_URL+"/browse?username="+session.getUsername()+"&folder=").build()).execute();
                    if(r.isSuccessful()){
                        JSONArray a=new JSONObject(r.body().string()).getJSONArray("items");
                        final List<String> n=new ArrayList<>();
                        n.add("Nova...");
                        for(int x=0;x<a.length();x++) if(a.getJSONObject(x).getString("type").equals("folder")) n.add(a.getJSONObject(x).getString("name"));
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                new AlertDialog.Builder(MainActivity.this).setItems(n.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        if(w==0)showCreatePlaylistForAddDialog(l);
                                        else addSongsToPlaylist(n.get(w),l);
                                    }
                                }).show();
                            }
                        });
                    }
                }catch(Exception e){}
            }
        });
    }

    private void showCreatePlaylistForAddDialog(final List<MusicItem> s) {
        EditText i=new EditText(this);
        new AlertDialog.Builder(this)
                .setTitle("Nombre")
                .setView(i)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { createPlaylistAndAdd(i.getText().toString(),s); }
                }).show();
    }

    private void createPlaylistAndAdd(final String n, final List<MusicItem> s) {
        JSONObject j=new JSONObject();
        try{
            j.put("username",session.getUsername());
            j.put("playlist_name",n);
        }catch(Exception e){}
        postJson("/playlist/create",j.toString(), new Runnable() {
            @Override public void run() { addSongsToPlaylist(n,s); }
        });
    }

    private void addSongsToPlaylist(String n, List<MusicItem> s) {
        JSONObject j=new JSONObject();
        JSONArray a=new JSONArray();
        for(MusicItem m:s)a.put(m.getPath());
        try{
            j.put("username",session.getUsername());
            j.put("playlist_name",n);
            j.put("songs",a);
        }catch(Exception e){}
        postJson("/playlist/add",j.toString(), new Runnable() {
            @Override public void run() {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        adapter.setSelectionMode(false);
                        fabAdd.show();
                        fetchMusicContent(Config.SERVER_URL,"");
                    }
                });
            }
        });
    }

    private void checkForUpdates() {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    String url = Config.SERVER_URL + "/update/check?t=" + System.currentTimeMillis();
                    Request request = new Request.Builder().url(url).build();
                    Response response = client.newCall(request).execute();
                    if (response.isSuccessful()) {
                        JSONObject json = new JSONObject(response.body().string());
                        final int serverVersion = json.getInt("version");
                        final String serverChangelog = json.optString("changelog", "Millores generals.");
                        if (serverVersion > currentVersionCode) {
                            mainHandler.post(new Runnable() { @Override public void run() { showUpdateDialog(serverVersion); } });
                        } else if (serverVersion == currentVersionCode) {
                            mainHandler.post(new Runnable() { @Override public void run() { checkAndShowChangelog(serverVersion, serverChangelog); } });
                        }
                    }
                } catch (Exception e) {}
            }
        });
    }

    private void checkAndShowChangelog(int version, String logText) {
        android.content.SharedPreferences prefs = getSharedPreferences("ResoNodePrefs", MODE_PRIVATE);
        int lastSeenLogVersion = prefs.getInt("last_changelog_seen", 0);
        if (version > lastSeenLogVersion) {
            final int ver = version;
            new AlertDialog.Builder(this)
                    .setTitle("¡Novetats v" + version + "!")
                    .setMessage(logText)
                    .setCancelable(false)
                    .setPositiveButton("GENIAL", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            getSharedPreferences("ResoNodePrefs", MODE_PRIVATE).edit().putInt("last_changelog_seen", ver).apply();
                            dialog.dismiss();
                        }
                    }).show();
        }
    }

    private void downloadAndInstallUpdate() {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Descargando...");
        pd.show();
        final ProgressDialog finalPd = pd;
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    Request request = new Request.Builder().url(Config.SERVER_URL + "/update/download").build();
                    Response response = client.newCall(request).execute();
                    if(!response.isSuccessful()) throw new Exception("Error");
                    File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
                    InputStream is = response.body().byteStream();
                    FileOutputStream fos = new FileOutputStream(f);
                    byte[] b = new byte[4096]; int l;
                    while((l=is.read(b))!=-1) fos.write(b,0,l);
                    fos.close(); is.close();
                    final File finalF = f;
                    mainHandler.post(new Runnable() { @Override public void run() { finalPd.dismiss(); checkPermissionsAndInstall(finalF); } });
                } catch(Exception e) {
                    mainHandler.post(new Runnable() { @Override public void run() { finalPd.dismiss(); Toast.makeText(MainActivity.this, "Error update", Toast.LENGTH_SHORT).show(); } });
                }
            }
        });
    }

    private void showUpdateDialog(int version) {
        new AlertDialog.Builder(this)
                .setTitle("¡Nova versió disponible! v" + version)
                .setMessage("És necesari instal·lar esta actualizació.")
                .setCancelable(false)
                .setPositiveButton("ACTUALITZAR", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { downloadAndInstallUpdate(); }
                })
                .setNegativeButton("TANCAR", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { finishAffinity(); System.exit(0); }
                }).show();
    }

    private void checkPermissionsAndInstall(File f) {
        pendingApkFile=f;
        if(Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()) {
            updatePermissionLauncher.launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:"+getPackageName())));
        } else {
            finishInstallation(f);
        }
    }

    private void finishInstallation(File f) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(this, getPackageName()+".provider", f);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch(Exception e){}
    }

    private void deleteOfflinePlaylist(final MusicItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Borrar descàrregues")
                .setMessage("Eliminar els arxius deescarregats de '" + item.getName() + "'? (La playlist seguirà al servidor)")
                .setPositiveButton("BORRAR", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        List<String> files = offlineDB.getPlaylistFilePaths(item.getName());

                        for (String path : files) {
                            File f = new File(path);
                            if (f.exists()) f.delete();
                        }

                        File privateDir = getDir("offline_music", Context.MODE_PRIVATE);
                        String safeCoverName = "cover_" + item.getName().replaceAll("[^a-zA-Z0-9.-]", "_") + ".jpg";
                        File cover = new File(privateDir, safeCoverName);
                        if (cover.exists()) cover.delete();

                        offlineDB.deletePlaylist(item.getName());

                        Toast.makeText(MainActivity.this, "Descargàrregues eliminades", Toast.LENGTH_SHORT).show();
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton("CANCEL·LAR", null)
                .show();
    }

    private void handleNetworkChange(boolean isConnected) {

        if (isConnected) {
            if (tvStatus.getVisibility() == View.VISIBLE && tvStatus.getText().toString().contains("Connexió")) {
                tvStatus.setVisibility(View.GONE);
                Toast.makeText(this, "Connexió reestablerta", Toast.LENGTH_SHORT).show();

                if (!currentPath.isEmpty() && !currentPath.startsWith("/")) {
                    fetchMusicContent(Config.SERVER_URL, currentPath);
                }
            }
        } else {
            if (tvStatus.getVisibility() == View.GONE) {
                tvStatus.setText("Mode Sense Connexió");
                tvStatus.setVisibility(View.VISIBLE);
            }
        }
    }

    private void checkAndUninstallOldApp(final String oldPackageName) {
        if (isPackageInstalled(oldPackageName, getPackageManager())) {

            new AlertDialog.Builder(this)
                    .setTitle("Actualització Crítica")
                    .setMessage("S'ha detectat una versió antiga (SpotiFly). És necessari desinstal·lar-la per a continuar. (Les cançons descarregades les hauràs de descarregar de nou a aquesta versió)")
                    .setCancelable(false)
                    .setPositiveButton("DESINSTAL·LAR ARA", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                Uri packageUri = Uri.parse("package:" + oldPackageName);
                                Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                                intent.setData(packageUri);
                                intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                                startActivity(intent);
                            } catch (Exception e) {
                                try {
                                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                    intent.setData(Uri.parse("package:" + oldPackageName));
                                    startActivity(intent);
                                    Toast.makeText(MainActivity.this, "Prem el botó 'Desinstal·lar' en la pantalla que s'ha obert.", Toast.LENGTH_LONG).show();
                                } catch (Exception ex) {
                                    Toast.makeText(MainActivity.this, "Error: No s'ha pogut obrir el desinstal·lador.", Toast.LENGTH_LONG).show();
                                }
                            }
                        }
                    })

                    .setNegativeButton("TANCAR", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                        }
                    })
                    .show();
        }
    }

    private boolean isPackageInstalled(String packageName, PackageManager packageManager) {
        try {
            packageManager.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void updatePlayerIcons() {
        SharedPreferences prefs = getSharedPreferences("ResoNodePrefs", MODE_PRIVATE);
        String playStyle = prefs.getString("play_style", "hud");
        String navStyle = prefs.getString("nav_style", "hud");
        String colorKey = prefs.getString("icon_color", "resonode");

        switch (playStyle) {
            case "hex": playIconRes = R.drawable.ic_play_hex; pauseIconRes = R.drawable.ic_pause_hex; break;
            case "glitch": playIconRes = R.drawable.ic_play_glitch; pauseIconRes = R.drawable.ic_pause_glitch; break;
            case "original": playIconRes = android.R.drawable.ic_media_play; pauseIconRes = android.R.drawable.ic_media_pause; break;
            default: playIconRes = R.drawable.ic_play_hud; pauseIconRes = R.drawable.ic_pause_hud; break;
        }

        int nextIconRes, prevIconRes;
        switch (navStyle) {
            case "hex": nextIconRes = R.drawable.ic_next_hex; prevIconRes = R.drawable.ic_prev_hex; break;
            case "glitch": nextIconRes = R.drawable.ic_next_glitch; prevIconRes = R.drawable.ic_prev_glitch; break;
            case "original": nextIconRes = android.R.drawable.ic_media_next; prevIconRes = android.R.drawable.ic_media_previous; break;
            default: nextIconRes = R.drawable.ic_next_hud; prevIconRes = R.drawable.ic_prev_hud; break;
        }
        if (btnNext != null) btnNext.setImageResource(nextIconRes);
        if (btnPrev != null) btnPrev.setImageResource(prevIconRes);

        int tintColor;
        switch (colorKey) {
            case "white": tintColor = 0xFFFFFFFF; break;
            case "green": tintColor = 0xFF1DB954; break;
            case "cyan": tintColor = 0xFF03F8A7; break;
            case "cyber_yellow": tintColor = 0xFFFCEE09; break;
            case "cyber_red": tintColor = 0xFFFF003C; break;
            default: tintColor = 0xFFF2B327; break;
        }

        if (btnMiniPlay != null) btnMiniPlay.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
        if (btnFullPlay != null) btnFullPlay.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
        if (btnNext != null) btnNext.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
        if (btnPrev != null) btnPrev.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
        if (btnClosePlayer != null) {
            btnClosePlayer.setImageResource(playIconRes);
            btnClosePlayer.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        if (seekBar != null) {
            try {
                android.graphics.drawable.Drawable progressDrawable = seekBar.getProgressDrawable().mutate();
                progressDrawable.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
                seekBar.setProgressDrawable(progressDrawable);

                if (Build.VERSION.SDK_INT >= 16) {
                    android.graphics.drawable.Drawable thumb = seekBar.getThumb().mutate();
                    thumb.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
                    seekBar.setThumb(thumb);
                }
            } catch (Exception e) {}
        }

        TextView tvTitle = findViewById(R.id.tv_custom_title);
        if (tvTitle != null) {
            tvTitle.setTextColor(tintColor);
        }

        if (fabAdd != null) {
            fabAdd.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tintColor));
        }

        if (tvToolbarAction != null) {
            tvToolbarAction.setTextColor(tintColor);
        }

        if (btnBulkMore != null) {
            btnBulkMore.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }

        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(tintColor);
        }

        boolean isPlaying = false;
        if (isBound && musicService != null) isPlaying = musicService.isPlaying();
        updatePlayIcons(isPlaying);
    }
    private void updatePlayIcons(boolean isPlaying) {
        int resId = isPlaying ? pauseIconRes : playIconRes;
        if (btnMiniPlay != null) btnMiniPlay.setImageResource(resId);
        if (btnFullPlay != null) btnFullPlay.setImageResource(resId);
    }

    private String[] getAudioMetadata(Uri uri) {
        String title = "Arxiu Desconegut";
        String artist = "Dispositiu Local";
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String metaTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
            String metaArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);

            if (metaTitle != null && !metaTitle.trim().isEmpty()) {
                title = metaTitle;
            } else {
                android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) title = cursor.getString(nameIndex);
                    cursor.close();
                }
            }
            if (metaArtist != null && !metaArtist.trim().isEmpty()) {
                artist = metaArtist;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { retriever.release(); } catch (Exception e) {}
        }
        return new String[]{title, artist};
    }

    private void handlePendingIntent() {
        if (pendingIntentToHandle != null && Intent.ACTION_VIEW.equals(pendingIntentToHandle.getAction())) {
            Uri audioUri = pendingIntentToHandle.getData();
            if (audioUri != null && isBound && musicService != null) {

                String[] meta = getAudioMetadata(audioUri);
                String title = meta[0];
                String artist = meta[1];

                MusicItem externalItem = new MusicItem(title, "file", audioUri.toString(), artist);
                List<MusicItem> temp = new ArrayList<>();
                temp.add(externalItem);
                currentSongIndex = 0;
                musicService.playUrl(audioUri.toString(), temp, 0, session.getUsername());

                if (fullPlayerLayout != null) {
                    fullPlayerLayout.setVisibility(View.VISIBLE);
                }
            }
            pendingIntentToHandle = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingIntentToHandle = intent;
        handlePendingIntent();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        boolean wasFullPlayerOpen = (fullPlayerLayout != null && fullPlayerLayout.getVisibility() == View.VISIBLE);
        int currentAdapterMode = (adapter != null) ? adapter.getMode() : PlaylistAdapter.MODE_PRIVATE;

        setContentView(R.layout.activity_main);

        if (seekBarHandler != null) seekBarHandler.removeCallbacksAndMessages(null);

        setupUI();
        setupPlayer();
        updatePlayerIcons();

        if (adapter != null) adapter.setMode(currentAdapterMode);

        if (wasFullPlayerOpen && fullPlayerLayout != null) {
            fullPlayerLayout.setVisibility(View.VISIBLE);
        }

        if (isBound && musicService != null && musicService.getCurrentSong() != null) {
            updatePlayerUI(musicService.getCurrentSong(), musicService.isPlaying());
        }

        boolean isVault = (currentAdapterMode == PlaylistAdapter.MODE_VAULT);
        updateTitleAndFab(currentPath, isVault);
        updateHeaderImage();
        if (drawerLayout != null) {
            androidx.core.view.ViewCompat.requestApplyInsets(drawerLayout);
        }
    }

    private void showPlaylistSelectorForLocalSong(final Uri audioUri, final String title, final String artist) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    Response r = client.newCall(new Request.Builder().url(Config.SERVER_URL + "/browse?username=" + session.getUsername() + "&folder=").build()).execute();
                    if(r.isSuccessful()) {
                        JSONArray a = new JSONObject(r.body().string()).getJSONArray("items");
                        final List<String> pList = new ArrayList<>();
                        for (int x = 0; x < a.length(); x++) {
                            if (a.getJSONObject(x).getString("type").equals("folder")) {
                                pList.add(a.getJSONObject(x).getString("name"));
                            }
                        }

                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (pList.isEmpty()) {
                                    Toast.makeText(MainActivity.this, "Crea primer una playlist per afegir cançons!", Toast.LENGTH_LONG).show();
                                    return;
                                }

                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("On vols afegir-la?")
                                        .setItems(pList.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                            @Override public void onClick(DialogInterface d, int w) {
                                                String selectedPlaylist = pList.get(w);

                                                offlineDB.saveSong(audioUri.toString(), title, selectedPlaylist, audioUri.toString(), artist);
                                                Toast.makeText(MainActivity.this, "Afegida a " + selectedPlaylist, Toast.LENGTH_SHORT).show();

                                                if (currentPath.equals(selectedPlaylist)) {
                                                    fetchMusicContent(Config.SERVER_URL, currentPath);
                                                }
                                            }
                                        }).show();
                            }
                        });
                    }
                } catch(Exception e) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "Error carregant les playlists", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }
}
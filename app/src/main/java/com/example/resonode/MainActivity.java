package com.example.resonode;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
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

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    private DrawerLayout drawerLayout;
    private NetworkReceiver networkReceiver;
    private RecyclerView recyclerView;
    private TextView tvStatus, tvToolbarAction;
    private FloatingActionButton fabAdd;
    private ImageButton btnSearch;
    private SwipeRefreshLayout swipeRefresh;
    private ImageView ivPlaylistHeader;

    private LinearLayout miniPlayerLayout, fullPlayerLayout;
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

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.LocalBinder binder = (MusicService.LocalBinder) service;
            musicService = binder.getService();
            isBound = true;
            musicService.setCallback(new MusicService.OnSongChangedListener() {
                @Override public void onSongChanged(final MusicItem song, final boolean isPlaying) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { updatePlayerUI(song, isPlaying); }
                    });
                }
                @Override public void onPlaybackStateChanged(final boolean isPlaying) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { updatePlayIcons(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play); }
                    });
                }
            });
            MusicItem savedSong = musicService.getCurrentSong();
            if (savedSong != null) {
                updatePlayerUI(savedSong, musicService.isPlaying());
                if (!musicService.isPlaying()) updatePlayIcons(android.R.drawable.ic_media_play);
                fullPlayerLayout.setVisibility(View.GONE);
            }
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
                            showPlaylistSelectorForVaultItem(pathId);
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
            errorView.setText("ERROR DE DISEÑO:\n" + e.getMessage());
            errorView.setTextColor(android.graphics.Color.RED);
            setContentView(errorView);
            return;
        }

        session = new SessionManager(this);
        if (!session.isLoggedIn()) { logoutUser(); return; }

        setupUI();
        setupPlayer();

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
    }

    private void fetchMusicContent(final String baseUrl, final String folderPath) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(true);
                tvStatus.setVisibility(View.GONE);
            }
        });

        final String username = session.getUsername();
        String tempEncodedPath = "";
        try { tempEncodedPath = URLEncoder.encode(folderPath, "UTF-8"); } catch (Exception e) { tempEncodedPath = folderPath; }
        final String encodedPath = tempEncodedPath;

        final String targetUrl = baseUrl + "/browse?username=" + username + "&folder=" + encodedPath;
        final String currentFolderRequest = folderPath;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Request request = new Request.Builder().url(targetUrl).build();
                    Response response = client.newCall(request).execute();

                    if (response.code() == 403) throw new Exception("Clave Incorrecta");
                    if (!response.isSuccessful()) throw new Exception("Error " + response.code());

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
                                musicList.clear();
                                musicList.addAll(temp);

                                updateTitleAndFab(currentPath, finalIsVault);

                                if (adapter != null) adapter.setCurrentPath(currentPath);

                                if (ivPlaylistHeader != null) {
                                    if (currentPath.isEmpty()) {
                                        ivPlaylistHeader.setVisibility(View.GONE);
                                    } else {
                                        ivPlaylistHeader.setVisibility(View.VISIBLE);
                                        String headerUrl = "";
                                        try {
                                            String encodedHeaderPath = URLEncoder.encode(currentPath, "UTF-8");
                                            headerUrl = Config.SERVER_URL + "/cover?username=" + session.getUsername() + "&path=" + encodedHeaderPath;
                                        } catch (Exception e) {}

                                        Glide.with(MainActivity.this)
                                                .load(headerUrl)
                                                .placeholder(R.mipmap.ic_launcher)
                                                .error(R.mipmap.ic_launcher)
                                                .circleCrop()
                                                .into(ivPlaylistHeader);
                                    }
                                }

                                if (currentPath.equals("General") || currentPath.startsWith("General/")) adapter.setMode(PlaylistAdapter.MODE_PUBLIC);
                                else if (finalIsVault) adapter.setMode(PlaylistAdapter.MODE_VAULT);
                                else adapter.setMode(PlaylistAdapter.MODE_PRIVATE);

                                adapter.notifyDataSetChanged();

                            } catch (Throwable t) {
                                android.util.Log.e("UI_ERROR", "Error pintando lista", t);
                                tvStatus.setText("Error visual: " + t.getMessage());
                                tvStatus.setVisibility(View.VISIBLE);
                            }
                        }
                    });

                } catch (final Exception e) {
                    android.util.Log.e("Offline", "Fallo red, cargando offline: " + e.getMessage());
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            loadOfflineContent(currentFolderRequest);
                            if (musicList.isEmpty()) {
                                tvStatus.setText("Sin Conexión");
                                tvStatus.setVisibility(View.VISIBLE);
                                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                            }
                        }
                    });
                }
            }
        });
    }

    private void loadOfflineContent(final String folderPath) {
        final List<MusicItem> offlineItems = new ArrayList<>();
        final String displayTitle;

        if (folderPath.isEmpty()) {
            offlineItems.addAll(offlineDB.getOfflinePlaylists());
            currentPath = "";
            displayTitle = "Modo Offline";
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
                tvStatus.setText("Modo Sin Conexión");
                tvStatus.setVisibility(View.VISIBLE);

                if (adapter != null) adapter.setCurrentPath(currentPath);

                if (ivPlaylistHeader != null) ivPlaylistHeader.setVisibility(View.GONE);

                adapter.setMode(PlaylistAdapter.MODE_PRIVATE);
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void updateTitleAndFab(String path, boolean isVault) {
        String title = path.isEmpty() ? "ResoNode" : path;
        if (title.startsWith("General")) title = "Playlists Públicas";
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(title);
        if (path.isEmpty()) fabAdd.show(); else fabAdd.hide();
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
            Toast.makeText(this, "Error al reproducir", Toast.LENGTH_SHORT).show();
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

        updatePlayIcons(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

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
                    .circleCrop()
                    .into(realMiniCover);
        }
    }

    private void updatePlayIcons(int resId) {
        btnMiniPlay.setImageResource(resId);
        btnFullPlay.setImageResource(resId);
    }

    private String formatTime(int ms) {
        int s = (ms/1000)%60;
        int m = (ms/(1000*60))%60;
        return String.format("%d:%02d", m, s);
    }

    private void setupUI() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        drawerLayout = findViewById(R.id.drawer_layout);
        NavigationView navView = findViewById(R.id.nav_view);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
        navView.setNavigationItemSelectedListener(this);
        View header = navView.getHeaderView(0);
        TextView tvUser = header.findViewById(R.id.tv_header_user);
        if(tvUser != null) tvUser.setText(session.getUsername());

        tvStatus = findViewById(R.id.tv_status);
        tvToolbarAction = findViewById(R.id.tv_toolbar_action);
        btnSearch = findViewById(R.id.btn_search);
        recyclerView = findViewById(R.id.recycler_view_playlists);
        fabAdd = findViewById(R.id.fab_add);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        ivPlaylistHeader = findViewById(R.id.iv_playlist_header);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        swipeRefresh.setColorSchemeColors(0xFF1DB954);
        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                retryHandler.removeCallbacks(retryRunnable);
                fetchMusicContent(Config.SERVER_URL, currentPath);
            }
        });
        retryRunnable = new Runnable() {
            @Override public void run() { fetchMusicContent(Config.SERVER_URL, currentPath); }
        };

        adapter = new PlaylistAdapter(this, musicList, PlaylistAdapter.MODE_PRIVATE,
                new PlaylistAdapter.OnItemClickListener() {
                    @Override
                    public void onItemClick(MusicItem item) {
                        if (item.isFolder()) {
                            String target = item.getPath();
                            if (target.equals("Playlists Públicas")) target = "General";
                            fetchMusicContent(Config.SERVER_URL, target);
                        } else {
                            currentSongIndex = musicList.indexOf(item);
                            playServiceStream(item);
                        }
                    }
                },
                new PlaylistAdapter.OnItemMenuClickListener() {
                    @Override
                    public void onMenuClick(MusicItem item, String action) {
                        if (action.equals("Eliminar")) deleteItem(item);
                        else if (action.equals("Renombrar")) showRenameDialog(item);
                        else if (action.equals("Añadir a Playlist")) showPlaylistSelectorForVaultItem(item.getPath());
                        else if (action.equals("Descargar Offline")) downloadPlaylist(item);
                        else if (action.equals("Borrar Offline")) deleteOfflinePlaylist(item);
                        else if (action.equals("Cambiar Portada")) {
                            playlistCoverTarget = item;
                            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.setType("image/*");
                            pickImageLauncher.launch(Intent.createChooser(intent, "Selecciona una portada"));
                        }
                    }
                }
        );

        adapter.setOnSelectionChangedListener(new PlaylistAdapter.OnSelectionChangedListener() {
            @Override
            public void onSelectionChanged(int count) {
                if (count > 0) {
                    tvToolbarAction.setVisibility(View.VISIBLE);
                    tvToolbarAction.setText("AÑADIR (" + count + ")");
                    btnSearch.setVisibility(View.GONE);
                    fabAdd.hide();
                } else {
                    tvToolbarAction.setVisibility(View.GONE);
                    btnSearch.setVisibility(View.VISIBLE);
                    if(currentPath.isEmpty()) fabAdd.show();
                }
            }
        });
        recyclerView.setAdapter(adapter);

        fabAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handleFabClick(); }
        });

        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { searchLauncher.launch(new Intent(MainActivity.this, SearchActivity.class)); }
        });

        tvToolbarAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<MusicItem> selected = adapter.getSelectedItems();
                if (!selected.isEmpty()) showAddToPlaylistDialog(selected);
            }
        });
    }

    private void downloadPlaylist(final MusicItem playlistItem) {
        if (offlineDB.isPlaylistDownloaded(playlistItem.getName())) {
            Toast.makeText(this, "Esta playlist ya está descargada", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Iniciando descarga...", Toast.LENGTH_SHORT).show();

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

                    if (!responseList.isSuccessful()) throw new Exception("Error al leer playlist");

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
                        android.util.Log.e("DL_COVER", "No se pudo bajar portada: " + e.getMessage());
                    }

                    int total = 0;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject o = items.getJSONObject(i);
                        if (!o.getString("type").equals("file")) continue;

                        String serverPath = o.getString("path");
                        String name = o.getString("name");
                        String artist = o.optString("artist", "Desconocido");

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
                            Toast.makeText(MainActivity.this, "Descargadas " + count + " canciones", Toast.LENGTH_LONG).show();
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
            progressDrawable.setColorFilter(0xFF1DB954, android.graphics.PorterDuff.Mode.SRC_IN);
            seekBar.setProgressDrawable(progressDrawable);

            if (Build.VERSION.SDK_INT >= 16) {
                android.graphics.drawable.Drawable thumb = seekBar.getThumb().mutate();
                thumb.setColorFilter(0xFF1DB954, android.graphics.PorterDuff.Mode.SRC_IN);
                seekBar.setThumb(thumb);
            }
        } catch (Exception e) {}

        miniPlayerLayout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fullPlayerLayout.setVisibility(View.VISIBLE); }
        });
        btnClosePlayer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fullPlayerLayout.setVisibility(View.GONE); }
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

    private void uploadCoverImage(Uri imageUri, final MusicItem playlist) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Subiendo portada...");
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
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                finalPd.dismiss();
                                Toast.makeText(MainActivity.this, "¡Portada Actualizada!", Toast.LENGTH_SHORT).show();
                                adapter.notifyDataSetChanged();
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
        String[] opts = {"Crear Playlist", "Añadir de Playlist Pública", "Buscar en Bóveda"};
        new AlertDialog.Builder(this).setItems(opts, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                if(w==0) showCreatePlaylistDialog();
                else if(w==1) goToGeneralAndSelect();
                else searchLauncher.launch(new Intent(MainActivity.this, SearchActivity.class));
            }
        }).show();
    }

    private void goToGeneralAndSelect() {
        fetchMusicContent(Config.SERVER_URL, "General");
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() { if(adapter!=null) { adapter.setSelectionMode(true); fabAdd.hide(); } }
        }, 500);
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
        else if(!currentPath.isEmpty() && !currentPath.equals("General")) {
            File f=new File(currentPath);
            String p=f.getParent();
            if(p==null)p="";
            fetchMusicContent(Config.SERVER_URL, p.replace("\\","/"));
        } else super.onBackPressed();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem i) {
        if(i.getItemId()==R.id.nav_home) fetchMusicContent(Config.SERVER_URL,"");
        else if(i.getItemId()==R.id.nav_logout) logoutUser();
        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
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
                .setTitle("Nueva")
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

    private void showPlaylistSelectorForVaultItem(final String p) {
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
                                    @Override public void onClick(DialogInterface d, int w) { addToSpecificPlaylist(l.get(w),p); }
                                }).show();
                            }
                        });
                    }
                }catch(Exception e){}
            }
        });
    }

    private void addToSpecificPlaylist(String n, String p) {
        JSONObject j=new JSONObject();
        try{
            j.put("username",session.getUsername());
            j.put("playlist_name",n);
            JSONArray a=new JSONArray();
            a.put(p);
            j.put("items",a);
        }catch(Exception e){}
        postJson("/playlist/add_from_vault",j.toString(), new Runnable() {
            @Override public void run() {
                mainHandler.post(new Runnable() {
                    @Override public void run() { Toast.makeText(MainActivity.this,"Añadido",Toast.LENGTH_SHORT).show(); }
                });
            }
        });
    }

    private void showAddToPlaylistDialog(final List<MusicItem> l) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try{
                    Response r=client.newCall(new Request.Builder().url(Config.SERVER_URL+"/browse?username="+session.getUsername()+"&folder=").build()).execute();
                    if(r.isSuccessful()){
                        JSONArray a=new JSONObject(r.body().string()).getJSONArray("items");
                        final List<String> n=new ArrayList<>();
                        n.add("Nueva...");
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
                        final String serverChangelog = json.optString("changelog", "Mejoras generales.");
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
                    .setTitle("¡Novedades v" + version + "!")
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
                .setTitle("¡Nueva versión disponible! v" + version)
                .setMessage("Es necesario instalar esta actualización.")
                .setCancelable(false)
                .setPositiveButton("ACTUALIZAR", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { downloadAndInstallUpdate(); }
                })
                .setNegativeButton("CERRAR", new DialogInterface.OnClickListener() {
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
                .setTitle("Borrar descargas")
                .setMessage("¿Eliminar los archivos descargados de '" + item.getName() + "'? (La playlist seguirá en el servidor)")
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

                        Toast.makeText(MainActivity.this, "Descargas eliminadas", Toast.LENGTH_SHORT).show();
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton("CANCELAR", null)
                .show();
    }

    private void handleNetworkChange(boolean isConnected) {

        if (isConnected) {
            if (tvStatus.getVisibility() == View.VISIBLE && tvStatus.getText().toString().contains("Conexión")) {
                tvStatus.setVisibility(View.GONE);
                Toast.makeText(this, "Conexión restablecida", Toast.LENGTH_SHORT).show();

                if (!currentPath.isEmpty() && !currentPath.startsWith("/")) {
                    fetchMusicContent(Config.SERVER_URL, currentPath);
                }
            }
        } else {
            if (tvStatus.getVisibility() == View.GONE) {
                tvStatus.setText("Modo Sin Conexión");
                tvStatus.setVisibility(View.VISIBLE);
            }
        }
    }
}
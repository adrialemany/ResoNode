package com.example.resonode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MusicService extends Service implements AudioManager.OnAudioFocusChangeListener {

    private static final String CHANNEL_ID = "ResoNodeChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "ResoNodeState";

    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private final IBinder binder = new LocalBinder();

    private List<MusicItem> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private String currentUsername = "";
    private boolean resumeOnFocusGain = false;

    private int failureCount = 0;
    private static final int MAX_RETRIES = 3;

    private OnSongChangedListener callback;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private File tempFile = null;

    
    private OfflineDB offlineDB;

    private OkHttpClient createClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
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

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                if (mediaPlayer != null && !mediaPlayer.isPlaying() && currentIndex != -1) {
                    resumeMusic();
                }
            }
            else if (android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action) ||
                    android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(action)) {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    pauseMusic();
                }
            }
        }
    };

    public interface OnSongChangedListener {
        void onSongChanged(MusicItem song, boolean isPlaying);
        void onPlaybackStateChanged(boolean isPlaying);
    }

    public class LocalBinder extends Binder {
        MusicService getService() { return MusicService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        
        offlineDB = new OfflineDB(this);

        createNotificationChannel();
        showPlaceholderNotification();

        initializePlayer();
        initializeMediaSession();
        restoreState();

        IntentFilter filter = new IntentFilter();
        filter.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(bluetoothReceiver, filter);
    }

    private void showPlaceholderNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("ResoNode")
                        .setContentText("Servicio activo")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .build();
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void initializePlayer() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                failureCount = 0;
                mp.start();
                updateNotification();
                saveState();
                if (callback != null && currentIndex != -1 && currentIndex < playlist.size()) {
                    callback.onSongChanged(playlist.get(currentIndex), true);
                    callback.onPlaybackStateChanged(true);
                }
            }
        });

        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer mp) {
                
                
                playNext();
            }
        });

        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                
                handlePlaybackError();
                return true;
            }
        });
    }

    private void handlePlaybackError() {
        failureCount++;
        
        if (failureCount < MAX_RETRIES) {
            playNext();
        } else {
            failureCount = 0;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { Toast.makeText(getApplicationContext(), "Error reproducción", Toast.LENGTH_SHORT).show(); }
            });
            if (callback != null) callback.onPlaybackStateChanged(false);
        }
    }

    private void initializeMediaSession() {
        mediaSession = new MediaSessionCompat(this, "MusicService");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { super.onPlay(); resumeMusic(); }
            @Override public void onPause() { super.onPause(); pauseMusic(); }
            @Override public void onSkipToNext() { super.onSkipToNext(); playNextByUser(); }
            @Override public void onSkipToPrevious() { super.onSkipToPrevious(); playPrev(); }
            @Override public void onSeekTo(long pos) {
                super.onSeekTo(pos);
                if(mediaPlayer != null) mediaPlayer.seekTo((int) pos);
                updateNotification();
            }
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (event.getKeyCode()) {
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                            if (mediaPlayer.isPlaying()) pauseMusic(); else resumeMusic();
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PLAY: resumeMusic(); return true;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE: pauseMusic(); return true;
                        case KeyEvent.KEYCODE_MEDIA_NEXT: playNextByUser(); return true;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS: playPrev(); return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent);
            }
        });

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(this, MusicService.class);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, mediaButtonIntent,
                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);
        mediaSession.setMediaButtonReceiver(pendingIntent);
        mediaSession.setActive(true);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                if (isPlaying()) { pauseMusic(); resumeOnFocusGain = false; } break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (isPlaying()) { pauseMusic(); resumeOnFocusGain = true; } break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (resumeOnFocusGain) { resumeMusic(); resumeOnFocusGain = false; } break;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mediaSession, intent);
        if (intent != null && intent.getAction() != null) {
            if ("ACTION_NEXT".equals(intent.getAction())) playNextByUser();
            else if ("ACTION_PREV".equals(intent.getAction())) playPrev();
            else if ("ACTION_PLAY_PAUSE".equals(intent.getAction())) {
                if (isPlaying()) pauseMusic(); else resumeMusic();
            } else if ("ACTION_CLOSE".equals(intent.getAction())) {
                stopForeground(true);
                stopSelf();
                System.exit(0);
            }
        }
        return START_NOT_STICKY;
    }

    public void playUrl(String url, List<MusicItem> list, int index, String username) {
        this.failureCount = 0;
        this.playlist = new ArrayList<>(list);
        this.currentIndex = index;
        this.currentUsername = username;

        
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            playInternal(playlist.get(currentIndex));
        }
    }

    
    private void playInternal(MusicItem item) {
        try {
            String urlToPlay = "";
            boolean isLocal = false;

            
            if (item.getPath().startsWith("/")) {
                urlToPlay = item.getPath();
                isLocal = true;
            }
            
            else {
                
                boolean hasInternet = NetworkReceiver.isConnected(getApplicationContext());

                
                String localPath = findLocalPath(item);

                if (!hasInternet) {
                    
                    if (localPath != null) {
                        urlToPlay = localPath;
                        isLocal = true;
                        android.util.Log.d("MusicService", "Offline: Cambiando a archivo local -> " + localPath);
                    } else {
                        
                        Toast.makeText(getApplicationContext(), "No disponible offline", Toast.LENGTH_SHORT).show();
                        handlePlaybackError();
                        return;
                    }
                } else {
                    
                    
                    
                    String encodedPath = URLEncoder.encode(item.getPath(), "UTF-8");
                    urlToPlay = Config.SERVER_URL + "/stream?username=" + currentUsername + "&path=" + encodedPath;
                }
            }

            
            requestAudioFocusAndPlay(urlToPlay, item, isLocal);

        } catch(Exception e){
            handlePlaybackError();
        }
    }

    
    private String findLocalPath(MusicItem item) {
        if (offlineDB == null) return null;
        try {
            
            List<MusicItem> playlists = offlineDB.getOfflinePlaylists();
            for (MusicItem pl : playlists) {
                List<MusicItem> songs = offlineDB.getSongsInPlaylist(pl.getName());
                for (MusicItem s : songs) {
                    
                    if (s.getPath().equals(item.getPath()) || s.getName().equals(item.getName())) {
                        File f = new File(s.getPath()); 
                        if (f.exists()) return s.getPath();
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private void requestAudioFocusAndPlay(final String url, final MusicItem originalItem, final boolean isLocalFile) {
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            try {
                mediaPlayer.reset();

                if (!isLocalFile && url.startsWith("http")) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("x-secret-key", Config.API_SECRET_KEY);
                    mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(url), headers);
                } else {
                    mediaPlayer.setDataSource(url);
                }

                
                mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        
                        if (!isLocalFile) {
                            String localPath = findLocalPath(originalItem);
                            if (localPath != null) {
                                android.util.Log.d("MusicService", "Error Stream -> Fallback Local");
                                requestAudioFocusAndPlay(localPath, originalItem, true);
                                return true; 
                            }
                        }
                        handlePlaybackError();
                        return true;
                    }
                });

                mediaPlayer.prepareAsync();

            } catch (Exception e) {
                
                if (!isLocalFile) {
                    String localPath = findLocalPath(originalItem);
                    if (localPath != null) {
                        requestAudioFocusAndPlay(localPath, originalItem, true);
                        return;
                    }
                }
                handlePlaybackError();
            }
        }
    }

    public void resumeMusic() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            if (audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mediaPlayer.start();
                updateNotification();
                if (callback != null) callback.onPlaybackStateChanged(true);
            }
        }
    }

    public void pauseMusic() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updateNotification();
            saveState();
            if (callback != null) callback.onPlaybackStateChanged(false);
        }
    }

    public void playNextByUser() {
        failureCount = 0;
        playNext();
    }

    public void playNext() {
        if (playlist.isEmpty()) return;
        int nextIndex = (currentIndex + 1) % playlist.size();
        MusicItem nextItem = playlist.get(nextIndex);

        
        if (nextItem.isFolder()) {
            currentIndex = nextIndex;
            playNext();
            return;
        }

        currentIndex = nextIndex;
        
        playInternal(nextItem);
    }

    public void playPrev() {
        failureCount = 0;
        if (playlist.isEmpty()) return;
        int prevIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
        MusicItem prevItem = playlist.get(prevIndex);

        if (prevItem.isFolder()) {
            currentIndex = prevIndex;
            playPrev();
            return;
        }

        currentIndex = prevIndex;
        playInternal(prevItem);
    }

    private void saveState() {
        if (playlist.isEmpty() || currentIndex == -1) return;
        final int saveIndex = currentIndex;
        final int savePos = (mediaPlayer != null) ? mediaPlayer.getCurrentPosition() : 0;
        final String saveUser = currentUsername;
        final List<MusicItem> saveList = new ArrayList<>(playlist);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putInt("last_index", saveIndex);
                    editor.putInt("last_position", savePos);
                    editor.putString("last_username", saveUser);
                    JSONArray jsonArray = new JSONArray();
                    for (MusicItem item : saveList) {
                        JSONObject obj = new JSONObject();
                        try {
                            obj.put("name", item.getName());
                            obj.put("type", item.getType());
                            obj.put("path", item.getPath());
                            obj.put("artist", item.getArtist());
                            jsonArray.put(obj);
                        } catch (Exception e) {}
                    }
                    editor.putString("last_playlist", jsonArray.toString());
                    editor.apply();
                } catch (Exception e) {}
            }
        }).start();
    }

    private void restoreState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String playlistJson = prefs.getString("last_playlist", null);
        if (playlistJson != null) {
            try {
                JSONArray jsonArray = new JSONArray(playlistJson);
                playlist.clear();
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    playlist.add(new MusicItem(obj.getString("name"), obj.getString("type"), obj.getString("path"), obj.optString("artist", "")));
                }
                currentIndex = prefs.getInt("last_index", 0);
                currentUsername = prefs.getString("last_username", "");
            } catch (Exception e) {}
        }
    }

    public boolean isPlaying() { return mediaPlayer != null && mediaPlayer.isPlaying(); }
    public int getCurrentPosition() { return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0; }
    public int getDuration() { return mediaPlayer != null ? mediaPlayer.getDuration() : 0; }
    public void seekTo(int pos) { if(mediaPlayer != null) mediaPlayer.seekTo(pos); }
    public MusicItem getCurrentSong() { return (currentIndex != -1 && !playlist.isEmpty()) ? playlist.get(currentIndex) : null; }
    public void setCallback(OnSongChangedListener callback) { this.callback = callback; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "ResoNode Player", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void updateNotification() {
        if (currentIndex == -1 || playlist.isEmpty()) return;
        final MusicItem item = playlist.get(currentIndex);

        String artUrl = "";
        File localCover = null;

        
        if (item.getPath().startsWith("/")) {
            
            if (offlineDB != null) {
                String originalPath = offlineDB.getServerPathForLocalFile(item.getPath());
                if (originalPath != null) {
                    try { artUrl = Config.SERVER_URL + "/cover?username=" + currentUsername + "&path=" + URLEncoder.encode(originalPath, "UTF-8"); } catch (Exception e) {}
                }
                
                List<MusicItem> playlists = offlineDB.getOfflinePlaylists();
                for(MusicItem pl : playlists) {
                    List<MusicItem> songs = offlineDB.getSongsInPlaylist(pl.getName());
                    for(MusicItem s : songs) {
                        if(s.getPath().equals(item.getPath()) || s.getName().equals(item.getName())) {
                            File dir = getDir("offline_music", Context.MODE_PRIVATE);
                            localCover = new File(dir, "cover_" + pl.getName().replaceAll("[^a-zA-Z0-9.-]", "_") + ".jpg");
                            break;
                        }
                    }
                    if(localCover != null && localCover.exists()) break;
                }
            }
        } else {
            try { artUrl = Config.SERVER_URL + "/cover?username=" + currentUsername + "&path=" + URLEncoder.encode(item.getPath(), "UTF-8"); } catch (Exception e) {}
        }

        final Bitmap defaultIcon = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
        final File finalLocalCover = localCover;

        Glide.with(this)
                .asBitmap()
                .load(artUrl)
                .error(Glide.with(this).asBitmap().load(finalLocalCover)) 
                .placeholder(R.mipmap.ic_launcher)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        renderNotification(item, resource);
                    }
                    @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                    @Override
                    public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                        renderNotification(item, defaultIcon);
                    }
                });
    }

    private void renderNotification(MusicItem item, Bitmap largeIcon) {
        if (item == null) return;

        String cleanTitle = item.getName()
                .replace(".mp3", "").replace(".MP3", "")
                .replaceAll("^(\\d+[\\s_\\-]*)+", "");

        String artistName = item.getArtist();
        if (artistName == null || artistName.isEmpty()) artistName = "ResoNode Music";

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openAppIntent,
                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent prevI = new Intent(this, MusicService.class).setAction("ACTION_PREV");
        PendingIntent pPrev = PendingIntent.getService(this, 1, prevI, Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent playI = new Intent(this, MusicService.class).setAction("ACTION_PLAY_PAUSE");
        PendingIntent pPlay = PendingIntent.getService(this, 2, playI, Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent nextI = new Intent(this, MusicService.class).setAction("ACTION_NEXT");
        PendingIntent pNext = PendingIntent.getService(this, 3, nextI, Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent closeI = new Intent(this, MusicService.class).setAction("ACTION_CLOSE");
        PendingIntent pClose = PendingIntent.getService(this, 4, closeI, Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);

        int playIcon = mediaPlayer.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, cleanTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, largeIcon)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer.getDuration())
                .build());

        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(mediaPlayer.isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, mediaPlayer.getCurrentPosition(), 1f)
                .setActions(PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build());

        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(cleanTitle)
                .setContentText(artistName)
                .setLargeIcon(largeIcon)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .addAction(android.R.drawable.ic_media_previous, "Prev", pPrev)
                .addAction(playIcon, "Play", pPlay)
                .addAction(android.R.drawable.ic_media_next, "Next", pNext)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", pClose)
                .setOngoing(mediaPlayer.isPlaying());

        startForeground(NOTIFICATION_ID, notification.build());
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(bluetoothReceiver); } catch (Exception e) {}

        saveState();
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        if (mediaSession != null) { mediaSession.release(); }
        if (tempFile != null && tempFile.exists()) { tempFile.delete(); }
    }
}
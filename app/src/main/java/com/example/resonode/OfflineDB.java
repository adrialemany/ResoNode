package com.example.resonode;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class OfflineDB extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 3;
    private static final String DATABASE_NAME = "offline.db";

    private static final String TABLE_SONGS = "songs";
    private static final String KEY_ID = "id";
    private static final String KEY_SERVER_PATH = "server_path";
    private static final String KEY_NAME = "name";
    private static final String KEY_PLAYLIST = "playlist_name";
    private static final String KEY_FILE_PATH = "file_path";
    private static final String KEY_ARTIST = "artist";

    private static final String TABLE_HISTORY = "playback_history";
    private static final String KEY_TIMESTAMP = "timestamp";
    private static final String KEY_DURATION = "duration";
    private static final String KEY_SYNCED = "synced";

    public OfflineDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_SONGS_TABLE = "CREATE TABLE " + TABLE_SONGS + "("
                + KEY_ID + " INTEGER PRIMARY KEY,"
                + KEY_SERVER_PATH + " TEXT,"
                + KEY_NAME + " TEXT,"
                + KEY_PLAYLIST + " TEXT,"
                + KEY_FILE_PATH + " TEXT,"
                + KEY_ARTIST + " TEXT" + ")";
        db.execSQL(CREATE_SONGS_TABLE);

        String CREATE_HISTORY_TABLE = "CREATE TABLE " + TABLE_HISTORY + "("
                + KEY_ID + " INTEGER PRIMARY KEY,"
                + KEY_TIMESTAMP + " INTEGER,"
                + KEY_NAME + " TEXT,"
                + KEY_ARTIST + " TEXT,"
                + KEY_DURATION + " INTEGER,"
                + KEY_SYNCED + " INTEGER" + ")";
        db.execSQL(CREATE_HISTORY_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SONGS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_HISTORY);
        onCreate(db);
    }


    public void saveSong(String serverPath, String name, String playlist, String filePath, String artist) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_SERVER_PATH, serverPath);
        values.put(KEY_NAME, name);
        values.put(KEY_PLAYLIST, playlist);
        values.put(KEY_FILE_PATH, filePath);
        values.put(KEY_ARTIST, artist);
        db.insert(TABLE_SONGS, null, values);
        db.close();
    }

    public List<MusicItem> getOfflinePlaylists() {
        List<MusicItem> playlists = new ArrayList<>();
        String selectQuery = "SELECT DISTINCT " + KEY_PLAYLIST + " FROM " + TABLE_SONGS;
        SQLiteDatabase db = this.getReadableDatabase();
        try {
            Cursor cursor = db.rawQuery(selectQuery, null);
            if (cursor.moveToFirst()) {
                do {
                    String plName = cursor.getString(0);
                    MusicItem pl = new MusicItem(plName, "folder", plName, "");
                    playlists.add(pl);
                } while (cursor.moveToNext());
            }
            cursor.close();
        } catch (Exception e) { e.printStackTrace(); }
        return playlists;
    }

    public List<MusicItem> getSongsInPlaylist(String playlistName) {
        List<MusicItem> songs = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        try {
            Cursor cursor = db.query(TABLE_SONGS, null, KEY_PLAYLIST + "=?",
                    new String[]{playlistName}, null, null, null);
            if (cursor.moveToFirst()) {
                do {
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_NAME));
                    String path = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FILE_PATH));
                    String artist = cursor.getString(cursor.getColumnIndexOrThrow(KEY_ARTIST));
                    MusicItem song = new MusicItem(name, "file", path, artist);
                    songs.add(song);
                } while (cursor.moveToNext());
            }
            cursor.close();
        } catch (Exception e) { e.printStackTrace(); }
        return songs;
    }

    public boolean isPlaylistDownloaded(String playlistName) {
        boolean exists = false;
        Cursor cursor = null;
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_SONGS + " WHERE " + KEY_PLAYLIST + " = ?", new String[]{playlistName});
            if (cursor != null && cursor.moveToFirst()) {
                exists = cursor.getInt(0) > 0;
            }
        } catch (Exception e) { e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }
        return exists;
    }

    public List<String> getPlaylistFilePaths(String playlistName) {
        List<String> paths = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT " + KEY_FILE_PATH + " FROM " + TABLE_SONGS + " WHERE " + KEY_PLAYLIST + " = ?", new String[]{playlistName});
            if (cursor.moveToFirst()) {
                do {
                    paths.add(cursor.getString(0));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }
        return paths;
    }

    public void deletePlaylist(String playlistName) {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.delete(TABLE_SONGS, KEY_PLAYLIST + " = ?", new String[]{playlistName});
            db.close();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public String getServerPathForLocalFile(String localPath) {
        SQLiteDatabase db = this.getReadableDatabase();
        String serverPath = null;
        try {
            Cursor cursor = db.query(TABLE_SONGS, new String[]{KEY_SERVER_PATH},
                    KEY_FILE_PATH + "=?", new String[]{localPath}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                serverPath = cursor.getString(0);
            }
            if (cursor != null) cursor.close();
        } catch (Exception e) {}
        return serverPath;
    }

    public void logPlay(String name, String artist, int durationSeconds) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_TIMESTAMP, System.currentTimeMillis() / 1000);
        values.put(KEY_NAME, name);
        values.put(KEY_ARTIST, artist);
        values.put(KEY_DURATION, durationSeconds);
        values.put(KEY_SYNCED, 0);
        db.insert(TABLE_HISTORY, null, values);
        db.close();
    }

    public List<JSONObject> getUnsyncedPlays() {
        List<JSONObject> list = new ArrayList<>();
        String selectQuery = "SELECT * FROM " + TABLE_HISTORY + " WHERE " + KEY_SYNCED + " = 0";
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);

        if (cursor.moveToFirst()) {
            do {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("id", cursor.getInt(0));
                    obj.put("timestamp", cursor.getLong(1));
                    obj.put("song", cursor.getString(2));
                    obj.put("artist", cursor.getString(3));
                    obj.put("duration", cursor.getInt(4));
                    list.add(obj);
                } catch (Exception e) {}
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public void markAsSynced(List<Integer> ids) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_SYNCED, 1);
        for (int id : ids) {
            db.update(TABLE_HISTORY, values, KEY_ID + " = ?", new String[]{String.valueOf(id)});
        }
        db.close();
    }

    public JSONObject getLocalStats(String period) {
        long now = System.currentTimeMillis() / 1000;
        long limit = 0;

        if (period.equals("week")) limit = now - (7 * 24 * 3600);
        else if (period.equals("month")) limit = now - (30 * 24 * 3600);
        else if (period.equals("year")) limit = now - (365 * 24 * 3600);

        SQLiteDatabase db = this.getReadableDatabase();
        JSONObject result = new JSONObject();

        try {
            String queryTime = "SELECT SUM(" + KEY_DURATION + ") FROM " + TABLE_HISTORY + " WHERE " + KEY_TIMESTAMP + " >= ?";
            Cursor cursorTime = db.rawQuery(queryTime, new String[]{String.valueOf(limit)});

            int totalSeconds = 0;
            if (cursorTime.moveToFirst()) {
                totalSeconds = cursorTime.getInt(0);
            }
            cursorTime.close();

            result.put("total_minutes", totalSeconds / 60);
            result.put("total_hours", totalSeconds / 3600.0);

            String queryTop = "SELECT " + KEY_NAME + ", COUNT(*) as plays FROM " + TABLE_HISTORY +
                    " WHERE " + KEY_TIMESTAMP + " >= ? " +
                    " GROUP BY " + KEY_NAME +
                    " ORDER BY plays DESC LIMIT 5";

            Cursor cursorTop = db.rawQuery(queryTop, new String[]{String.valueOf(limit)});
            JSONArray topSongs = new JSONArray();

            if (cursorTop.moveToFirst()) {
                do {
                    JSONObject song = new JSONObject();
                    song.put("name", cursorTop.getString(0));
                    song.put("plays", cursorTop.getInt(1));
                    topSongs.put(song);
                } while (cursorTop.moveToNext());
            }
            cursorTop.close();

            result.put("top_5", topSongs);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }
}
package com.example.resonode;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class OfflineDB extends SQLiteOpenHelper {

    
    private static final int DATABASE_VERSION = 2;
    private static final String DATABASE_NAME = "offline.db";
    private static final String TABLE_SONGS = "songs";

    
    private static final String KEY_ID = "id";
    private static final String KEY_SERVER_PATH = "server_path";
    private static final String KEY_NAME = "name";
    private static final String KEY_PLAYLIST = "playlist_name";
    private static final String KEY_FILE_PATH = "file_path";
    private static final String KEY_ARTIST = "artist";

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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SONGS);
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
        } catch (Exception e) {
            e.printStackTrace();
        }

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
        } catch (Exception e) {
            e.printStackTrace();
        }

        return songs;
    }

    

    
    public boolean isPlaylistDownloaded(String playlistName) {
        boolean exists = false;
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = this.getReadableDatabase();
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_SONGS + " WHERE " + KEY_PLAYLIST + " = ?", new String[]{playlistName});
            if (cursor != null && cursor.moveToFirst()) {
                exists = cursor.getInt(0) > 0;
            }
        } catch (Exception e) {
            e.printStackTrace();
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
        } catch (Exception e) {
            e.printStackTrace();
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
        } catch (Exception e) {
            e.printStackTrace();
        }
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
}
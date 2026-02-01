package com.example.resonode;

import java.io.Serializable;

public class MusicItem implements Serializable {
    private String name;
    private String type; 
    private String path;
    private String artist; 

    
    public MusicItem(String name, String type, String path, String artist) {
        this.name = name;
        this.type = type;
        this.path = path;
        this.artist = (artist == null) ? "" : artist;
    }

    
    public MusicItem(String name, String type, String path) {
        this(name, type, path, "");
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getPath() { return path; }
    public String getArtist() { return artist; } 

    public boolean isFolder() { return "folder".equals(type); }
}
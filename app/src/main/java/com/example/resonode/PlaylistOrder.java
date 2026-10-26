package com.example.resonode;

import org.json.JSONArray;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.text.Collator;

public final class PlaylistOrder {

    private PlaylistOrder() {}

    private static final String LOCAL_PREFIX = "local:";

    public static String keyOf(MusicItem item) {
        return item.getPath().startsWith("content://") ? LOCAL_PREFIX + item.getPath() : item.getName();
    }

    public static JSONArray toJson(List<MusicItem> items) {
        JSONArray a = new JSONArray();
        for (MusicItem m : items) if (!m.isFolder()) a.put(keyOf(m));
        return a;
    }

     public static void sort(List<MusicItem> items, JSONArray order) {
        if (order == null || order.length() == 0 || items.isEmpty()) return;
        final Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < order.length(); i++) {
            String k = order.optString(i, null);
            if (k != null && !pos.containsKey(k)) pos.put(k, i);
        }
        Collections.sort(items, (a, b) -> {
            if (a.isFolder() != b.isFolder()) return a.isFolder() ? -1 : 1;
            Integer pa = pos.get(keyOf(a));
            Integer pb = pos.get(keyOf(b));
            int ia = pa != null ? pa : Integer.MAX_VALUE;
            int ib = pb != null ? pb : Integer.MAX_VALUE;
            return Integer.compare(ia, ib);
        });
    }

    public static void sortFoldersByName(List<MusicItem> items) {
        if (items.size() < 2) return;
        final Collator collator = Collator.getInstance(); // usa el idioma de la app
        collator.setStrength(Collator.PRIMARY);           // "a" = "A" = "á"
        Collections.sort(items, (a, b) -> {
            if (a.isFolder() != b.isFolder()) return a.isFolder() ? -1 : 1;
            if (!a.isFolder()) return 0; // canciones: se quedan como estaban
            return collator.compare(a.getName(), b.getName());
        });
    }

    public static void sortNames(List<String> names) {
        if (names.size() < 2) return;
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(names, collator);
    }
}
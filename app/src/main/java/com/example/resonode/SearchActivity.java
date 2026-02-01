package com.example.resonode;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SearchActivity extends AppCompatActivity {

    private static final String SERVER_URL = Config.SERVER_URL;
    private static final String API_SECRET_KEY = Config.API_SECRET_KEY;

    private EditText etSearch;
    private ImageButton btnBack;
    private RecyclerView recyclerSearch;
    private ProgressBar progressBar;
    private PlaylistAdapter adapter;
    private List<MusicItem> searchResults = new ArrayList<>();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                Request original = chain.request();
                Request request = original.newBuilder()
                        .header("x-secret-key", API_SECRET_KEY)
                        .method(original.method(), original.body())
                        .build();
                return chain.proceed(request);
            }).build();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        etSearch = findViewById(R.id.et_search);
        btnBack = findViewById(R.id.btn_back);
        recyclerSearch = findViewById(R.id.recycler_search);
        progressBar = findViewById(R.id.progress_bar_search);

        recyclerSearch.setLayoutManager(new LinearLayoutManager(this));

        adapter = new PlaylistAdapter(this, searchResults, PlaylistAdapter.MODE_SEARCH,
                item -> openContext(item),
                (item, action) -> {
                    if (action.equals("Añadir a Playlist")) {
                        Intent resultIntent = new Intent();
                        resultIntent.putExtra("action", "add_to_playlist");
                        resultIntent.putExtra("path_id", item.getPath());
                        setResult(RESULT_OK, resultIntent);
                        finish();
                    }
                }
        );
        recyclerSearch.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());

        etSearch.addTextChangedListener(new TextWatcher() {
            private Runnable searchRunnable;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (searchRunnable != null) mainHandler.removeCallbacks(searchRunnable);
                searchRunnable = () -> performSearch(s.toString().trim());
                mainHandler.postDelayed(searchRunnable, 600);
            }
        });
    }

    private void performSearch(String query) {
        if (query.isEmpty()) return;

        progressBar.setVisibility(View.VISIBLE);
        JSONObject json = new JSONObject();
        try { json.put("query", query); } catch(Exception e){}

        executor.execute(() -> {
            try {
                RequestBody body = RequestBody.create(MediaType.parse("application/json"), json.toString());
                Request request = new Request.Builder().url(SERVER_URL + "/search").post(body).build();
                Response response = client.newCall(request).execute();

                if (response.isSuccessful()) {
                    JSONObject resJson = new JSONObject(response.body().string());
                    JSONArray results = resJson.getJSONArray("results");

                    List<MusicItem> temp = new ArrayList<>();
                    for(int i=0; i<results.length(); i++) {
                        JSONObject o = results.getJSONObject(i);
                        String type = o.getString("type");
                        String path = o.getString("path_id");
                        String title = o.getString("title");
                        String artist = o.has("artist") ? o.getString("artist") : "";

                        String displayName = title;
                        if(type.equals("song")) displayName += " - " + artist;
                        if(type.equals("album")) displayName += " (" + artist + ")";

                        
                        boolean isFolder = type.equals("album") || type.equals("artist");

                        temp.add(new MusicItem(displayName, isFolder ? "folder" : "file", path));
                    }

                    mainHandler.post(() -> {
                        searchResults.clear();
                        searchResults.addAll(temp);
                        adapter.notifyDataSetChanged();
                        progressBar.setVisibility(View.GONE);
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> progressBar.setVisibility(View.GONE));
            }
        });
    }

    private void openContext(MusicItem item) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("action", "navigate");

        if (item.isFolder()) {
            resultIntent.putExtra("path", item.getPath());
        } else {
            File f = new File(item.getPath());
            String parentPath = f.getParent();
            if (parentPath == null) parentPath = "";
            parentPath = parentPath.replace("\\", "/");

            resultIntent.putExtra("path", parentPath);
            resultIntent.putExtra("play_song", f.getName());
        }

        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
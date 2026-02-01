from fastapi import FastAPI, UploadFile, File, Form, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.middleware.cors import CORSMiddleware
import os
import shutil
import zipfile
import re
from mutagen.easyid3 import EasyID3
from mutagen.id3 import ID3, APIC
from typing import List
from pydantic import BaseModel
from urllib.parse import unquote

# Import secure configuration
try:
    import server_config
    API_SECRET_KEY = server_config.API_SECRET_KEY
except ImportError:
    API_SECRET_KEY = "default_unsafe_key"
    print("⚠️ WARNING: server_config.py not found. Using unsafe default key.")

app = FastAPI(title="SpotiFly Server")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Dynamic paths (relative to the script location)
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
VAULT_DIR = os.path.join(BASE_DIR, "MusicVault") 
GENERAL_DIR = os.path.join(BASE_DIR, "General")  
UPDATE_DIR = os.path.join(BASE_DIR, "updates")
USERS_DB_PATH = os.path.join(BASE_DIR, "users_db")

# Create necessary directories
for d in [VAULT_DIR, GENERAL_DIR, UPDATE_DIR, USERS_DB_PATH]:
    if not os.path.exists(d): os.makedirs(d)

# --- 🛡️ MIDDLEWARE ---
@app.middleware("http")
async def verify_secret_key(request: Request, call_next):
    allowed_paths = ["/", "/update/check", "/cover", "/docs", "/openapi.json"]
    if request.url.path in allowed_paths:
        return await call_next(request)
    
    client_key = request.headers.get("x-secret-key")
    if client_key != API_SECRET_KEY:
        return JSONResponse(status_code=403, content={"error": "⛔ Access Denied: Incorrect Key"})
    
    return await call_next(request)

# --- UTILITIES ---
def secure_path(path_str):
    if not path_str: return ""
    # Prevent Path Traversal
    clean = path_str.replace("..", "").replace("~", "").replace("\\", "/")
    return os.path.basename(clean) if os.path.isabs(clean) else clean

def natural_sort_key(s):
    return [int(text) if text.isdigit() else text.lower() for text in re.split('([0-9]+)', os.path.basename(s))]

def get_next_prefix(folder_path):
    if not os.path.exists(folder_path): return "0001"
    files = os.listdir(folder_path)
    max_num = 0
    for f in files:
        try:
            prefix = f.split("_")[0]
            if prefix.isdigit() and len(prefix) == 4:
                num = int(prefix)
                if num > max_num: max_num = num
        except: pass
    return f"{max_num + 1:04d}"

def organize_file(temp_path):
    try:
        audio = EasyID3(temp_path)
        artist = audio.get('artist', ['Unknown'])[0].replace("/", "-")
        album = audio.get('album', ['Singles'])[0].replace("/", "-")
        title = audio.get('title', [os.path.basename(temp_path)])[0].replace("/", "-")
        track = audio.get('tracknumber', ['0'])[0].split('/')[0]
        
        filename = f"{int(track):02d} - {title}.mp3"
        save_dir = os.path.join(VAULT_DIR, artist, album)
        
        if not os.path.exists(save_dir): os.makedirs(save_dir)
        
        final_path = os.path.join(save_dir, filename)

        # Extract Cover Art
        cover_path = os.path.join(save_dir, "cover.jpg")
        if not os.path.exists(cover_path):
            try:
                tags = ID3(temp_path)
                for tag in tags.values():
                    if isinstance(tag, APIC):
                        with open(cover_path, 'wb') as img:
                            img.write(tag.data)
                        break
            except: pass
        
        if os.path.exists(final_path):
            os.remove(temp_path); return final_path
        shutil.move(temp_path, final_path)
        return final_path
    except Exception as e:
        fallback = os.path.join(VAULT_DIR, "Unknown", "Unknown")
        if not os.path.exists(fallback): os.makedirs(fallback)
        final = os.path.join(fallback, os.path.basename(temp_path))
        if not os.path.exists(final): shutil.move(temp_path, final)
        return final

# --- MODELS ---
class PlaylistCreate(BaseModel):
    username: str; playlist_name: str
class AddSongs(BaseModel):
    username: str; playlist_name: str; songs: List[str]
class RenamePlaylist(BaseModel):
    username: str; old_name: str; new_name: str
class DeleteItem(BaseModel):
    username: str; path: str 
class SearchQuery(BaseModel):
    query: str
class AddFromVault(BaseModel):
    username: str; playlist_name: str; items: List[str]

# --- ENDPOINTS ---

@app.post("/auth/register")
def register(username: str = Form(...), password: str = Form(...)):
    safe_user = secure_path(username)
    if not safe_user or not password: return JSONResponse({"error": "Missing data"}, 400)
    user_music_path = os.path.join(BASE_DIR, safe_user)
    
    # Security: Only allow registration if the folder already exists physically
    if not os.path.exists(user_music_path) or not os.path.isdir(user_music_path):
        return JSONResponse({"error": "⛔ ACCESS DENIED: Contact administrator to create your user folder."}, 403)
        
    user_file = os.path.join(USERS_DB_PATH, f"{safe_user}.txt")
    if os.path.exists(user_file): return JSONResponse({"error": "User already exists"}, 409)
    
    with open(user_file, 'w') as f: f.write(password)
    return {"message": "Registered successfully"}

@app.post("/auth/login")
def login(username: str = Form(...), password: str = Form(...)):
    safe_user = secure_path(username)
    user_file = os.path.join(USERS_DB_PATH, f"{safe_user}.txt")
    if not os.path.exists(user_file): return JSONResponse({"error": "User not registered"}, 404)
    
    with open(user_file, 'r') as f: stored = f.read().strip()
    if stored == password: return {"message": "Login successful"}
    return JSONResponse({"error": "Incorrect password"}, 401)

@app.get("/browse")
def browse(username: str, folder: str = ""):
    safe_user = secure_path(username)
    folder = folder.replace("\\", "/") 
    if ".." in folder: return JSONResponse({"error": "Invalid path"}, 403)
    
    target = ""
    is_vault = False
    
    # Path logic
    if folder == "General" or folder.startswith("General/"): 
        target = os.path.join(BASE_DIR, folder)
    else:
        user_target = os.path.join(BASE_DIR, safe_user, folder)
        vault_target = os.path.join(VAULT_DIR, folder)
        if os.path.exists(user_target): target = user_target
        elif os.path.exists(vault_target): target = vault_target; is_vault = True
        else: return JSONResponse({"error": "Not found"}, 404)
    
    items = []
    try:
        for name in os.listdir(target):
            full_path = os.path.join(target, name)
            rel_path = name if folder == "" else os.path.join(folder, name)
            rel_path = rel_path.replace("\\", "/")
            
            if os.path.isdir(full_path):
                items.append({"name": name, "type": "folder", "path": rel_path})
            elif name.lower().endswith(".mp3"):
                artist_tag = "SpotiFly"
                # Try to deduce artist from path
                if "MusicVault" in full_path:
                    parts = full_path.split(os.sep)
                    try: 
                        idx = parts.index("MusicVault")
                        if idx + 1 < len(parts): artist_tag = parts[idx+1]
                    except: pass
                items.append({"name": name, "type": "file", "path": rel_path, "artist": artist_tag})
        
        items.sort(key=lambda x: (x["type"] != "folder", x["name"].lower()))
        return {"current_path": folder, "items": items, "is_vault": is_vault}
    except Exception as e: return JSONResponse({"error": str(e)}, 500)

@app.get("/stream")
def stream(username: str, path: str):
    if ".." in path: return JSONResponse({"error": "Forbidden"}, 403)
    safe_user = secure_path(username)
    
    paths_to_try = [
        os.path.join(BASE_DIR, path) if path.startswith("General") else None,
        os.path.join(BASE_DIR, safe_user, path),
        os.path.join(VAULT_DIR, path)
    ]
    for p in paths_to_try:
        if p and os.path.isfile(p): return FileResponse(p, media_type="audio/mpeg")
    return JSONResponse({"error": "404 Not Found"}, 404)

@app.post("/upload_zip")
async def upload_zip(file: UploadFile = File(...), target_playlist: str = Form(...)):
    temp_zip = "temp_upload.zip"
    extract_dir = "temp_extract_folder"
    if os.path.exists(extract_dir): shutil.rmtree(extract_dir)
    
    with open(temp_zip, "wb") as b: shutil.copyfileobj(file.file, b)
    with zipfile.ZipFile(temp_zip, 'r') as z: z.extractall(extract_dir)
    
    processed = 0
    # Search for mp3s recursively
    for root, _, files in os.walk(extract_dir):
        files.sort(key=natural_sort_key)
        for name in files:
            if name.lower().endswith(".mp3"):
                src = os.path.join(root, name)
                # 1. Organize into Vault (Artist/Album/01-Song.mp3)
                vault_path = organize_file(src)
                
                # 2. If target playlist provided, create symbolic link
                if target_playlist and target_playlist not in ["null", ""]:
                    safe_pl = secure_path(target_playlist)
                    if safe_pl.startswith("General"): dest = os.path.join(BASE_DIR, safe_pl)
                    else: dest = os.path.join(BASE_DIR, secure_path("Alemany"), safe_pl) # NOTE: Adjust 'Alemany' to dynamic user if needed in future
                    
                    if os.path.exists(dest):
                        prefix = get_next_prefix(dest)
                        link_name = os.path.join(dest, f"{prefix}_{os.path.basename(vault_path)}")
                        if not os.path.exists(link_name): 
                            os.symlink(vault_path, link_name)
                            processed += 1
                else:
                    processed += 1 

    # Cleanup
    if os.path.exists(temp_zip): os.remove(temp_zip)
    if os.path.exists(extract_dir): shutil.rmtree(extract_dir)
    return {"status": "ok", "processed": processed}

# --- OTHER NECESSARY ENDPOINTS ---
@app.get("/update/check")
def check_update():
    v_file = os.path.join(UPDATE_DIR, "version.json")
    if os.path.exists(v_file): return FileResponse(v_file)
    return {"version": 0}

@app.get("/update/download")
def download_update():
    if not os.path.exists(UPDATE_DIR): return JSONResponse({}, 404)
    apks = [f for f in os.listdir(UPDATE_DIR) if f.endswith(".apk")]
    if apks: return FileResponse(os.path.join(UPDATE_DIR, apks[0]), media_type='application/vnd.android.package-archive')
    return JSONResponse({}, 404)

@app.get("/cover")
def get_cover(username: str, path: str):
    path = unquote(path)
    if ".." in path: return JSONResponse({}, 403)
    
    possible_roots = [VAULT_DIR, os.path.join(BASE_DIR, secure_path(username))]
    target_file = None
    
    for root in possible_roots:
        check = os.path.join(root, path)
        if os.path.exists(check): target_file = check; break
        
    if not target_file: return JSONResponse({}, 404)
    
    if os.path.isfile(target_file) and target_file.endswith(".mp3"):
         try:
            tags = ID3(target_file)
            for tag in tags.values():
                if isinstance(tag, APIC): return Response(content=tag.data, media_type="image/jpeg")
         except: pass
         target_file = os.path.dirname(target_file)
    
    if os.path.isdir(target_file):
        for img in ["cover.jpg", "folder.jpg"]:
            i_path = os.path.join(target_file, img)
            if os.path.exists(i_path): return FileResponse(i_path)
            
    return JSONResponse({}, 404)
    
@app.post("/system/upload_update")
async def upload_update_remoto(apk_file: UploadFile = File(...), json_file: UploadFile = File(...)):
    try:
        # Clean old updates
        if os.path.exists(UPDATE_DIR):
            for f in os.listdir(UPDATE_DIR):
                os.remove(os.path.join(UPDATE_DIR, f))
        else:
            os.makedirs(UPDATE_DIR)
            
        # Save APK
        apk_path = os.path.join(UPDATE_DIR, apk_file.filename)
        with open(apk_path, "wb") as buffer:
            shutil.copyfileobj(apk_file.file, buffer)
            
        # Save Version JSON
        json_path = os.path.join(UPDATE_DIR, "version.json")
        with open(json_path, "wb") as buffer:
            shutil.copyfileobj(json_file.file, buffer)
            
        return {"status": "ok", "msg": "Update deployed successfully"}
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})

if __name__ == "__main__":
    import uvicorn
    # For testing only, production usually uses server_launcher.py
    uvicorn.run(app, host="0.0.0.0", port=8000)

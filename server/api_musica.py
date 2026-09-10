from fastapi import FastAPI, UploadFile, File, Form, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, Response, StreamingResponse, HTMLResponse
from fastapi.middleware.cors import CORSMiddleware
import os
import shutil
import zipfile
import re
from mutagen.easyid3 import EasyID3
from mutagen.id3 import ID3, APIC
from typing import List
from pydantic import BaseModel
from urllib.parse import unquote, quote
from typing import Optional
import time
from datetime import datetime, timedelta
import subprocess
import asyncio
import uuid
import json

# --- ⚙️ SERVER CONFIGURATION ---
try:
    import server_config
    API_SECRET_KEY = server_config.API_SECRET_KEY
    ADMIN_SECRET_KEY = getattr(server_config, 'ADMIN_SECRET_KEY', "admin_default")
    SERVER_PORT = getattr(server_config, 'PORT', 5000)
    print("✅ Configuration loaded from server_config.py")
except ImportError:
    print("⚠️ WARNING: server_config.py not found. Using default keys (UNSAFE).")
    API_SECRET_KEY = "example_api_key"
    ADMIN_SECRET_KEY = "admin_default"
    SERVER_PORT = 5000

app = FastAPI()

@app.on_event("startup")
async def startup_event():
    async def run_updater():
        # Wait 5 seconds to allow Cloudflare to generate the new URL
        await asyncio.sleep(5) 
        
        try:
            # Get the ABSOLUTE path of the current folder using __file__
            current_dir = os.path.dirname(os.path.abspath(__file__))
            script_path = os.path.join(current_dir, "update_url.py")
            
            if os.path.exists(script_path):
                # Launch it in the background so it doesn't freeze the server
                subprocess.Popen(["python3", script_path])
                print("🚀 [INFO] update_url.py executed to update the GitHub Gist.")
            else:
                print(f"⚠️ [WARNING] File not found: {script_path}")
        except Exception as e:
            print(f"❌ [ERROR] Failed to execute update_url.py: {e}")

    # Launch the background task
    asyncio.create_task(run_updater())

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

BASE_DIR = os.path.abspath(".")
VAULT_DIR = os.path.join(BASE_DIR, "MusicVault") 
GENERAL_DIR = os.path.join(BASE_DIR, "General")  
UPDATE_DIR = os.path.join(BASE_DIR, "updates")
USERS_DB_PATH = os.path.join(BASE_DIR, "users_db")

# Create necessary folders
for d in [VAULT_DIR, GENERAL_DIR, UPDATE_DIR, USERS_DB_PATH]:
    if not os.path.exists(d): os.makedirs(d)

SHARES_DB_PATH = os.path.join(BASE_DIR, "shares_db.json")

def load_shares():
    if not os.path.exists(SHARES_DB_PATH): return {}
    with open(SHARES_DB_PATH, "r") as f: return json.load(f)

def save_shares(shares):
    with open(SHARES_DB_PATH, "w") as f: json.dump(shares, f)

# --- 🛡️ MIDDLEWARE ---

@app.middleware("http")
async def verify_secret_key(request: Request, call_next):
    # Public routes
    allowed_paths = ["/", "/update/check", "/cover", "/share/link", "/share/resolve"]
    
    if request.url.path in allowed_paths:
        return await call_next(request)
    
    # Key verification
    client_key = request.headers.get("x-secret-key")
    if client_key != API_SECRET_KEY:
        return JSONResponse(status_code=403, content={"error": "⛔ Access Denied: Incorrect Key"})
    
    response = await call_next(request)
    return response


# --- UTILITIES ---

def secure_path(path_str):
    if not path_str: return ""
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
        
        # Prioritize the filename cleaned by the client
        base_name = os.path.basename(temp_path)
        match = re.match(r"^(\d+)\s*-\s*(.*)\.mp3$", base_name, re.IGNORECASE)
        
        if match:
            # If the client named it "01 - Title.mp3", we trust this.
            track = match.group(1)
            title = match.group(2).replace("/", "-")
        else:
            # Fallback to ID3 metadata if the name has no format
            title = audio.get('title', [base_name])[0].replace("/", "-")
            track = audio.get('tracknumber', ['0'])[0].split('/')[0]
            
        filename = f"{int(track):02d} - {title}.mp3"
        save_dir = os.path.join(VAULT_DIR, artist, album)
        
        if not os.path.exists(save_dir): os.makedirs(save_dir)
        
        final_path = os.path.join(save_dir, filename)

        # --- EXTRACT COVER ---
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

class SyncStats(BaseModel):
    username: str
    plays: List[dict] # List of {timestamp, duration, song, artist}

class WrappedConfig(BaseModel):
    username: str
    enabled: bool
    is_public: bool

class ShareCreate(BaseModel):
    username: str
    path: str

class ShareImport(BaseModel):
    token: str
    target_username: str


# --- AUTHENTICATION ENDPOINTS ---

@app.post("/auth/register")
def register(username: str = Form(...), password: str = Form(...)):
    # 1. Clean name
    safe_user = secure_path(username)
    if not safe_user or not password:
        return JSONResponse({"error": "Missing data"}, 400)

    # 2. 🔥 SECURITY: Verify that the user's folder ALREADY EXISTS 🔥
    # If there is no folder, we do not allow registering a password.
    user_music_path = os.path.join(BASE_DIR, safe_user)
    if not os.path.exists(user_music_path) or not os.path.isdir(user_music_path):
        return JSONResponse({"error": "⛔ ACCESS DENIED: You do not have an assigned folder on the server."}, 403)

    # 3. Check if a password is already assigned
    user_file = os.path.join(USERS_DB_PATH, f"{safe_user}.txt")
    if os.path.exists(user_file):
        return JSONResponse({"error": "User is already registered"}, 409)

    # 4. Save password
    try:
        with open(user_file, 'w') as f:
            f.write(password)
            
        return {"message": "Registration successful"}
    except Exception as e:
        return JSONResponse({"error": str(e)}, 500)

@app.post("/auth/login")
def login(username: str = Form(...), password: str = Form(...), device_model: Optional[str] = Form(None)):
    safe_user = secure_path(username)
    user_file = os.path.join(USERS_DB_PATH, f"{safe_user}.txt")
    
    # If password file does not exist, user is not registered
    if not os.path.exists(user_file):
        return JSONResponse({"error": "User not registered"}, 404)

    try:
        # Read all lines
        with open(user_file, 'r') as f:
            lines = f.read().splitlines()
        
        if not lines:
            return JSONResponse({"error": "Corrupted file"}, 500)

        # The 1st line is ALWAYS the password
        stored_password = lines[0].strip()
        
        # --- COMPARISON DONE HERE ---
        if stored_password == password:
            
            # If everything is correct and a model was sent, save it
            if device_model:
                device_entry = f"Device: {device_model}"
                # Check if it already exists to avoid duplicates
                if device_entry not in lines:
                    with open(user_file, "a") as f:
                        f.write(f"\n{device_entry}")
                        
            return {"message": "Login successful"}
        else:
            return JSONResponse({"error": "Incorrect password"}, 401)
    except Exception as e:
        return JSONResponse({"error": str(e)}, 500)

@app.get("/auth/check_user")
def check_user(username: str):
    safe_user = secure_path(username)
    blacklist = ["MusicVault", "__pycache__", "updates", "api_musica.py", "General", "temp_extract", "venv", "env", ".git", ".config", "nohup.out", "users_db"]
    if safe_user in blacklist: return {"exists": False}
    
    full_path = os.path.join(BASE_DIR, safe_user)
    # We only say it "exists" if it has a physical folder
    exists = os.path.exists(full_path) and os.path.isdir(full_path)
    return {"exists": exists}

# --- OTHER ENDPOINTS ---

@app.get("/")
def home(): return {"status": "Vault Online - Secured"}

@app.get("/system/folders")
def get_root_folders():
    ignored = ["api_musica.py", "__pycache__", "MusicVault", "temp.zip", "temp_extract", ".config", ".git", "venv", "env", "nohup.out", "updates", "users_db"]
    folders = []
    try:
        if not os.path.exists(BASE_DIR): return {"folders": []}
        for name in os.listdir(BASE_DIR):
            full_path = os.path.join(BASE_DIR, name)
            if name not in ignored and os.path.isdir(full_path): folders.append(name)
        return {"folders": sorted(folders)}
    except Exception as e: return {"error": str(e)}

@app.post("/upload_zip")
async def upload_zip(request: Request, file: UploadFile = File(...), target_playlist: str = Form(...)):
    admin_key = request.headers.get("x-admin-key")
    if admin_key != ADMIN_SECRET_KEY:
        return JSONResponse(status_code=403, content={"error": "Only the admin can upload new music."})
    temp_zip = "temp.zip"; temp_extract = "temp_extract"

    with open(temp_zip, "wb") as buffer: shutil.copyfileobj(file.file, buffer)

    if os.path.exists(temp_extract): shutil.rmtree(temp_extract)

    with zipfile.ZipFile(temp_zip, 'r') as zip_ref: zip_ref.extractall(temp_extract)

    extracted_files = []

    for root, dirs, files in os.walk(temp_extract):
        for name in files:
            if name.lower().endswith(".mp3"): extracted_files.append(os.path.join(root, name))

    extracted_files.sort(key=natural_sort_key); processed_count = 0

    for full_path in extracted_files:
        vault_path = organize_file(full_path)
        if target_playlist and target_playlist != "null" and target_playlist != "":
            safe_target = secure_path(target_playlist)
            if safe_target.startswith("General"): dest_dir = os.path.join(BASE_DIR, safe_target)
            else: dest_dir = os.path.join(BASE_DIR, safe_target)
            if not os.path.exists(dest_dir):
                parent_dir = os.path.dirname(dest_dir)
                if not os.path.exists(parent_dir) and parent_dir != BASE_DIR: continue 
                os.makedirs(dest_dir)
            prefix = get_next_prefix(dest_dir); final_filename = os.path.basename(vault_path)
            dst = os.path.join(dest_dir, f"{prefix}_{final_filename}")
            if not os.path.exists(dst): os.symlink(vault_path, dst); processed_count += 1

    os.remove(temp_zip)
    if os.path.exists(temp_extract): shutil.rmtree(temp_extract)
    return {"status": "ok", "processed": processed_count}

@app.post("/search")
def search_vault(data: SearchQuery):
    query = data.query.lower(); results = []
    if not os.path.exists(VAULT_DIR): return {"results": []}
    
    for artist in os.listdir(VAULT_DIR):
        artist_path = os.path.join(VAULT_DIR, artist)
        if not os.path.isdir(artist_path): continue
        
        if query in artist.lower():
            results.append({"type": "artist", "title": artist, "artist": "Artist", "path_id": artist})

        for album in os.listdir(artist_path):
            album_path = os.path.join(artist_path, album)
            if not os.path.isdir(album_path): continue
            
            is_match = query in album.lower()
            files = [f for f in os.listdir(album_path) if f.endswith(".mp3")]
            
            if is_match: 
                results.append({"type": "album", "title": album, "artist": artist, "count": len(files), "path_id": os.path.join(artist, album)})
            
            for f in files:
                if query in f.lower() and not is_match: 
                    results.append({"type": "song", "title": f, "artist": artist, "album": album, "path_id": os.path.join(artist, album, f)})
                    
    return {"results": results}

@app.post("/playlist/add_from_vault")
def add_from_vault(data: AddFromVault):
    safe_user = secure_path(data.username); safe_pl = secure_path(data.playlist_name)
    if safe_pl == "General" or safe_pl.startswith("General/"): dest_dir = os.path.join(BASE_DIR, safe_pl)
    else: dest_dir = os.path.join(BASE_DIR, safe_user, safe_pl)
    if not os.path.exists(dest_dir): os.makedirs(dest_dir)
    count = 0
    for item_path in data.items:
        if ".." in item_path: continue
        
        # 🔥 SMART PATH (Searches where it belongs) 🔥
        full_src = ""
        if item_path.startswith("General"): full_src = os.path.join(BASE_DIR, item_path)
        elif os.path.exists(os.path.join(BASE_DIR, safe_user, item_path)): full_src = os.path.join(BASE_DIR, safe_user, item_path)
        else: full_src = os.path.join(VAULT_DIR, item_path)

        if os.path.isdir(full_src):
            files = [f for f in os.listdir(full_src) if f.endswith(".mp3")]
            files.sort(key=natural_sort_key)
            for f in files:
                src = os.path.join(full_src, f)
                dst = os.path.join(dest_dir, f"{get_next_prefix(dest_dir)}_{f}")
                if not os.path.exists(dst): 
                    try: os.symlink(src, dst); count += 1
                    except: pass
        else:
            if os.path.exists(full_src):
                dst = os.path.join(dest_dir, f"{get_next_prefix(dest_dir)}_{os.path.basename(full_src)}")
                if not os.path.exists(dst): 
                    try: os.symlink(full_src, dst); count += 1
                    except: pass
    return {"success": True, "added": count}

@app.get("/browse")
def browse(username: str, folder: str = ""):
    safe_user = secure_path(username)
    folder = folder.replace("\\", "/") 
    if ".." in folder: return JSONResponse({"error": "Invalid path"}, 403)
    
    is_vault = False
    target = ""
    current_rel = folder

    if folder == "General" or folder.startswith("General/"): 
        target = os.path.join(BASE_DIR, folder)
    else:
        user_target = os.path.join(BASE_DIR, safe_user, folder)
        vault_target = os.path.join(VAULT_DIR, folder)

        if os.path.exists(user_target):
            target = user_target
        elif os.path.exists(vault_target):
            target = vault_target
            is_vault = True
        else:
            return JSONResponse({"error": "Does not exist"}, 404)
    
    items = []
    try:
        for name in os.listdir(target):
            path_rel = name if current_rel == "" else os.path.join(current_rel, name)
            path_rel = path_rel.replace("\\", "/") 
            full_path = os.path.join(target, name)
            
            if os.path.isdir(full_path):
                items.append({"name": name, "type": "folder", "path": path_rel, "artist": ""})
            
            elif name.lower().endswith(".mp3"):
                detected_artist = "ResoNode"
                try:
                    real_path = os.path.realpath(full_path)
                    if "MusicVault" in real_path:
                        parts = real_path.split(os.sep)
                        if "MusicVault" in parts:
                            idx = parts.index("MusicVault")
                            if idx + 1 < len(parts):
                                detected_artist = parts[idx + 1] 
                except:
                    pass
                items.append({"name": name, "type": "file", "path": path_rel, "artist": detected_artist})

        items.sort(key=lambda x: (x["type"] != "folder", x["name"].lower()))
        return {"current_path": current_rel, "items": items, "is_vault": is_vault}
    except Exception as e: return JSONResponse({"error": str(e)}, 500)

@app.post("/playlist/create")
def create_pl(data: PlaylistCreate):
    safe_user = secure_path(data.username); safe_pl = secure_path(data.playlist_name)
    path = os.path.join(BASE_DIR, safe_user, safe_pl)
    if os.path.exists(path): return {"error": "Already exists"}
    os.makedirs(path); return {"success": True}

@app.post("/playlist/add")
def add_s(data: AddSongs):
    safe_user = secure_path(data.username); safe_pl = secure_path(data.playlist_name)
    pl_path = os.path.join(BASE_DIR, safe_user, safe_pl)
    if not os.path.exists(pl_path): os.makedirs(pl_path) # Create for prevention
    c = 0
    def link_file(src_path):
        nonlocal c
        if os.path.isfile(src_path) and src_path.lower().endswith(".mp3"):
            prefix = get_next_prefix(pl_path); dst = os.path.join(pl_path, f"{prefix}_{os.path.basename(src_path)}")
            if not os.path.exists(dst): 
                try: os.symlink(src_path, dst); c += 1
                except: pass
    for s in data.songs:
        if ".." in s: continue
        
        # 🔥 SMART PATH (Searches where it belongs) 🔥
        full_src = ""
        if s.startswith("General"): full_src = os.path.join(BASE_DIR, s)
        elif os.path.exists(os.path.join(BASE_DIR, safe_user, s)): full_src = os.path.join(BASE_DIR, safe_user, s)
        else: full_src = os.path.join(VAULT_DIR, s)

        if os.path.isdir(full_src):
            for root, dirs, files in os.walk(full_src):
                files.sort(key=natural_sort_key)
                for file in files: link_file(os.path.join(root, file))
        else: link_file(full_src)
    return {"success": True, "added": c}

# --- 🔥 HERE WE APPLY THE STREAMING CHUNKS IMPROVEMENT 🔥 ---
@app.get("/stream")
def stream(request: Request, username: str, path: str):
    if ".." in path: return JSONResponse({"error": "Hack detected"}, 403)
    safe_user = secure_path(username)
    
    p_general = os.path.join(BASE_DIR, path)
    p_user = os.path.join(BASE_DIR, safe_user, path)
    p_vault = os.path.join(VAULT_DIR, path)

    # Find the correct file following your original logic
    file_path = None
    if path.startswith("General") and os.path.isfile(p_general):
        file_path = p_general
    elif os.path.isfile(p_user):
        file_path = p_user
    elif os.path.isfile(p_vault):
        file_path = p_vault

    if not file_path:
        return JSONResponse({"error": "404"}, 404)

    file_size = os.path.getsize(file_path)
    CHUNK_SIZE = 1024 * 256  # 256 KB per chunk

    range_header = request.headers.get("Range")

    def iter_file(start: int, end: int):
        with open(file_path, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                chunk = f.read(min(CHUNK_SIZE, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk

    if range_header:
        # Parse "Range: bytes=start-end" or "bytes=start-"
        match = re.match(r"bytes=(\d+)-(\d*)", range_header)
        if match:
            start = int(match.group(1))
            end = int(match.group(2)) if match.group(2) else file_size - 1
            end = min(end, file_size - 1)

            if start > end or start >= file_size:
                return Response(
                    status_code=416,
                    headers={"Content-Range": f"bytes */{file_size}"}
                )

            content_length = end - start + 1
            return StreamingResponse(
                iter_file(start, end),
                status_code=206,
                media_type="audio/mpeg",
                headers={
                    "Content-Range": f"bytes {start}-{end}/{file_size}",
                    "Accept-Ranges": "bytes",
                    "Content-Length": str(content_length),
                    "Cache-Control": "no-cache",
                }
            )

    # Without Range: send the complete file also in streaming
    return StreamingResponse(
        iter_file(0, file_size - 1),
        status_code=200,
        media_type="audio/mpeg",
        headers={
            "Accept-Ranges": "bytes",
            "Content-Length": str(file_size),
            "Cache-Control": "no-cache",
        }
    )
# --------------------------------------------------------

@app.post("/playlist/delete_item")
def delete_item(data: DeleteItem):
    safe_user = secure_path(data.username)
    if ".." in data.path: return {"error": "Hack detected"}
    blacklist = ["MusicVault", "General", "updates", "users_db", "__pycache__"]
    if safe_user in blacklist:
        return {"error": "Hack Detected: You do not have permission to touch system folders."}

    target = os.path.join(BASE_DIR, safe_user, data.path)

    if "General" in data.path and ".." in data.path: return {"error": "Not allowed"}

    try:
        if os.path.isdir(target): shutil.rmtree(target)
        elif os.path.isfile(target): os.remove(target)
        else: return {"error": "Not found"}
        return {"success": True}
    except Exception as e: return {"error": str(e)}

@app.post("/playlist/rename")
def rename_playlist(data: RenamePlaylist):
    safe_user = secure_path(data.username); safe_old = secure_path(data.old_name); safe_new = secure_path(data.new_name)
    old_p = os.path.join(BASE_DIR, safe_user, safe_old); new_p = os.path.join(BASE_DIR, safe_user, safe_new)
    try: os.rename(old_p, new_p); return {"success": True}
    except Exception as e: return {"error": str(e)}

@app.get("/update/check")
def check_update():
    json_path = os.path.join(UPDATE_DIR, "version.json")
    if os.path.exists(json_path): return FileResponse(json_path)
    return {"version": 0}

@app.get("/update/download")
def download_update():
    if not os.path.exists(UPDATE_DIR): return JSONResponse({"error": "No updates folder"}, 404)
    files = [f for f in os.listdir(UPDATE_DIR) if f.endswith(".apk")]
    if files: return FileResponse(os.path.join(UPDATE_DIR, files[0]), media_type='application/vnd.android.package-archive', filename="spotifly_update.apk")
    return JSONResponse({"error": "No .apk found"}, 404)

@app.post("/system/upload_update")
async def upload_update_remoto(request: Request, apk_file: UploadFile = File(...), json_file: UploadFile = File(...)):
    admin_key = request.headers.get("x-admin-key")
    if admin_key != ADMIN_SECRET_KEY:
        return JSONResponse(status_code=403, content={"error": "Hack Detected: Only the administrator can upload APKs."})
    try:
        if os.path.exists(UPDATE_DIR):
            for f in os.listdir(UPDATE_DIR): os.remove(os.path.join(UPDATE_DIR, f))
        else: os.makedirs(UPDATE_DIR)
        apk_path = os.path.join(UPDATE_DIR, apk_file.filename)
        with open(apk_path, "wb") as buffer: shutil.copyfileobj(apk_file.file, buffer)
        json_path = os.path.join(UPDATE_DIR, "version.json")
        with open(json_path, "wb") as buffer: shutil.copyfileobj(json_file.file, buffer)
        return {"status": "ok", "msg": "Update deployed successfully"}
    except Exception as e: return JSONResponse(status_code=500, content={"error": str(e)})

@app.get("/cover")
def get_cover(username: str, path: str):
    path = unquote(path).lstrip('/')
    if ".." in path: return JSONResponse({"error": "Hack detected"}, 403)
    safe_user = secure_path(username)
    if path.startswith("General"): target = os.path.join(BASE_DIR, path)
    elif os.path.exists(os.path.join(BASE_DIR, safe_user, path)): target = os.path.join(BASE_DIR, safe_user, path)
    else: target = os.path.join(VAULT_DIR, path)

    if os.path.isfile(target) and target.lower().endswith(".mp3"):
        try:
            tags = ID3(target)
            for tag in tags.values():
                if isinstance(tag, APIC): return Response(content=tag.data, media_type="image/jpeg")
        except: pass
        target = os.path.dirname(target)
    elif os.path.isfile(target):
        target = os.path.dirname(target)

    for img_name in ["cover.jpg", "cover.png", "folder.jpg", "folder.png", "artwork.jpg"]:
        img_path = os.path.join(target, img_name)
        if os.path.exists(img_path): return FileResponse(img_path)

    if os.path.isdir(target):
        for f in os.listdir(target):
            if f.lower().endswith(".mp3"):
                try:
                    tags = ID3(os.path.join(target, f))
                    for tag in tags.values():
                        if isinstance(tag, APIC): return Response(content=tag.data, media_type="image/jpeg")
                except: continue
    return JSONResponse({"error": "No cover"}, 404)

@app.post("/playlist/upload_cover")
async def upload_playlist_cover(file: UploadFile = File(...), username: str = Form(...), playlist_path: str = Form(...)):
    safe_user = secure_path(username)
    if playlist_path.startswith("General"): dest_dir = os.path.join(BASE_DIR, playlist_path)
    else: dest_dir = os.path.join(BASE_DIR, safe_user, playlist_path)
    if not os.path.exists(dest_dir): return JSONResponse({"error": "Playlist does not exist"}, 404)
    save_path = os.path.join(dest_dir, "cover.jpg")
    with open(save_path, "wb") as buffer: shutil.copyfileobj(file.file, buffer)
    return {"status": "ok", "msg": "Cover updated"}

@app.post("/auth/get_devices")
def get_devices(username: str = Form(...)):
    safe_user = secure_path(username)
    user_file = os.path.join(USERS_DB_PATH, f"{safe_user}.txt")
    
    if not os.path.exists(user_file):
        return JSONResponse({"devices": []})

    try:
        with open(user_file, 'r') as f:
            lines = f.read().splitlines()
        
        # The first line is the password, skip it
        devices = []
        if len(lines) > 1:
            for line in lines[1:]:
                if line.startswith("Device: "):
                    # Remove the "Device: " prefix to send only the name
                    devices.append(line.replace("Device: ", "").strip())
                    
        return {"devices": devices}
    except Exception as e:
        return JSONResponse({"error": str(e)}, 500)
        
@app.post("/stats/config")
def update_stats_config(data: WrappedConfig):
    safe_user = secure_path(data.username)
    user_file = os.path.join(USERS_DB_PATH, f"{safe_user}.txt")
    if not os.path.exists(user_file): return JSONResponse({"error": "User not found"}, 404)

    try:
        with open(user_file, 'r') as f:
            lines = f.read().splitlines()
        
        # Filter old configurations
        new_lines = [l for l in lines if not l.startswith("Wrapped:")]
        
        if data.enabled:
            mode = "PUBLIC" if data.is_public else "PRIVATE"
            new_lines.append(f"Wrapped: {mode}")
        else:
            new_lines.append("Wrapped: DISABLED")
            # Optional: If disabled, we could delete the logs, 
            # but for now we only mark it to stop logging.

        with open(user_file, 'w') as f:
            f.write("\n".join(new_lines))
            
        return {"status": "updated"}
    except Exception as e: return JSONResponse({"error": str(e)}, 500)

@app.post("/stats/sync")
def sync_stats(data: SyncStats):
    safe_user = secure_path(data.username)
    user_file = os.path.join(USERS_DB_PATH, f"{safe_user}.txt")
    
    # First check if Wrapped is enabled
    is_enabled = False
    if os.path.exists(user_file):
        with open(user_file, 'r') as f:
            if "Wrapped: DISABLED" not in f.read():
                is_enabled = True # Default or if it's Public/Private
    
    if not is_enabled:
        return {"status": "ignored", "msg": "Wrapped is disabled"}

    try:
        with open(user_file, "a") as f:
            for p in data.plays:
                # Format: LOG|timestamp|seconds|SongName|Artist
                line = f"LOG|{p['timestamp']}|{p['duration']}|{p['song']}|{p['artist']}"
                f.write(f"\n{line}")
        return {"status": "synced"}
    except Exception as e: return JSONResponse({"error": str(e)}, 500)

@app.get("/stats/get")
def get_stats(username: str, period: str = "week"): # period: week, month, year
    safe_user = secure_path(username)
    user_file = os.path.join(USERS_DB_PATH, f"{safe_user}.txt")
    if not os.path.exists(user_file): return JSONResponse({"error": "User not found"}, 404)

    # Calculate time limits
    now = time.time()
    limit = 0
    if period == "week": limit = now - (7 * 24 * 3600)
    elif period == "month": limit = now - (30 * 24 * 3600)
    elif period == "year": limit = now - (365 * 24 * 3600)

    total_seconds = 0
    song_counts = {} # { "SongName": count }
    
    try:
        with open(user_file, 'r') as f:
            lines = f.read().splitlines()

        # Check config
        config = "DISABLED"
        for l in lines:
            if l.startswith("Wrapped:"): config = l.split(":")[1].strip()
        
        if config == "DISABLED":
            return {"enabled": False}

        for line in lines:
            if line.startswith("LOG|"):
                parts = line.split("|")
                # LOG|timestamp|duration|song|artist
                if len(parts) >= 4:
                    ts = float(parts[1])
                    if ts > limit:
                        dur = int(parts[2])
                        song = parts[3]
                        artist = parts[4] if len(parts) > 4 else "Unknown"
                        
                        total_seconds += dur
                        full_name = f"{song} - {artist}"
                        song_counts[full_name] = song_counts.get(full_name, 0) + 1
        
        # Sort Top 5
        sorted_songs = sorted(song_counts.items(), key=lambda item: item[1], reverse=True)[:5]
        top_5 = [{"name": k, "plays": v} for k, v in sorted_songs]

        return {
            "enabled": True,
            "period": period,
            "total_minutes": int(total_seconds / 60),
            "total_hours": round(total_seconds / 3600, 1),
            "top_5": top_5,
            "config": config
        }
    except Exception as e: return JSONResponse({"error": str(e)}, 500)
    
@app.get("/stats/community")
def get_community_stats(period: str = "week"):
    community = []
    
    # Calculate time limit
    now = time.time()
    limit = 0
    if period == "week": limit = now - (604800)
    elif period == "month": limit = now - (2592000)
    elif period == "year": limit = now - (31536000)

    try:
        if not os.path.exists(USERS_DB_PATH): return {"users": []}
        
        for filename in os.listdir(USERS_DB_PATH):
            if not filename.endswith(".txt"): continue
            
            username = filename.replace(".txt", "")
            filepath = os.path.join(USERS_DB_PATH, filename)
            
            with open(filepath, 'r') as f:
                lines = f.read().splitlines()
            
            # 1. Check if it's PUBLIC
            is_public = False
            for l in lines:
                if l == "Wrapped: PUBLIC":
                    is_public = True
                    break
            
            if is_public:
                # 2. Calculate their minutes for the ranking
                total_sec = 0
                for l in lines:
                    if l.startswith("LOG|"):
                        parts = l.split("|")
                        if len(parts) >= 3:
                            ts = float(parts[1])
                            if ts > limit:
                                total_sec += int(parts[2])
                
                community.append({
                    "username": username,
                    "minutes": int(total_sec / 60)
                })

        # Sort by who has listened to the most music
        community.sort(key=lambda x: x["minutes"], reverse=True)
        return {"users": community}
        
    except Exception as e: return JSONResponse({"error": str(e)}, 500)
    
@app.get("/vault/artists")
def get_vault_artists():
    try:
        if not os.path.exists(VAULT_DIR): return {"results": []}
        artists = [d for d in os.listdir(VAULT_DIR) if os.path.isdir(os.path.join(VAULT_DIR, d))]
        artists.sort(key=lambda s: s.lower())
        results = [{"type": "artist", "title": a, "artist": "Artist", "path_id": a} for a in artists]
        return {"results": results}
    except Exception as e: return JSONResponse({"error": str(e)}, 500)

@app.post("/share/create")
def create_share(data: ShareCreate):
    safe_user = secure_path(data.username)
    # Smart paths
    if data.path.startswith("General"):
        source_path = os.path.join(BASE_DIR, data.path)
    else:
        source_path = os.path.join(BASE_DIR, safe_user, data.path)
        
    if not os.path.exists(source_path):
        return JSONResponse({"error": "Playlist not found"}, 404)

    token = str(uuid.uuid4())
    shares = load_shares()
    # Save the owner and the shared path
    shares[token] = {"owner": safe_user, "path": data.path}
    save_shares(shares)

    return {"token": token}

@app.post("/share/import")
def import_share(data: ShareImport):
    shares = load_shares()
    if data.token not in shares:
        return JSONResponse({"error": "Invalid or expired link"}, 404)

    share_info = shares[data.token]
    owner = share_info["owner"]
    original_path = share_info["path"]
    safe_new_user = secure_path(data.target_username)

    if original_path.startswith("General"):
        src_dir = os.path.join(BASE_DIR, original_path)
    else:
        src_dir = os.path.join(BASE_DIR, owner, original_path)

    if not os.path.exists(src_dir):
        return JSONResponse({"error": "Original playlist no longer exists"}, 404)

    # Create folder for the new user
    playlist_name = os.path.basename(original_path)
    dest_dir = os.path.join(BASE_DIR, safe_new_user, f"{playlist_name}")

    # If one with that name already exists, add a number
    counter = 1
    while os.path.exists(dest_dir):
        dest_dir = os.path.join(BASE_DIR, safe_new_user, f"{playlist_name} (Shared {counter})")
        counter += 1

    os.makedirs(dest_dir)

    # Copy cover and symlinks of the songs (avoiding broken symlinks)
    count = 0
    for item in os.listdir(src_dir):
        src_item = os.path.join(src_dir, item)
        if os.path.isfile(src_item):
            dst_item = os.path.join(dest_dir, item)
            try:
                if src_item.lower().endswith(".mp3"):
                    real_src = os.path.realpath(src_item) # Find the real file in the Vault
                    os.symlink(real_src, dst_item)
                    count += 1
                elif item in ["cover.jpg", "cover.png"]:
                    shutil.copy2(src_item, dst_item)
            except: pass

    return {"success": True, "added": count, "new_playlist": os.path.basename(dest_dir)}

@app.get("/share/link", response_class=HTMLResponse)
def share_redirect(request: Request, token: str):
    shares = load_shares()
    if token not in shares:
        return HTMLResponse(content="<h1>Invalid or expired link</h1>", status_code=404)

    share_info = shares[token]
    owner = share_info["owner"]
    path = share_info["path"]

    # Extract the name of the playlist or song (e.g.: "Rock Classics")
    item_name = os.path.basename(path)
    if not item_name:
        item_name = "Shared Playlist"

    # Build the base public URL (detecting Cloudflare or proxies)
    scheme = request.headers.get("x-forwarded-proto", "http")
    host = request.headers.get("x-forwarded-host", request.headers.get("host", "localhost:5000"))
    base_url = f"{scheme}://{host}"

    # Build the cover URL. Telegram NEEDS an absolute URL.
    encoded_path = quote(path)
    cover_url = f"{base_url}/cover?username={owner}&path={encoded_path}"
    share_url = f"{base_url}/share/link?token={token}"

    # Generate the web with meta-tags for Telegram/WhatsApp
    html_content = f"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>{item_name} - ResoNode</title>

        <!-- Open Graph tags for Telegram, WhatsApp, Discord... -->
        <meta property="og:title" content="{item_name}">
        <meta property="og:description" content="This playlist has been shared with you. Click to open it in ResoNode!">
        <meta property="og:image" content="{cover_url}">
        <meta property="og:url" content="{share_url}">
        <meta property="og:type" content="music.playlist">

        <!-- Tags for Twitter/X -->
        <meta name="twitter:card" content="summary_large_image">
        <meta name="twitter:title" content="{item_name}">
        <meta name="twitter:description" content="Open in ResoNode!">
        <meta name="twitter:image" content="{cover_url}">

        <!-- Automatic redirection to the Android App -->
        <meta http-equiv="refresh" content="0; url=resonode://share?token={token}">
        <script>
            window.onload = function() {{
                window.location.href = "resonode://share?token={token}";
            }};
        </script>
    </head>
    <body style="background-color: #121212; color: #1DB954; font-family: Arial, sans-serif; text-align: center; padding-top: 20%;">
        <h1>ResoNode</h1>
        <h2 style="color: white;">Opening "{item_name}"...</h2>
        <p style="color: gray;">If the app doesn't open automatically, make sure you have it installed.</p>
    </body>
    </html>
    """
    
    return HTMLResponse(content=html_content)

@app.get("/share/resolve")
def share_resolve(token: str):
    shares = load_shares()
    if token not in shares: return JSONResponse({"error": "Invalid link"}, 404)
    info = shares[token]
    owner = info["owner"]
    path = info["path"]
    
    if path.startswith("General"):
        full_path = os.path.join(BASE_DIR, path)
    else:
        full_path = os.path.join(BASE_DIR, owner, path)
        
    is_file = os.path.isfile(full_path)
    
    # Extract the artist if it's a single song
    artist = "Unknown"
    if is_file and path.lower().endswith(".mp3"):
        try:
            audio = EasyID3(full_path)
            artist = audio.get('artist', ['Unknown'])[0]
        except: pass
        
    return {
        "type": "file" if is_file else "folder",
        "owner": owner,
        "path": path,
        "name": os.path.basename(path),
        "artist": artist
    }

class ShareImportItems(BaseModel):
    token: str
    target_username: str
    target_playlist: str
    items: List[str]

@app.post("/share/import_items")
def share_import_items(data: ShareImportItems):
    shares = load_shares()
    if data.token not in shares: return JSONResponse({"error": "Invalid link"}, 404)
    info = shares[data.token]
    owner = info["owner"]
    src_path = info["path"]
    
    safe_new_user = secure_path(data.target_username)
    safe_target_pl = secure_path(data.target_playlist)
    
    dest_dir = os.path.join(BASE_DIR, safe_new_user, safe_target_pl)
    if not os.path.exists(dest_dir): os.makedirs(dest_dir)
    
    if src_path.startswith("General"):
        full_src_path = os.path.join(BASE_DIR, src_path)
    else:
        full_src_path = os.path.join(BASE_DIR, owner, src_path)
        
    is_file = os.path.isfile(full_src_path)
    
    count = 0
    for filename in data.items:
        safe_file = secure_path(filename)
        
        # If a single song was shared, the source path is already the file
        if is_file:
            item_src = full_src_path
        else:
            item_src = os.path.join(full_src_path, safe_file)
            
        if os.path.isfile(item_src) and item_src.lower().endswith(".mp3"):
            dst_item = os.path.join(dest_dir, f"{get_next_prefix(dest_dir)}_{os.path.basename(item_src)}")
            try:
                real_src = os.path.realpath(item_src)
                os.symlink(real_src, dst_item)
                count += 1
            except: pass 
    return {"success": True, "added": count}

@app.get("/anime/server")
def get_anime_server():
    # Path where your script saves the Jellyfin Cloudflare URL
    file_path = "/home/adri/ULTIMA_URL_ANIME.txt"
    try:
        if os.path.exists(file_path):
            with open(file_path, "r", encoding="utf-8") as f:
                url = f.read().strip()
                if url:
                    return {"url": url}
        return JSONResponse({"error": "Anime URL not found"}, 404)
    except Exception as e:
        return JSONResponse({"error": str(e)}, 500)

if __name__ == "__main__":
    import uvicorn
    # 🔥 LISTEN ON ALL IPS 🔥
    print(f"🔥 INITIATING SERVER ON PORT {SERVER_PORT} 🔥")
    uvicorn.run(app, host="0.0.0.0", port=SERVER_PORT)

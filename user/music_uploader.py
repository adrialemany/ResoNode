import os
import sys
import shutil
import requests
import zipfile
import base64
import hashlib
import time
import json
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend

# --- SECURE CONFIGURATION IMPORT ---
# Get parent directory (ResoNode) and append 'server' folder to path
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(current_dir)
server_dir = os.path.join(parent_dir, "server")
sys.path.append(server_dir)

try:
    import server_config
except ImportError:
    print("❌ CRITICAL ERROR: 'server_config.py' not found in the 'server' folder.")
    sys.exit(1)

API_SECRET_KEY = server_config.API_SECRET_KEY
ADMIN_SECRET_KEY = getattr(server_config, 'ADMIN_SECRET_KEY', "")
URL_CASA = getattr(server_config, 'URL_CASA', "http://192.168.1.100:8000") 
GIST_ID = getattr(server_config, 'GIST_ID', "YOUR_GIST_ID_HERE")

# -----------------------------------------------

def decrypt_url_aes(ciphertext_base64, secret):
    try:
        key = hashlib.sha256(secret.encode('utf-8')).digest()
        decoded = base64.b64decode(ciphertext_base64)
        iv = decoded[:16]
        ciphertext = decoded[16:]
        
        cipher = Cipher(algorithms.AES(key), modes.CBC(iv), backend=default_backend())
        decryptor = cipher.decryptor()
        plaintext_padded = decryptor.update(ciphertext) + decryptor.finalize()
        
        pad_len = plaintext_padded[-1]
        return plaintext_padded[:-pad_len].decode('utf-8')
    except Exception as e:
        print(f"❌ Error decrypting link: {e}")
        return None

def get_url_from_gist():
    print("🌍 Fetching encrypted URL from GitHub Gist...")
    try:
        res = requests.get(f"https://api.github.com/gists/{GIST_ID}?nocache={int(time.time())}")
        if res.status_code == 200:
            data = res.json()
            content = data["files"]["resonode_url.json"]["content"]
            
            url_json = json.loads(content)
            encrypted_url = url_json["url"]
            
            clean_url = decrypt_url_aes(encrypted_url, API_SECRET_KEY)
            if clean_url:
                return clean_url
        return None
    except Exception as e:
        print(f"❌ Connection error fetching Gist: {e}")
        return None

def get_server_folders(base_url):
    try:
        url = f"{base_url}/system/folders"
        headers = {"x-secret-key": API_SECRET_KEY}
        r = requests.get(url, headers=headers, timeout=5)
        if r.status_code == 200:
            return r.json().get("folders", [])
        return []
    except:
        return []

def create_zip_from_folder(source_folder):
    zip_name = "temp_upload.zip"
    mp3_count = 0
    print(f"📦 Scanning directory: {source_folder}")
    
    with zipfile.ZipFile(zip_name, 'w', zipfile.ZIP_DEFLATED) as zipf:
        for root, dirs, files in os.walk(source_folder):
            for file in files:
                if file.lower().endswith(".mp3"):
                    full_path = os.path.join(root, file)
                    zipf.write(full_path, arcname=file)
                    mp3_count += 1
    
    if mp3_count == 0:
        print("❌ No .mp3 files found in the specified folder.")
        if os.path.exists(zip_name): os.remove(zip_name)
        return None
        
    print(f"📦 ZIP package created with {mp3_count} songs.")
    return zip_name

def main():
    print("\n🎵 --- RESONODE MUSIC UPLOADER --- 🎵\n")

    local_folder = input("📂 Drag and drop your music folder here: ").strip()
    local_folder = local_folder.replace('"', '').replace("'", "")
    
    if not os.path.isdir(local_folder):
        print("❌ Invalid directory path.")
        return

    print("\n🌍 Select target environment:")
    print("  [1] 🏠 Local Network (Direct IP)")
    print("  [2] 🌐 Remote Network (GitHub Gist + Cloudflare)")
    mode = input(" > ").strip()

    if mode == "1":
        if "192" in URL_CASA: 
             server_url = URL_CASA
        else:
             server_url = input("Enter Local Server URL (e.g., http://192.168.1.100:8000): ").strip()
    else:
        server_url = get_url_from_gist()
        if not server_url:
            print("⚠️ Failed to retrieve URL from Gist. Aborting.")
            return

    print(f"✅ Target Server: {server_url}")

    target_path = "" 
    print("\nAdd uploaded music directly to an existing Playlist?")
    print("  [y] YES")
    print("  [n] NO (Upload to Music Vault only)")
    opcion = input(" > ").lower().strip()
    
    if opcion.startswith('y'): 
        folders = get_server_folders(server_url)
        if folders:
            print("\n--- AVAILABLE SERVER FOLDERS ---")
            for c in folders: print(f" 📂 {c}")
        
        print("\nDefine destination:")
        user_dir = input("   User Folder (e.g., Adri): ").strip()
        pl_name = input("   Playlist Name (e.g., Favorites): ").strip()
        
        if user_dir and pl_name:
            target_path = f"{user_dir}/{pl_name}"
            print(f"✅ Target Path set to: [{target_path}]")
        else:
            print("⚠️ Incomplete details. Uploading to Vault only.")

    zip_file = create_zip_from_folder(local_folder)
    if not zip_file: return

    upload_endpoint = f"{server_url}/upload_zip"
    print(f"\n🚀 Uploading data to {upload_endpoint}...")

    try:
        # 🔥 SENDING SECURE CREDENTIALS AND ADMIN KEY 🔥
        headers = {
            "x-secret-key": API_SECRET_KEY,
            "x-admin-key": ADMIN_SECRET_KEY
        }
        
        files_up = {'file': ('music_upload.zip', open(zip_file, 'rb'), 'application/zip')}
        data_up = {}
        if target_path:
            data_up['target_playlist'] = target_path

        r = requests.post(upload_endpoint, files=files_up, data=data_up, headers=headers)
        
        files_up['file'][1].close()

        if r.status_code == 200:
            res = r.json()
            print(f"\n✨ SUCCESS! {res.get('processed')} files uploaded and processed.")
        elif r.status_code == 403:
            print(f"\n⛔ ACCESS DENIED: {r.json().get('error', 'Incorrect credentials')}")
        else:
            print(f"\n❌ Server Error ({r.status_code}): {r.text}")

    except Exception as e:
        print(f"\n❌ Connection Error: {e}")
    finally:
        if os.path.exists(zip_file): os.remove(zip_file)

if __name__ == "__main__":
    main()

import os
import sys
import shutil
import requests
import zipfile
import imaplib
import email
from email.header import decode_header

# --- IMPORT SECURE CONFIGURATION ---
# Add the 'server' folder to the path to import the config file
sys.path.append(os.path.join(os.getcwd(), "server"))

try:
    import server_config
except ImportError:
    print("❌ CRITICAL ERROR: 'server/server_config.py' not found.")
    print("   Make sure the configuration file exists.")
    sys.exit(1)

# Use variables from the imported config
API_SECRET_KEY = server_config.API_SECRET_KEY
GMAIL_USER = server_config.GMAIL_USER
GMAIL_PASS = server_config.GMAIL_PASS
# Assume you have a local IP in your config; otherwise, use the default below
URL_CASA = getattr(server_config, 'URL_CASA', "http://192.168.1.100:8000") 
EMAIL_SUBJECT = "Enlace a Cloudfare" # Keep this as is, must match the email subject exactly

# -----------------------------------------------

def get_url_from_gmail():
    print("📧 Connecting to Gmail to fetch public URL...")
    try:
        mail = imaplib.IMAP4_SSL("imap.gmail.com")
        mail.login(GMAIL_USER, GMAIL_PASS)
        mail.select("inbox")
        
        # Search by exact subject
        status, messages = mail.search(None, f'(SUBJECT "{EMAIL_SUBJECT}")')
        
        if status != "OK" or not messages[0]:
            print("⚠️ Connection email not found.")
            return None

        latest_id = messages[0].split()[-1]
        _, data = mail.fetch(latest_id, "(RFC822)")
        msg = email.message_from_bytes(data[0][1])
        
        body = ""
        if msg.is_multipart():
            for part in msg.walk():
                if part.get_content_type() == "text/plain":
                    body = part.get_payload(decode=True).decode()
                    break
        else:
            body = msg.get_payload(decode=True).decode()

        # Search for the line containing the link
        for line in body.splitlines():
            if "trycloudflare.com" in line:
                return line.strip()
        return None
    except Exception as e:
        print(f"❌ Error reading Gmail: {e}")
        return None

def get_server_folders(base_url):
    """Queries the server for existing folders to help you choose."""
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
    """Compresses only MP3s from the specified folder."""
    zip_name = "temp_upload.zip"
    mp3_count = 0
    
    print(f"📦 Analyzing folder: {source_folder}")
    
    with zipfile.ZipFile(zip_name, 'w', zipfile.ZIP_DEFLATED) as zipf:
        # Walk allows searching in subfolders as well
        for root, dirs, files in os.walk(source_folder):
            for file in files:
                if file.lower().endswith(".mp3"):
                    full_path = os.path.join(root, file)
                    # Save the file flat (without original folder structure) 
                    # for better server processing
                    zipf.write(full_path, arcname=file)
                    mp3_count += 1
    
    if mp3_count == 0:
        print("❌ No .mp3 files found in that folder.")
        if os.path.exists(zip_name): os.remove(zip_name)
        return None
        
    print(f"📦 ZIP created with {mp3_count} songs.")
    return zip_name

def main():
    print("\n🎵 --- RESONODE MUSIC UPLOADER --- 🎵\n")

    # --- 1. SELECT MUSIC FOLDER ---
    local_folder = input("📂 Drag and drop your music folder here: ").strip()
    # Clean quotes that Windows/Linux adds when dragging
    local_folder = local_folder.replace('"', '').replace("'", "")
    
    if not os.path.isdir(local_folder):
        print("❌ That is not a valid folder.")
        return

    # --- 2. ENVIRONMENT SELECTION ---
    print("\n🌍 Where are you?")
    print("  [1] 🏠 At Home (Local IP)")
    print("  [2] 🌐 Away (Gmail + Cloudflare)")
    modo = input(" > ").strip()

    if modo == "1":
        # If not in config, ask manually
        if "192" in URL_CASA: 
             server_url = URL_CASA
        else:
             server_url = input("Enter Local IP (e.g., http://192.168.1.XX:8000): ").strip()
    else:
        server_url = get_url_from_gmail()
        if not server_url:
            print("⚠️ Could not retrieve URL from Gmail.")
            return

    print(f"✅ Server detected: {server_url}")

    # --- 3. ASK DESTINATION (PLAYLIST) ---
    target_path = "" 
    print("\nAdd to existing Playlist?")
    print("  [s] YES")
    print("  [n] NO (Upload to Vault only)")
    opcion = input(" > ").lower().strip()
    
    if opcion.startswith('s'): 
        carpetas = get_server_folders(server_url)
        if carpetas:
            print("\n--- SERVER FOLDERS ---")
            for c in carpetas: print(f" 📂 {c}")
        else:
            print("\n(Could not list folders or empty)")
        
        print("\nDefine destination (User/Playlist):")
        user_dir = input("   User (e.g., Adri): ").strip()
        pl_name = input("   Playlist (e.g., Favorites): ").strip()
        
        if user_dir and pl_name:
            target_path = f"{user_dir}/{pl_name}"
            print(f"✅ Destination configured: [{target_path}]")
        else:
            print("⚠️ Incomplete data. Uploading to Vault only.")
    else:
        print("📦 Uploading to Vault only (no playlist).")

    # --- 4. COMPRESS AND UPLOAD ---
    zip_file = create_zip_from_folder(local_folder)
    if not zip_file: return

    upload_endpoint = f"{server_url}/upload_zip"
    print(f"\n🚀 Uploading to {upload_endpoint}...")

    try:
        headers = {"x-secret-key": API_SECRET_KEY}
        
        # Prepare data
        files_up = {'file': ('music_upload.zip', open(zip_file, 'rb'), 'application/zip')}
        data_up = {}
        if target_path:
            data_up['target_playlist'] = target_path

        r = requests.post(upload_endpoint, files=files_up, data=data_up, headers=headers)
        
        files_up['file'][1].close() # Close file

        if r.status_code == 200:
            res = r.json()
            print(f"\n✨ SUCCESS! {res.get('processed')} files processed.")
            print("   The server is organizing them right now.")
        elif r.status_code == 403:
            print("\n⛔ ACCESS DENIED: Check your API Key in server_config.py")
        else:
            print(f"\n❌ Server Error ({r.status_code}): {r.text}")

    except Exception as e:
        print(f"\n❌ Connection Error: {e}")
    finally:
        if os.path.exists(zip_file): os.remove(zip_file)

if __name__ == "__main__":
    main()

import os
import json
import subprocess
import glob
import requests
import imaplib
import email
from email.header import decode_header
import sys

# Try to import credentials from local config
try:
    import server_config
except ImportError:
    print("❌ ERROR: server_config.py not found. Please setup your config first.")
    sys.exit(1)

# --- CONFIGURATION ---
# Path to Android build folder (Relative to this script)
# Assumes this script is in project root and Android project is in /app
LOCAL_BUILD_PATH = os.path.join(os.getcwd(), "app", "build", "outputs", "apk", "debug")

def get_url_from_gmail():
    print("📧 Connecting to Gmail to fetch server URL...")
    try:
        mail = imaplib.IMAP4_SSL("imap.gmail.com")
        mail.login(server_config.GMAIL_USER, server_config.GMAIL_PASS)
        mail.select("inbox")
        status, messages = mail.search(None, f'(SUBJECT "Enlace a Cloudfare")') # Subject matches server_launcher.py
        
        if status != "OK" or not messages[0]:
            print("⚠️ No emails found with that subject.")
            return None

        latest_id = messages[0].split()[-1]
        _, data = mail.fetch(latest_id, "(RFC822)")
        msg = email.message_from_bytes(data[0][1])
        body = msg.get_payload(decode=True).decode() if not msg.is_multipart() else msg.get_payload(0).get_payload(decode=True).decode()
        
        # Extract URL
        for line in body.splitlines():
            if "trycloudflare.com" in line:
                return line.strip()
        return None
    except Exception as e:
        print(f"❌ Gmail Error: {e}")
        return None

def deploy_update():
    print("\n🚀 --- SPOTIFLY DEPLOYER --- 🚀\n")

    # 1. FIND APK
    print(f"🔍 Looking for APK in: {LOCAL_BUILD_PATH}")
    if not os.path.exists(LOCAL_BUILD_PATH):
        print(f"❌ Path not found. Did you build the APK in Android Studio?")
        return

    list_of_files = glob.glob(os.path.join(LOCAL_BUILD_PATH, "*.apk"))
    if not list_of_files:
        print("❌ No .apk files found.")
        return

    latest_apk = max(list_of_files, key=os.path.getctime)
    print(f"📦 Found: {os.path.basename(latest_apk)}")

    # 2. METADATA
    try:
        version_num = int(input(" > Version Code (e.g., 10): "))
    except ValueError: return

    print(" > Changelog (Type 'END' on a new line to finish):")
    lines = []
    while True:
        line = input("   📝: ")
        if line.strip().upper() == "END": break
        lines.append(line)
    
    changelog = "\n".join(lines) if lines else "Bug fixes and performance improvements."
    
    json_data = {"version": version_num, "changelog": changelog}
    json_file = "version.json"
    with open(json_file, 'w') as f: json.dump(json_data, f)

    # 3. DEPLOY
    print("\n🌍 Deploy Method?")
    print("  [1] SSH/SCP (Local Network)")
    print("  [2] HTTP Upload (Remote/Cloudflare)")
    mode = input(" > ").strip()

    if mode == "1":
        # Requires SSH setup on user machine
        server_ip = input("Server Local IP: ")
        server_user = input("Server User (e.g., adri): ")
        server_path = input("Remote Path (e.g., ~/server_music/updates/): ")
        
        print("⬆️ Uploading via SCP...")
        subprocess.run(["scp", latest_apk, json_file, f"{server_user}@{server_ip}:{server_path}"])
        print("✨ Done.")

    else:
        url = get_url_from_gmail()
        if not url: return
        
        upload_url = f"{url}/system/upload_update"
        print(f"⬆️ Uploading to {upload_url}...")
        
        try:
            files = {
                'apk_file': open(latest_apk, 'rb'),
                'json_file': open(json_file, 'rb')
            }
            headers = {"x-secret-key": server_config.API_SECRET_KEY}
            r = requests.post(upload_url, files=files, headers=headers)
            
            if r.status_code == 200: print("✨ Update Deployed Successfully!")
            else: print(f"❌ Error {r.status_code}: {r.text}")
        except Exception as e:
            print(f"❌ Connection Error: {e}")

    if os.path.exists(json_file): os.remove(json_file)

if __name__ == "__main__":
    deploy_update()

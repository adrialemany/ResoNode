import os
import json
import subprocess
import glob
import requests
import base64
import hashlib
import time
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend

# --- 🔐 CONFIGURATION ---

# YOUR CREDENTIALS (DO NOT UPLOAD REAL ONES TO GITHUB)
API_SECRET_KEY = "YOUR_API_SECRET_KEY"
ADMIN_SECRET_KEY = "YOUR_ADMIN_SECRET_KEY"
GIST_ID = "YOUR_GITHUB_GIST_ID"

# LOCAL CONFIGURATION (SSH/SCP)
SERVER_USER = "YOUR_SERVER_USERNAME"
SERVER_IP_LOCAL = "192.168.X.X"
SERVER_UPDATE_PATH = "~/server_music/updates/"

# LOCAL APK PATH
LOCAL_BUILD_PATH = os.path.expanduser("~/ResoNode/app/build/outputs/apk/debug/")

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
        print(f"❌ Error decrypting the link: {e}")
        return None

def get_url_from_gist():
    print("🌍 Searching for encrypted URL in GitHub Gist...")
    try:
        res = requests.get(f"https://api.github.com/gists/{GIST_ID}?nocache={int(time.time())}")
        if res.status_code == 200:
            data = res.json()
            content = data["files"]["resonode_url.json"]["content"]
            
            url_json = json.loads(content)
            encrypted_url = url_json["url"]
            
            clean_url = decrypt_url_aes(encrypted_url, API_SECRET_KEY)
            if clean_url:
                print(f"🔗 Decrypted URL: {clean_url}")
                return clean_url
        print("⚠️ Could not retrieve the Gist.")
        return None
    except Exception as e:
        print(f"❌ Gist connection error: {e}")
        return None

def deploy_update():
    print("\n🚀 --- RESONODE DEPLOYER v3 (Gist & Admin) --- 🚀\n")

    # --- 1. FIND LOCAL APK ---
    print(f"🔍 Searching for APK in: {LOCAL_BUILD_PATH}")
    list_of_files = glob.glob(os.path.join(LOCAL_BUILD_PATH, "*.apk"))
    
    if not list_of_files:
        print("❌ ERROR: No .apk found.")
        print("   Did you 'Build APK' in Android Studio?")
        return

    latest_apk = max(list_of_files, key=os.path.getctime)
    apk_name = os.path.basename(latest_apk)
    print(f"📦 APK found: {apk_name}")

    # --- 2. REQUEST VERSION AND CHANGELOG ---
    print("\nEnter update details:")
    try:
        version_num = int(input(" > Version number (e.g., 3): "))
    except ValueError: 
        print("❌ Must be a number.")
        return

    print(" > Write the Changelog.")
    print("   ℹ️  Write a line and press Enter.")
    print("   ℹ️  Type 'END' (and Enter) when finished to save.")
    print("   -------------------------------------------------------------")

    changelog_lines = []
    while True:
        line = input("   📝: ")
        if line.strip().upper() == "END":
            break
        changelog_lines.append(line)

    changelog_text = "\n".join(changelog_lines)

    if not changelog_text.strip(): 
        changelog_text = "General improvements."

    json_filename = "version.json"
    version_data = {
        "version": version_num,
        "changelog": changelog_text
    }
    
    with open(json_filename, 'w') as f:
        json.dump(version_data, f)

    # --- 3. ENVIRONMENT SELECTION ---
    print("\n🌍 Where are you deploying from?")
    print("  [1] 🏠 At Home (SSH/SCP - Fast and Direct)")
    print("  [2] 🌐 Outside (HTTP/GitHub Gist - Web)")
    mode = input(" > ").strip()

    # ==========================================
    # 🏠 HOME MODE (SSH / SCP)
    # ==========================================
    if mode == "1":
        print(f"\n📡 Connecting via SSH to {SERVER_IP_LOCAL}...")
        
        print("🧹 Cleaning server...")
        subprocess.run(
            ["ssh", f"{SERVER_USER}@{SERVER_IP_LOCAL}", f"rm {SERVER_UPDATE_PATH}*.apk"],
            stderr=subprocess.DEVNULL
        )

        print("⬆️ Uploading files...")
        cmd_upload = [
            "scp",
            latest_apk,
            json_filename,
            f"{SERVER_USER}@{SERVER_IP_LOCAL}:{SERVER_UPDATE_PATH}"
        ]
        result = subprocess.run(cmd_upload)

        if result.returncode == 0:
            print(f"\n✨ SSH DEPLOYMENT COMPLETED! v{version_num}")
        else:
            print("\n❌ SCP Error.")

    # ==========================================
    # 🌐 OUTSIDE MODE (HTTP POST)
    # ==========================================
    else:
        url = get_url_from_gist()
        if not url:
            print("❌ Could not get the URL. Aborting.")
            return

        upload_url = f"{url}/system/upload_update"
        
        print(f"\n📡 Sending files via HTTP to {upload_url}...")
        
        try:
            headers = {
                "x-secret-key": API_SECRET_KEY,
                "x-admin-key": ADMIN_SECRET_KEY
            }
            
            files = {
                'apk_file': open(latest_apk, 'rb'),
                'json_file': open(json_filename, 'rb')
            }
            
            r = requests.post(upload_url, files=files, headers=headers)
            
            if r.status_code == 200:
                print(f"\n✨ WEB DEPLOYMENT COMPLETED! v{version_num}")
                print(f"   Response: {r.json()}")
            elif r.status_code == 403:
                print(f"⛔ Access denied: {r.json().get('error', 'Incorrect key.')}")
            else:
                print(f"❌ Server Error ({r.status_code}): {r.text}")
                
            files['apk_file'].close()
            files['json_file'].close()

        except Exception as e:
            print(f"❌ Connection error: {e}")

    # --- FINAL LOCAL CLEANUP ---
    if os.path.exists(json_filename):
        os.remove(json_filename)

if __name__ == "__main__":
    deploy_update()

import requests
import json
import re
import os
import time
import base64
import hashlib
import sys
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.backends import default_backend

# --- ⚙️ SECURE CONFIGURATION IMPORT ---
try:
    import server_config
    API_SECRET_KEY = server_config.API_SECRET_KEY
    GITHUB_TOKEN = server_config.GITHUB_TOKEN
    GIST_ID = server_config.GIST_ID
except ImportError:
    print("❌ CRITICAL ERROR: 'server_config.py' not found.")
    sys.exit(1)
except AttributeError as e:
    print(f"❌ CONFIGURATION ERROR: Missing variable in server_config.py - {e}")
    sys.exit(1)

LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cf_music.log")

def extract_cloudflare_url():
    if os.path.exists(LOG_FILE):
        with open(LOG_FILE, "r") as f:
            content = f.read()
            # Find any link ending in .trycloudflare.com
            urls = re.findall(r'https://[a-zA-Z0-9-]+\.trycloudflare\.com', content)
            if urls:
                return urls[-1] # Always take the latest generated URL
    return None

def encrypt_url_aes(url, secret):
    # Generate 256-bit AES key using SHA-256
    key = hashlib.sha256(secret.encode('utf-8')).digest()
    iv = os.urandom(16) # Random Initialization Vector
    
    cipher = Cipher(algorithms.AES(key), modes.CBC(iv), backend=default_backend())
    encryptor = cipher.encryptor()
    
    # Add padding to make it a multiple of 16 bytes
    padder = padding.PKCS7(128).padder()
    padded_data = padder.update(url.encode('utf-8')) + padder.finalize()
    
    ciphertext = encryptor.update(padded_data) + encryptor.finalize()
    
    # Return (IV + Ciphertext) in Base64
    return base64.b64encode(iv + ciphertext).decode('utf-8')

def update_gist():
    new_url = extract_cloudflare_url()
    if not new_url:
        print("❌ Error: No Cloudflare URL found in the log.")
        return

    # Encrypt the URL
    encrypted_url = encrypt_url_aes(new_url, API_SECRET_KEY)
    print(f"🔒 Encrypted URL generated: {encrypted_url[:20]}...")
    
    # Upload to GitHub Gist
    headers = {
        "Authorization": f"token {GITHUB_TOKEN}",
        "Accept": "application/vnd.github.v3+json"
    }
    payload = {
        "files": {
            "resonode_url.json": {
                "content": json.dumps({"url": encrypted_url})
            }
        }
    }
    
    res = requests.patch(f"https://api.github.com/gists/{GIST_ID}", json=payload, headers=headers)
    
    if res.status_code == 200:
        print(f"✅ Gist updated successfully! The public URL is protected.")
    else:
        print(f"❌ Error updating the Gist: {res.status_code} - {res.text}")

if __name__ == "__main__":
    update_gist()

import subprocess
import time
import requests
import smtplib
from email.mime.text import MIMEText
import threading
import sys
import re

# Import configuration
try:
    import server_config
except ImportError:
    print("❌ ERROR: Please create 'server_config.py' first.")
    sys.exit(1)

def start_api():
    print(f"🎵 Starting SpotiFly API on port {server_config.PORT}...")
    # Launches uvicorn as a subprocess
    subprocess.Popen([sys.executable, "-m", "uvicorn", "api_musica:app", "--host", "0.0.0.0", "--port", str(server_config.PORT)])

def start_tunnel_and_notify():
    print("☁️ Starting Cloudflare Tunnel...")
    # Start cloudflared (ensure cloudflared is installed and in your PATH)
    # Redirect stderr to stdout to capture the URL log
    process = subprocess.Popen(
        ["cloudflared", "tunnel", "--url", f"http://localhost:{server_config.PORT}"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )

    tunnel_url = None
    
    # Read output line by line looking for the URL
    while True:
        line = process.stdout.readline()
        if not line: break
        
        # Regex to find trycloudflare.com URL
        match = re.search(r'https://[a-zA-Z0-9-]+\.trycloudflare\.com', line)
        if match:
            tunnel_url = match.group(0)
            print(f"✅ TUNNEL DETECTED: {tunnel_url}")
            send_email(tunnel_url)
            break 
            # We break the search loop, but let the cloudflared process run in background.

def send_email(url):
    print("📧 Sending URL via Email...")
    msg = MIMEText(f"{url}")
    msg['Subject'] = 'Enlace a Cloudfare' # Exact subject the App looks for
    msg['From'] = server_config.GMAIL_USER
    msg['To'] = server_config.DESTINATION_EMAIL

    try:
        with smtplib.SMTP_SSL('smtp.gmail.com', 465) as s:
            s.login(server_config.GMAIL_USER, server_config.GMAIL_PASS)
            s.sendmail(server_config.GMAIL_USER, server_config.DESTINATION_EMAIL, msg.as_string())
        print("🚀 Email sent successfully.")
    except Exception as e:
        print(f"❌ Error sending email: {e}")

if __name__ == "__main__":
    # 1. Start API in a separate thread (or background process)
    api_thread = threading.Thread(target=start_api)
    api_thread.start()
    
    # Wait a bit for API to initialize
    time.sleep(3)
    
    # 2. Start Tunnel and Notification logic
    start_tunnel_and_notify()
    
    # Keep script alive
    try:
        while True: time.sleep(1)
    except KeyboardInterrupt:
        print("\nShutting down services...")

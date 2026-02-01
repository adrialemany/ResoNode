# SpotiFly

**Self-hosted music streaming without barriers.**

SpotiFly is a self-hosted music streaming platform designed to provide full control over a personal music library. It serves as an alternative to commercial streaming services, allowing users to host their own server and access their music remotely without subscriptions, ads, or data tracking.

## Overview

Unlike other media servers like Plex or Jellyfin, SpotiFly is built with a specific focus on resilience and connection simplicity for users with dynamic IPs.

* **Data Sovereignty:** You host the MP3 files. The library is not subject to licensing agreements or removals.
* **Connection Mechanism:** Removes the need for static IPs, dynamic DNS (DDNS), or complex VPN configurations (like WireGuard/Tailscale). It uses a discovery system based on Cloudflare Tunnels and Gmail to locate the server automatically.
* **Hybrid Playback:**
    * **Streaming:** Plays directly from the server when an internet connection is available.
    * **Offline Mode:** Downloads playlists to local storage. The app manages the local database transparently, switching modes automatically when network connectivity is lost.
* **The Vault:** An automated file system that organizes uploads by Artist, Album, and Track.
* **Legacy Support:** Includes a custom TLS 1.2 socket factory implementation, allowing the client to run on older Android devices (Android 4.4+) as dedicated media players.

## System Architecture

The project consists of two main components:

1.  **Server (Python/FastAPI):** Runs on a host machine (Linux/Windows/Mac). Manages the file system, user authentication, and streaming logic.
2.  **Client (Android):** A native application optimized for low-latency streaming and offline synchronization.

### The Connection Logic

To bypass carrier-grade NAT (CGNAT) and dynamic IPs without user intervention:

1.  **Tunneling:** Upon startup, `server_launcher.py` initiates a secure Cloudflare Tunnel (`cloudflared`).
2.  **Assignment:** Cloudflare assigns a temporary, random public URL (e.g., `https://random-id.trycloudflare.com`).
3.  **Broadcasting:** The server detects this URL and emails it to a dedicated Gmail account via SMTP.
4.  **Discovery:** When the Android app launches, it checks the Gmail inbox via IMAP, retrieves the latest email from the server, and parses the new URL.
5.  **Handshake:** The app updates its configuration and establishes the connection.

## Server Installation

### Prerequisites
* Python 3.8 or higher.
* **Cloudflared:** The Cloudflare tunnel daemon must be installed and available in the system PATH.
* **Gmail Account:** A dedicated account is recommended for the handshake process. App Passwords must be enabled.

### Setup Steps

1.  **Install Dependencies:**
    ```bash
    pip install fastapi uvicorn python-multipart mutagen requests
    ```

2.  **Configuration:**
    * Locate `server_config_example.py` in the root directory.
    * Rename it to `server_config.py` (this file is git-ignored to prevent credential leakage).
    * Edit the file with your credentials:
        ```python
        API_SECRET_KEY = "YOUR_SECURE_KEY" # Must match the key in the Android App
        GMAIL_USER = "your_email@gmail.com"
        GMAIL_PASS = "your_app_password"
        DESTINATION_EMAIL = "destination_email@gmail.com"
        PORT = 8000
        ```

3.  **Run the Server:**
    Execute the launcher script. This will start the API, initialize the tunnel, and send the connection email.
    ```bash
    python server_launcher.py
    ```

## Client Installation (Android)

1.  Open the project in **Android Studio**.
2.  **Security Configuration:**
    * Navigate to `app/src/main/java/com/example/spotifly/`.
    * Modify the file named `Config.java` using it as a template.
    * Fill in the required fields:
        * `API_SECRET_KEY`: Must match the server key exactly.
        * `GMAIL_EMAIL` / `PASSWORD`: Credentials used to read the connection email.
3.  **Build:** compile the APK and install it on the target device.

## Uploading Music

This repository does not include a music downloader. The server exposes an API endpoint to receive and organize music libraries.

### Upload Method

Send a **ZIP** file containing MP3s via a POST request. The server extracts the files, reads ID3 tags (Artist, Album, Title), and sorts them into the `MusicVault` directory.

**Endpoint Specification:**
* **URL:** `/upload_zip`
* **Method:** `POST`
* **Headers:**
    * `x-secret-key`: [YOUR_API_SECRET_KEY]
* **Body (Multipart/Form-Data):**
    * `file`: The `.zip` file containing MP3s.
    * `target_playlist`: (Optional) Name of a playlist (e.g., "Favorites") to automatically create symlinks for the uploaded tracks.

**Note:** Ensure MP3 files have valid ID3 tags and embedded cover art before uploading for the best experience.

## Disclaimer

This project is a proof of concept regarding media streaming and network traversal. Users are responsible for ensuring they have the legal rights to the media files hosted, streamed, and downloaded on their private servers.

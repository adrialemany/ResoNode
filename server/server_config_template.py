# server_config_template.py
# [PUBLIC TEMPLATE]
# RENAME this file to 'server_config.py' on your Ubuntu server and fill in your real data.

# Security Key (Must match the one in the Android App Config)
API_SECRET_KEY = "put_your_secret_key_here"

# Administrator Key (To upload APKs and music remotely)
ADMIN_SECRET_KEY = "put_your_admin_key_here"

# GitHub Configuration for dynamic IP Discovery
GITHUB_TOKEN = "put_your_github_personal_access_token_here"
GIST_ID = "put_your_gist_id_here"

# Port where the API will run locally
PORT = 5000

#!/bin/sh
set -e

echo "[garage-init] Waiting for Garage daemon to be ready..."
until /garage status >/dev/null 2>&1; do
  sleep 1
done

echo "[garage-init] Garage daemon is responding."

# Get local node ID
NODE_ID=$(/garage node id -q 2>/dev/null || true)
if [ -z "$NODE_ID" ]; then
  NODE_ID=$(/garage status | grep -E "^[a-f0-9]{64}" | head -n1 | awk '{print $1}')
fi

echo "[garage-init] Local Node ID: $NODE_ID"

# Check if layout is already configured
if /garage status | grep -q "NO ROLE ASSIGNED"; then
  echo "[garage-init] Assigning single-node layout (zone dc1, capacity 10G)..."
  /garage layout assign -z dc1 -c 10G "$NODE_ID"
  CURRENT_VERSION=$(/garage layout show | grep "Current cluster version:" | awk '{print $4}')
  NEXT_VERSION=$((CURRENT_VERSION + 1))
  /garage layout apply --version "$NEXT_VERSION"
  echo "[garage-init] Layout applied successfully."
else
  echo "[garage-init] Cluster layout already initialized."
fi

# Ensure API Key exists
KEY_NAME="comtrak-key"
KEY_INFO=$(/garage key list 2>/dev/null | grep "$KEY_NAME" || true)

if [ -z "$KEY_INFO" ]; then
  echo "[garage-init] Creating API Key '$KEY_NAME'..."
  # If specified in environment, import or create
  if [ -n "$GARAGE_ACCESS_KEY" ] && [ -n "$GARAGE_SECRET_KEY" ]; then
    /garage key import --name "$KEY_NAME" "$GARAGE_ACCESS_KEY" "$GARAGE_SECRET_KEY" || /garage key create "$KEY_NAME"
  else
    /garage key create "$KEY_NAME"
  fi
else
  echo "[garage-init] API Key '$KEY_NAME' already exists."
fi

# Ensure Bucket exists
BUCKET_NAME="${GARAGE_BUCKET:-comtrak-media}"
if ! /garage bucket list 2>/dev/null | grep -q "$BUCKET_NAME"; then
  echo "[garage-init] Creating bucket '$BUCKET_NAME'..."
  /garage bucket create "$BUCKET_NAME"
  /garage bucket allow --read --write "$BUCKET_NAME" --key "$KEY_NAME"
  echo "[garage-init] Bucket '$BUCKET_NAME' created and permissions granted."
else
  echo "[garage-init] Bucket '$BUCKET_NAME' already exists."
fi

echo "[garage-init] Initialization complete."

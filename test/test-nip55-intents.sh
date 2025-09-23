#!/bin/bash

echo "Testing NIP-55 intents with proper Android intent extras..."

echo "1. Testing get_public_key..."
adb shell am start \
  -a android.intent.action.VIEW \
  -d "nostrsigner:" \
  --es "type" "get_public_key" \
  com.frostr.igloo

echo
echo "2. Testing sign_event..."
EVENT_JSON='{"kind":1,"content":"test event","tags":[],"created_at":1234567890}'
adb shell am start \
  -a android.intent.action.VIEW \
  -d "nostrsigner:$EVENT_JSON" \
  --es "type" "sign_event" \
  --es "id" "test_event_123" \
  --es "current_user" "test_pubkey_abc" \
  com.frostr.igloo

echo
echo "3. Testing nip04_encrypt..."
adb shell am start \
  -a android.intent.action.VIEW \
  -d "nostrsigner:hello world" \
  --es "type" "nip04_encrypt" \
  --es "id" "encrypt_test_456" \
  --es "current_user" "test_pubkey_abc" \
  --es "pubkey" "target_pubkey_def" \
  com.frostr.igloo

echo
echo "4. Testing nip04_decrypt..."
adb shell am start \
  -a android.intent.action.VIEW \
  -d "nostrsigner:encrypted_data_here" \
  --es "type" "nip04_decrypt" \
  --es "id" "decrypt_test_789" \
  --es "current_user" "test_pubkey_abc" \
  --es "pubkey" "source_pubkey_ghi" \
  com.frostr.igloo

echo
echo "All intents sent! Check the Android app logs and PWA console."
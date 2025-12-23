#!/bin/bash
# Generate QR code in terminal
# Usage: ./qr.sh "your string here"

if [ -z "$1" ]; then
    echo "Usage: $0 <string>"
    exit 1
fi

qrencode -m 2 -t ANSIUTF8 "$1"

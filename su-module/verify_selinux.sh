#!/system/bin/sh
# Futon Daemon - SELinux verification
# SPDX-License-Identifier: GPL-3.0-or-later

echo "=== Futon SELinux Verification ==="
echo ""

echo "[1] Enforcement: $(getenforce 2>/dev/null || echo 'unknown')"
echo ""

echo "[2] Android: $(getprop ro.build.version.release) (API $(getprop ro.build.version.sdk))"
echo ""

echo "[3] AVC denials (futon):"
dmesg 2>/dev/null | grep -i "avc.*futon" | tail -5 || echo "    none"
echo ""

echo "[4] Graphics denials:"
dmesg 2>/dev/null | grep -i "avc.*\(surfaceflinger\|gpu\|graphics\)" | tail -3 || echo "    none"
echo ""

echo "[5] Input denials:"
dmesg 2>/dev/null | grep -i "avc.*\(input\|uhid\)" | tail -3 || echo "    none"
echo ""

PID_FILE="/data/local/tmp/futon_daemon.pid"
echo "[6] Daemon:"
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "    PID: $PID"
        CONTEXT=$(cat /proc/$PID/attr/current 2>/dev/null)
        echo "    Context: $CONTEXT"
        echo "$CONTEXT" | grep -q "futon_daemon" && echo "    Status: OK" || echo "    Status: WARN (wrong context)"
    else
        echo "    Status: dead"
    fi
else
    echo "    Status: not running"
fi
echo ""

echo "[7] Recent logs:"
tail -10 /data/local/tmp/futon_daemon.log 2>/dev/null || echo "    no logs"

#!/usr/bin/env bash
# pull-app-db.sh — 把 dSIM 在模拟器/真机上的私有数据库拉到本地并可直接用 sqlite 读。
#
# 用法：
#   scripts/pull-app-db.sh <serial> [输出目录]
#   scripts/pull-app-db.sh emulator-5554
#
# 为什么需要它：
#   1. Room 默认是 WAL 模式，**只拉 .db 会得到一个 4 KB 的空头文件**，
#      数据实际在 `-wal` 里。必须 db + wal + shm 三件套一起拉。
#   2. Android 镜像里没有 sqlite3 二进制，不能在设备上直接查。
#      （`run-as ... sqlite3` 会报 "exec failed: No such file or directory"）
#   3. 需要 debug 版 APK 才能用 `run-as`（release 版不可调试）。

set -u

# Windows 控制台默认代码页是 GBK，bash 回显的中文会被解码成乱码。
# 切到 UTF-8；对 AI 读取脚本输出来说是必需的。
chcp.com 65001 >/dev/null 2>&1 || true

SERIAL="${1:?用法: pull-app-db.sh <serial> [输出目录]}"
OUT="${2:-tmp_dbpull/$SERIAL}"
PACKAGE="com.example.dsim"
DBNAME="dsim_core_database"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}}"
ADB="$SDK/platform-tools/adb.exe"

mkdir -p "$OUT"

if ! "$ADB" -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE"; then
  echo "设备 $SERIAL 上未安装 $PACKAGE" >&2
  exit 1
fi

echo "拉取 $SERIAL 的应用数据库 → $OUT"
for suffix in "" "-wal" "-shm"; do
  "$ADB" -s "$SERIAL" exec-out \
    "run-as $PACKAGE cat /data/data/$PACKAGE/databases/$DBNAME$suffix" \
    > "$OUT/$DBNAME$suffix" 2>/dev/null
done

for suffix in "" "-wal" "-shm"; do
  f="$OUT/$DBNAME$suffix"
  if [ -f "$f" ]; then
    printf '  %-32s %s 字节\n' "$DBNAME$suffix" "$(wc -c < "$f")"
  fi
done

echo
echo "读取方式（本地 CPython 自带 sqlite3）："
echo "  python -c \"import sqlite3;c=sqlite3.connect(r'$OUT/$DBNAME');print(list(c.execute('SELECT deviceId,deviceName,isLocalDevice,source FROM device_profiles')))\""

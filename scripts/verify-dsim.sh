#!/usr/bin/env bash
# verify-dsim.sh — dSIM 回归验证：短信采集链路 + 通知 + 跨设备发现。
#
# 用法：
#   scripts/verify-dsim.sh <serial> [peer-serial]
#   scripts/verify-dsim.sh emulator-5554                 # 只验证单机链路
#   scripts/verify-dsim.sh emulator-5554 emulator-5556   # 同时验证跨设备发现
#
# 退出码：0 = 全部通过；1 = 有检查项失败（失败项会逐条打印）
#
# 前置：设备已启动、已用 onboard-device.sh 完成配置。
#
# 设计取舍：
#   - 只做"可客观判定"的检查（日志关键字、数据库字段、进程状态），不做截图比对，
#     这样结果对分辨率/主题变化不敏感，也不会因渲染差异误报。

set -u

# Windows 控制台默认代码页是 GBK，bash 回显的中文会被解码成乱码。
# 切到 UTF-8；对 AI 读取脚本输出来说是必需的。
chcp.com 65001 >/dev/null 2>&1 || true

SERIAL="${1:?用法: verify-dsim.sh <serial> [peer-serial]}"
PEER="${2:-}"
SENDER="${VDSIM_SENDER:-10086}"
BODY="${VDSIM_BODY:-【自动验证】验证码 246810，请勿泄露。}"

cd "$(dirname "$0")/.."

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}}"
ADB="$SDK/platform-tools/adb.exe"
PY="${VDSIM_PYTHON:-C:/Users/admin/.workbuddy/binaries/python/versions/3.13.12/python.exe}"
PACKAGE="com.example.dsim"

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; }
step() { echo; echo "== $1 =="; }

sh_() { "$ADB" -s "$SERIAL" "$@"; }

# ---------------------------------------------------------------- 前置
step "Preflight ($SERIAL)"
if ! sh_ shell true >/dev/null 2>&1; then
  echo "device not available: $SERIAL" >&2; exit 1
fi
if ! sh_ shell pm list packages 2>/dev/null | grep -q "$PACKAGE"; then
  echo "$PACKAGE not installed; run scripts/onboard-device.sh first" >&2; exit 1
fi
ok "device online, package installed"

SIMSTATE="$(sh_ shell "dumpsys isub | grep -oE 'mSimState\[0\]=[A-Z]+'" 2>/dev/null | tr -d '\r' | head -1)"
SLOT="$(sh_ shell "dumpsys isub | grep -oE 'simSlotIndex=[-0-9]+'" 2>/dev/null | tr -d '\r' | head -1)"
if [ "$SIMSTATE" = "mSimState[0]=LOADED" ] && [ "$SLOT" = "simSlotIndex=0" ]; then
  ok "SIM mounted ($SIMSTATE, $SLOT)"
else
  bad "SIM not mounted ($SIMSTATE, $SLOT) -- check host outbound IPv6 (TESTING.md 0.1)"
fi

# Android 16 上 stopped 应用收不到广播，必须先启动一次
sh_ shell am start -n "$PACKAGE/.SmsListActivity" >/dev/null 2>&1
sleep 4

# ---------------------------------------------------------------- 采集
step "Inject SMS and verify capture"
sh_ logcat -c >/dev/null 2>&1
sh_ emu sms send "$SENDER" "$BODY" >/dev/null 2>&1
sleep 8

LOG="$(sh_ logcat -d 2>/dev/null | grep dSIM_Receiver | tail -3)"
if echo "$LOG" | grep -q "Captured incoming SMS"; then
  ok "SmsReceiver captured the SMS"
  echo "$LOG" | grep -oE "action=[^,]+" | head -1 | sed 's/^/         /'
  echo "$LOG" | grep -oE "mappingKey=[^ ]+" | head -1 | sed 's/^/         /'
else
  bad "SmsReceiver did not capture (did you am start? permissions granted?)"
fi

# ---------------------------------------------------------------- 落库
step "Verify Room persistence"
bash scripts/pull-app-db.sh "$SERIAL" "tmp_dbpull/$SERIAL" >/dev/null 2>&1
DB="tmp_dbpull/$SERIAL/dsim_core_database"
if [ -f "$DB" ]; then
  "$PY" - "$DB" "$SENDER" "$BODY" <<'PYEOF'
import sqlite3, sys
db, sender, body = sys.argv[1], sys.argv[2], sys.argv[3]
c = sqlite3.connect(db)
row = c.execute(
    "SELECT id,address,body,type,status,simId,mappingKey FROM sms_messages "
    "WHERE address=? ORDER BY id DESC LIMIT 1", (sender,)).fetchone()
if not row:
    print("  [FAIL] no row in sms_messages from %s" % sender); sys.exit(1)
_id, addr, got_body, typ, status, sim_id, key = row
print('  [PASS] persisted: id=%s type=%s status=%s simId=%s' % (_id, typ, status, sim_id))
print('         mappingKey=%s' % key)
if got_body.strip() != body.strip():
    print('  [FAIL] 正文不一致\n         期望 %r\n         实际 %r' % (body, got_body)); sys.exit(1)
print("  [PASS] body matches")
# 顺带校验 uuid 唯一索引仍在（跨设备幂等的基础，AGENTS.md C2）
idx = [r[0] for r in c.execute(
    "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='sms_messages' AND sql LIKE '%UNIQUE%'")]
if any('uuid' in (s or '') for s in idx):
    print("  [PASS] uuid unique index present")
else:
    print("  [FAIL] uuid unique index MISSING (breaks cross-device idempotency)"); sys.exit(1)
PYEOF
  if [ $? -eq 0 ]; then PASS=$((PASS+3)); else FAIL=$((FAIL+1)); fi
else
  bad "failed to pull app database"
fi

# ---------------------------------------------------------------- 通知
step "Verify notification"
NOTI="$(sh_ shell "dumpsys notification --noredact 2>/dev/null | grep -oE 'channel=dsim_(loud|silent)_v1'" 2>/dev/null | tr -d '\r' | head -1)"
if [ -n "$NOTI" ]; then
  ok "notification posted ($NOTI)"
else
  bad "no dSIM notification record found"
fi

# ---------------------------------------------------------------- 前台服务
step "Verify sync service"
FG="$(sh_ shell "dumpsys activity services $PACKAGE 2>/dev/null | grep -c 'isForeground=true'" 2>/dev/null | tr -d '\r')"
if [ "${FG:-0}" -ge 1 ]; then
  ok "MqttSyncService running in foreground"
else
  bad "MqttSyncService not in foreground (check cloud config / usage mode)"
fi

# ---------------------------------------------------------------- 跨设备
if [ -n "$PEER" ]; then
  step "Verify cross-device discovery ($SERIAL <-> $PEER)"
  echo "  waiting one snapshot interval (20s)..."
  sleep 24
  bash scripts/pull-app-db.sh "$SERIAL" "tmp_dbpull/$SERIAL" >/dev/null 2>&1
  bash scripts/pull-app-db.sh "$PEER"   "tmp_dbpull/$PEER"   >/dev/null 2>&1
  "$PY" - "tmp_dbpull/$SERIAL/dsim_core_database" "tmp_dbpull/$PEER/dsim_core_database" "$SERIAL" "$PEER" <<'PYEOF'
import sqlite3, sys
db_a, db_b, ser_a, ser_b = sys.argv[1:5]
def profiles(db):
    try:
        c = sqlite3.connect(db)
        return {r[0]: (r[1], r[2]) for r in c.execute(
            "SELECT deviceId,isLocalDevice,source FROM device_profiles")}
    except Exception:
        return {}
pa, pb = profiles(db_a), profiles(db_b)
if not pa or not pb:
    print("  [FAIL] device_profiles empty; snapshots may not be syncing"); sys.exit(1)
local_a = [k for k, v in pa.items() if v[0] == 1]
remote_a = [k for k, v in pa.items() if v[0] == 0]
local_b = [k for k, v in pb.items() if v[0] == 1]
remote_b = [k for k, v in pb.items() if v[0] == 0]
ok = True
if not local_a or not local_b:
    print("  [FAIL] missing LOCAL profile on one side"); ok = False
else:
    print("  [PASS] %s local=%s" % (ser_a, local_a[0]))
    print("  [PASS] %s local=%s" % (ser_b, local_b[0]))
    if local_a[0] == local_b[0]:
        print("  [FAIL] both sides share the same deviceId -- invalid cross-device test"); ok = False
    else:
        print("  [PASS] deviceIds differ")
    if local_a[0] in remote_b and local_b[0] in remote_a:
        print("  [PASS] mutual discovery OK")
    else:
        print("  [FAIL] no mutual discovery: remote(%s)=%s remote(%s)=%s" % (ser_a, remote_a, ser_b, remote_b))
        ok = False
sys.exit(0 if ok else 1)
PYEOF
  if [ $? -eq 0 ]; then PASS=$((PASS+4)); else FAIL=$((FAIL+1)); fi
fi

# ---------------------------------------------------------------- 汇总
echo
echo "================================"
echo "  PASS=$PASS  FAIL=$FAIL"
echo "================================"
[ "$FAIL" -eq 0 ] || exit 1

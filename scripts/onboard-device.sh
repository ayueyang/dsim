#!/usr/bin/env bash
# onboard-device.sh — 用 adb 无人值守完成 dSIM 首次引导 + 云端配置 + SIM 绑定。
#
# 用法：
#   scripts/onboard-device.sh <serial> <topic> [password] [phone]
#   例：scripts/onboard-device.sh emulator-5554 dsim/test/9f3a2b dsimTest2026
#
# 前置：设备已启动、APK 已安装。脚本内部会自行完成授权与默认短信角色设置。
#
# 设计说明：
#   - 全程走真实 UI 路径（uiautomator + input），不做 prefs 直写，
#     因此它同时充当引导页本身的回归测试。
#   - 该流程已在 Android 16 / dSIM 上实测跑通。
#
# 已知约束：
#   - `input text` 只支持 ASCII，所以 topic/password 必须是 ASCII。
#   - 反诈告知有 8 秒倒计时，未结束前点确认会被拒；且**未确认前选择使用模式会被静默忽略**。
#     因此顺序必须是：先等倒计时 → 选模式 → 点确认。
#   - 软键盘会遮挡下半屏按钮（uiauto.sh 的 tap/tapid 已自动收键盘）。

# 注意：这里**不能**用 `set -e`。
# UI 驱动脚本里几乎每一步（找元素、点击、读取状态）都可能在"这一帧还没有该元素"时返回非零，
# 那是正常现象而不是错误。用 `set -e` 会让脚本在第一处未命中就静默退出，
# 表现为"输出到某一行就没了"，极难排查。
set -u

# Windows 控制台默认代码页是 GBK，bash 回显的中文会被解码成乱码。
# 切到 UTF-8；对 AI 读取脚本输出来说是必需的。
chcp.com 65001 >/dev/null 2>&1 || true

cd "$(dirname "$0")/.."
UIAUTO="scripts/uiauto.sh"

SERIAL="${1:?用法: onboard-device.sh <serial> <topic> [password] [phone]}"
TOPIC="${2:?缺少 topic}"
PASSWORD="${3:-dsimTest2026}"
PHONE="${4:-}"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}}"
ADB="$SDK/platform-tools/adb.exe"
PACKAGE="com.example.dsim"

ui() { bash "$UIAUTO" "$@"; }
sh_() { "$ADB" -s "$SERIAL" "$@"; }

echo "=== [$SERIAL] 引导开始 ==="
ui wake "$SERIAL" >/dev/null

echo "--- 安装 APK（已装则覆盖安装）---"
APK="${DSIM_APK:-app/build/outputs/apk/debug/app-debug.apk}"
if [ ! -f "$APK" ]; then
  echo "找不到 APK：$APK" >&2
  echo "请先执行 ./gradlew :app:assembleDebug，或用 DSIM_APK=... 指定路径" >&2
  exit 1
fi
if ! sh_ install -r "$APK" 2>&1 | grep -q Success; then
  echo "安装失败" >&2
  exit 1
fi
echo "  已安装：$APK"

echo "--- 权限与默认短信角色 ---"
for p in READ_SMS RECEIVE_SMS SEND_SMS READ_PHONE_STATE READ_PHONE_NUMBERS \
         POST_NOTIFICATIONS READ_CONTACTS RECEIVE_MMS; do
  sh_ shell pm grant "$PACKAGE" "android.permission.$p" >/dev/null 2>&1 || true
done
sh_ shell cmd role add-role-holder android.app.role.SMS "$PACKAGE" >/dev/null 2>&1 || true
echo "  已授权，默认短信角色：$(sh_ shell cmd role get-role-holders android.app.role.SMS 2>/dev/null | tr -d '\r' | head -1)"

echo "--- 启动引导页 ---"
# Android 16 新装/重启后应用处于 stopped 状态，不先启动一次收不到广播
sh_ shell am start -n "$PACKAGE/.SmsListActivity" >/dev/null 2>&1 || true
sleep 4
sh_ shell am start -n "$PACKAGE/.OnboardingActivity" >/dev/null 2>&1

echo "--- 步骤 1/6：反诈告知 + 使用模式 ---"
# 必须等倒计时结束，否则确认按钮不可用
for _ in $(seq 1 12); do
  if ui wait "$SERIAL" "我已了解风险，继续使用" 4 >/dev/null 2>&1; then break; fi
  sleep 2
done
# 先选模式再确认：未确认时 handleModeSelection 会直接 return
ui tapid "$SERIAL" "cardModeBidirectional" >/dev/null 2>&1 || true
sleep 1
# 确认按钮常在视口下方，先滚动
sh_ shell input swipe 540 1900 540 600 400 >/dev/null 2>&1
sleep 1
ui tapid "$SERIAL" "btnAcknowledgeAntiFraud" >/dev/null 2>&1 || true
sleep 2
# 确认后模式选择才生效，再点一次
ui tapid "$SERIAL" "cardModeBidirectional" >/dev/null 2>&1 || true
sleep 1
ui tapid "$SERIAL" "btnOnboardingPrimary" >/dev/null 2>&1 || true
sleep 3

echo "--- 步骤 2-3/6：权限、默认短信（已由 adb 预置，直接下一步）---"
for _ in 1 2; do
  ui tapid "$SERIAL" "btnOnboardingPrimary" >/dev/null 2>&1 || true
  sleep 3
done

echo "--- 步骤 4/6：云端配置 ---"
ui tapid "$SERIAL" "etOnboardingTopic" >/dev/null 2>&1 || true
sleep 1
ui clearfield "$SERIAL" 40 >/dev/null 2>&1 || true
ui type "$SERIAL" "$TOPIC" >/dev/null 2>&1
sleep 1
ui tapid "$SERIAL" "etOnboardingPassword" >/dev/null 2>&1 || true
sleep 1
ui clearfield "$SERIAL" 40 >/dev/null 2>&1 || true
ui type "$SERIAL" "$PASSWORD" >/dev/null 2>&1
sleep 1
# tapid 内部会先收键盘，避免点击落到软键盘上
ui tapid "$SERIAL" "btnSaveCloudConfigStep" >/dev/null 2>&1 || true
sleep 4
ui tapid "$SERIAL" "btnOnboardingPrimary" >/dev/null 2>&1 || true
sleep 3

echo "--- 步骤 5/6：绑定本机 SIM ---"
ui tapid "$SERIAL" "btnOpenSimBindingStep" >/dev/null 2>&1 || true
sleep 5
ui tap "$SERIAL" "探测并绑定本机 SIM" >/dev/null 2>&1 || true
sleep 6
if [ -n "$PHONE" ]; then
  # 探测结果会自动带出模拟器号码；仅在显式指定时才覆盖
  ui tapid "$SERIAL" "custom" >/dev/null 2>&1 || true
  sleep 1
  ui clearfield "$SERIAL" 30 >/dev/null 2>&1 || true
  ui type "$SERIAL" "$PHONE" >/dev/null 2>&1
  sleep 1
fi
ui tap "$SERIAL" "保存绑定" >/dev/null 2>&1 || true
sleep 5
sh_ shell input keyevent KEYCODE_BACK >/dev/null 2>&1
sleep 3

echo "--- 步骤 6/6：完成 ---"
ui tapid "$SERIAL" "btnOnboardingPrimary" >/dev/null 2>&1 || true
sleep 8

echo
echo "=== [$SERIAL] 校验 ==="
echo -n "  顶层 Activity: "; ui activities "$SERIAL" 2>/dev/null | head -1
echo "  prefs："
sh_ exec-out "run-as $PACKAGE cat /data/data/$PACKAGE/shared_prefs/dSIM_UI_PREFS.xml" 2>/dev/null \
  | tr -d '\r' | grep -oE 'name="[A-Z_]+"' | sed 's/name=/    /' | sort -u
echo -n "  前台服务: "
sh_ shell "dumpsys activity services $PACKAGE 2>/dev/null | grep -c 'isForeground=true'" 2>/dev/null | tr -d '\r'
echo "  （1 = MqttSyncService 已在前台运行）"
echo
echo "提示：确认连接成功可看日志"
echo "  $ADB -s $SERIAL logcat -d | grep dSIM_SyncService | tail"

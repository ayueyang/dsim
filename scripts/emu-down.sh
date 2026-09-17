#!/usr/bin/env bash
# 关闭 dSIM 测试用的全部模拟器实例。
# 优先用模拟器自己的控制台命令优雅退出，再兜底清理残留锁。

set -u

# Windows 控制台默认代码页是 GBK，bash 回显的中文会被解码成乱码。
# 切到 UTF-8；对 AI 读取脚本输出来说是必需的。
chcp.com 65001 >/dev/null 2>&1 || true

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}}"
ADB="$SDK/platform-tools/adb.exe"

for port in 5554 5556 5558 5560; do
  serial="emulator-$port"
  if "$ADB" -s "$serial" shell true >/dev/null 2>&1; then
    echo "关闭 $serial …"
    "$ADB" -s "$serial" emu kill >/dev/null 2>&1
  fi
done

sleep 8

# 残留进程兜底
left=$(tasklist 2>/dev/null | grep -icE 'qemu-system|netsimd')
if [ "${left:-0}" != "0" ]; then
  echo "仍有 $left 个残留进程，尝试强制结束…"
  taskkill //F //IM qemu-system-x86_64-headless.exe >/dev/null 2>&1
  taskkill //F //IM netsimd.exe >/dev/null 2>&1
  sleep 3
fi

# 清理锁文件，避免下次启动报 multi-instance
rm -f "$HOME/.android/avd/"*/*.lock 2>/dev/null

echo
echo "剩余相关进程：$(tasklist 2>/dev/null | grep -icE 'qemu-system|netsimd')"

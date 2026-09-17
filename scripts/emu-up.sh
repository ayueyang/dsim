#!/usr/bin/env bash
# dSIM 模拟器设备编排：启动 N 台独立设备，供跨设备联调使用。
#
# 用法：
#   scripts/emu-up.sh 2          # 启动 2 台（A=5554, B=5556）
#   scripts/emu-up.sh 3          # 启动 3 台
#   scripts/emu-down.sh          # 全部关闭
#
# 关键约束（都是踩坑后总结的，改动前请读）：
#   1. 必须用独立 AVD。同一 AVD 的多个实例共享 ANDROID_ID，
#      而 dSIM 用 ANDROID_ID 作 deviceId，会把对端消息误判为自身回声丢弃。
#   2. 必须带 `-feature -Vulkan`。headless 下不禁用会卡在 lavapipe 软件 Vulkan
#      初始化，表现为进程活着但 5554/5555 端口永不监听。
#   3. 必须作为"受管后台任务"启动（本脚本逐个 nohup 是为了人工使用；
#      在 Agent 环境里应由宿主以受管后台任务方式拉起，否则命令结束即被回收）。
#   4. 不要传 `-netsim-args "-i N"`。实测无效，且会导致蓝牙仿真不可用
#      （模拟器的 netsim 客户端端口不由 -i 决定，-i 只移动服务端）。
#   5. 不要传 `-dns-server`。显式指定公共 DNS 会让 guest 完全无法解析；
#      留空走自动探测（宿主实际用的是 Docker/WSL 适配器 172.18.0.2）。
#
# 已知限制：
#   - 官方 AVD 仅单卡（-help-all 无任何多卡开关），单机多卡场景只能靠多设备覆盖。
#   - 需要宿主**出站 IPv6 可用**，否则模拟器的 modem 字符设备连不上
#     （chardev 硬编码 `host=::1,ipv6`），表现为 SIM 挂不上、短信注不进去。
#     自检：python -c "import socket;s=socket.socket(socket.AF_INET6);s.bind(('::1',0));\
#       c=socket.socket(socket.AF_INET6);c.connect(('::1',s.getsockname()[1]))"
#     若报 WinError 10013 即被拦。本机曾因 sing-box 的 TUN 模式触发，关闭 TUN 后恢复。
#     宾客侧判定：adb shell "dumpsys isub | grep -E 'Active subscriptions|simSlotIndex'"
#     —— simSlotIndex=-1 就是没挂上。
#
# 已验证可用的 AVD：dSIM_B、dSIM_C（新建的）。旧 AVD `Medium_Phone_API_36.1`
# 网络栈异常（无 wlan0），不要用。新建方式：
#   avdmanager create avd -n dSIM_X \
#     -k "system-images;android-36.1;google_apis_playstore;x86_64" -d medium_phone --force

set -u

# Windows 控制台默认代码页是 GBK，bash 回显的中文会被解码成乱码。
# 切到 UTF-8；对 AI 读取脚本输出来说是必需的。
chcp.com 65001 >/dev/null 2>&1 || true

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}}"
EMULATOR="$SDK/emulator/emulator.exe"
ADB="$SDK/platform-tools/adb.exe"

AVDS=(dSIM_B dSIM_C dSIM_D dSIM_E)
PORTS=(5554 5556 5558 5560)
PHONES=(13800138001 13800138002 13800138003 13800138004)

COUNT="${1:-2}"

if [ ! -x "$EMULATOR" ]; then
  echo "找不到模拟器：$EMULATOR" >&2
  exit 1
fi

if [ "$COUNT" -gt "${#AVDS[@]}" ]; then
  echo "最多支持 ${#AVDS[@]} 台，需要更多请先创建对应 AVD。" >&2
  exit 1
fi

echo "启动 $COUNT 台设备…"
for ((i = 0; i < COUNT; i++)); do
  avd="${AVDS[$i]}"
  port="${PORTS[$i]}"
  phone="${PHONES[$i]}"

  if [ ! -d "$HOME/.android/avd/$avd.avd" ]; then
    echo "  [跳过] AVD 不存在：$avd" >&2
    continue
  fi

  # 清理上次异常退出留下的锁，否则会报 multi-instance 错误
  rm -f "$HOME/.android/avd/$avd.avd/"*.lock 2>/dev/null

  echo "  $avd  port=$port  number=$phone"
  nohup "$EMULATOR" -avd "$avd" \
    -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -feature -Vulkan \
    -port "$port" -phone-number "$phone" \
    > "/tmp/emu-$avd.log" 2>&1 &
done

echo
echo "等待引导完成（最多 5 分钟）…"
for ((i = 0; i < COUNT; i++)); do
  serial="emulator-${PORTS[$i]}"
  for _ in $(seq 1 60); do
    if [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" = "1" ]; then
      echo "  ✅ $serial 就绪"
      break
    fi
    sleep 5
  done
done

echo
echo "当前设备："
"$ADB" devices
echo
echo "提示：装包后必须 `am start` 一次，否则 Android 16 的 stopped 应用收不到广播。"

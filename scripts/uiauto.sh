#!/usr/bin/env bash
# uiauto.sh — 基于 adb + uiautomator 的极简 Android UI 驱动辅助。
#
# 用法：
#   scripts/uiauto.sh dump       <serial>                    # 打印当前界面的文本元素与中心坐标
#   scripts/uiauto.sh tap        <serial> "文本"             # 按文本精确点击
#   scripts/uiauto.sh tapid      <serial> "resource-id 片段" # 按 resource-id 片段点击
#   scripts/uiauto.sh type       <serial> "文本"             # 向当前焦点输入（仅 ASCII）
#   scripts/uiauto.sh wait       <serial> "文本" [超时秒]    # 等到文本出现
#   scripts/uiauto.sh shot       <serial> <输出 png>         # 截图
#   scripts/uiauto.sh activities <serial>                    # 当前顶层 Activity
#   scripts/uiauto.sh wake       <serial>                    # 唤醒屏幕并解锁
#
# 踩过的坑（改动前务必读）：
#
# 1. **MSYS 路径改写必须关掉**。从 Bash 工具以 `bash scripts/uiauto.sh` 运行时，MSYS 会把传给
#    adb 的**设备侧路径** `/sdcard/_ui.xml` 改写成 `C:/Program Files/Git/sdcard/_ui.xml`，
#    adb 于是报 No such file or directory，dump 结果变成 68 字节的错误文本（极难察觉）。
#    两个变量必须同时设置，只设一个不够。宿主流会为**直接**子进程注入这两个变量，
#    但嵌套 bash 里不继承，所以必须在这里显式设。
#
# 2. **关掉改写后，POSIX 路径不能再喂给原生 CPython** —— 会被解析成 `C:\c\Users\...`。
#    所以：shell 重定向用 `$WORK`（POSIX），传给 Python 的一律用 cygpath 转出的 `$WORK_WIN`。
#
# 3. `input text` **不支持中文**，只接受 ASCII。中文文案只能用 tap/wait 匹配。
#    而且**空格会被当参数分隔符**——传 "Hello World" 只会输入 "Hello"，必须转义成 `Hello%sworld`
#    （`type` 命令已自动处理）。
#
# 4. `uiautomator dump` 偶尔返回空树（0 个 node）。先 wake 再 dump；
#    若仍为空，相隔几秒重试。本机实测有该现象，不要误判成"界面没有元素"。
#
# 5. **软键盘会遮挡下半屏，必须先收起再点。** 本机实测：键盘弹起时 `mInputShown=true`，
#    此时点击 y>1400 的按钮会落到键盘按键上——表面上"点了没反应"，实际是把字符打进了
#    当前聚焦的输入框（我曾因此把密码越打越长）。tap/tapid 现在会自动先收键盘。

set -u

# Windows 控制台默认代码页是 GBK，bash 回显的中文会被解码成乱码。
# 切到 UTF-8；对 AI 读取脚本输出来说是必需的。
chcp.com 65001 >/dev/null 2>&1 || true

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

# 控制台默认是 GBK，打印界面里的非 GBK 字符（如密码框的掩码 •）会让 Python 直接崩掉，
# 表现为"元素树打印到一半就中断"。强制 UTF-8 输出。
export PYTHONIOENCODING=utf-8

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}}"
ADB="$SDK/platform-tools/adb.exe"
PY="${UIAUTO_PYTHON:-C:/Users/admin/.workbuddy/binaries/python/versions/3.13.12/python.exe}"
WORK="${UIAUTO_WORK_DIR:-$(pwd)/tmp_uiauto}"
mkdir -p "$WORK"
WORK_WIN="$(cygpath -w "$WORK" 2>/dev/null || echo "$WORK")"

xml_posix() { printf '%s/ui_%s.xml' "$WORK" "$1"; }
xml_win()   { printf '%s/ui_%s.xml' "$WORK_WIN" "$1"; }

dump_xml() {
  local serial="$1" out="$2"
  "$ADB" -s "$serial" shell uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1
  "$ADB" -s "$serial" exec-out cat /sdcard/_ui.xml > "$out" 2>/dev/null
}

# 软键盘弹起时会遮住下半屏，点击会落到键盘上。点击前统一收起。
hide_keyboard() {
  local serial="$1"
  if "$ADB" -s "$serial" shell dumpsys input_method 2>/dev/null | grep -q 'mInputShown=true'; then
    "$ADB" -s "$serial" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
    echo "  (已收起软键盘)"
  fi
}

# 清空当前聚焦的输入框：光标移到末尾后连按退格
clear_focused_field() {
  local serial="$1" times="${2:-40}"
  "$ADB" -s "$serial" shell input keyevent KEYCODE_MOVE_END >/dev/null 2>&1
  for _ in $(seq 1 "$times"); do
    "$ADB" -s "$serial" shell input keyevent KEYCODE_DEL >/dev/null 2>&1
  done
}

# 按文本或 rid 片段找中心坐标，输出 "x y"；未找到以非 0 退出
find_center() {
  local xmlw="$1" needle="$2" mode="$3"
  "$PY" - "$xmlw" "$needle" "$mode" <<'PYEOF'
import re, sys
xml, needle, mode = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    t = open(xml, encoding='utf-8', errors='replace').read()
except Exception:
    sys.exit(1)
for m in re.finditer(r'<node\b[^>]*>', t):
    tag = m.group(0)
    def attr(n):
        a = re.search(r'%s="([^"]*)"' % n, tag)
        return a.group(1) if a else ''
    text, rid, bounds = attr('text'), attr('resource-id'), attr('bounds')
    if not bounds:
        continue
    if mode == 'text' and text.strip() != needle:
        continue
    if mode == 'rid' and needle not in rid:
        continue
    b = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    print('%d %d' % ((x1 + x2) // 2, (y1 + y2) // 2))
    sys.exit(0)
sys.exit(1)
PYEOF
}

print_nodes() {
  "$PY" - "$1" <<'PYEOF'
import re, sys
t = open(sys.argv[1], encoding='utf-8', errors='replace').read()
print('元素数:', t.count('<node'))
for m in re.finditer(r'<node\b[^>]*>', t):
    tag = m.group(0)
    def attr(n):
        a = re.search(r'%s="([^"]*)"' % n, tag)
        return a.group(1) if a else ''
    text, rid, bounds, cls = attr('text'), attr('resource-id'), attr('bounds'), attr('class')
    if not bounds:
        continue
    b = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    if text.strip() or rid:
        print('  %-30s %-30s (%4d,%4d) %s' % (
            text[:30], rid.split('/')[-1][:30], (x1 + x2) // 2, (y1 + y2) // 2, cls.split('.')[-1]))
PYEOF
}

cmd="${1:-}"; serial="${2:-}"

case "$cmd" in
  wake)
    "$ADB" -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    "$ADB" -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1
    echo "awake: $serial"
    ;;

  dump)
    out="$(xml_posix "$serial")"
    for _ in 1 2 3; do
      dump_xml "$serial" "$out"
      if [ "$(wc -c < "$out" 2>/dev/null || echo 0)" -gt 200 ]; then break; fi
      "$ADB" -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
      sleep 2
    done
    print_nodes "$(xml_win "$serial")"
    ;;

  tap)
    hide_keyboard "$serial"
    out="$(xml_posix "$serial")"; dump_xml "$serial" "$out"
    coords="$(find_center "$(xml_win "$serial")" "$3" text)" || { echo "未找到文本: $3" >&2; exit 1; }
    "$ADB" -s "$serial" shell input tap $coords
    echo "tapped '$3' at $coords"
    ;;

  tapid)
    hide_keyboard "$serial"
    out="$(xml_posix "$serial")"; dump_xml "$serial" "$out"
    coords="$(find_center "$(xml_win "$serial")" "$3" rid)" || { echo "未找到 resource-id 含: $3" >&2; exit 1; }
    "$ADB" -s "$serial" shell input tap $coords
    echo "tapped id~'$3' at $coords"
    ;;

  type)
    # ⚠️ `input text` 把空格当参数分隔符，直接传 "Hello World" 只会输入 "Hello"。
    # 必须把空格转义成 %s（adb input 的约定）。本机实测确认。
    escaped="${3// /%s}"
    "$ADB" -s "$serial" shell input text "$escaped"
    echo "typed: $3"
    ;;

  clearfield)
    clear_focused_field "$serial" "${3:-40}"
    echo "cleared focused field"
    ;;

  wait)
    needle="$3"; limit="${4:-20}"; i=0
    while [ "$i" -lt "$limit" ]; do
      out="$(xml_posix "$serial")"; dump_xml "$serial" "$out"
      if find_center "$(xml_win "$serial")" "$needle" text >/dev/null 2>&1; then
        echo "found: $needle (${i}s)"; exit 0
      fi
      sleep 2; i=$((i + 2))
    done
    echo "超时未出现: $needle" >&2; exit 1
    ;;

  shot)
    "$ADB" -s "$serial" exec-out screencap -p > "$3"
    echo "saved: $3"
    ;;

  activities)
    "$ADB" -s "$serial" shell dumpsys activity activities 2>/dev/null \
      | grep -oE 'com\.example\.dsim/\.[A-Za-z]+' | head -5
    ;;

  *)
    sed -n '2,30p' "$0"
    exit 1
    ;;
esac

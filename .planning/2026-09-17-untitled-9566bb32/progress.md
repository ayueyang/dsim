# Progress Log

## Session: 2026-09-17

### Current Status
- **Phase:** 4 双开与多设备（in_progress）；Phase 2 modem 修复 **阻塞于用户动作**
- **Started:** 2026-09-17 21:45

### Actions Taken

**Phase 1（complete）**

1. 初始化规划会话，`PLAN_ID=2026-09-17-untitled-9566bb32`。
2. 环境勘察：SDK emulator 36.4.10，AVD `Medium_Phone_API_36.1`（Android 16 / SDK 36 / x86_64 / google_apis_playstore）；
   WSL2 三发行版；Docker 29.2.1。
3. 虚拟化边界实测：WSL2 内 `/dev/kvm` 不存在、`vmx/svm` 计数 0 → **无嵌套虚拟化** → Cuttlefish 出局。
4. 确认模拟器可无头启动（关键参数 `-feature -Vulkan`），adb 装包/授权/设默认短信角色均可用。
5. 判定官方 AVD 多卡能力：`-help-all` 无任何多卡开关（仅 `-no-sim`）→ **单卡**。

**Phase 2（blocked）**

6. 先误判为 netsimd 绑 6402 失败（Windows 保留段 6380–6679），并算出端口公式 `6401 + max(1, instance)`，
   实测 instance=300 可成功监听 6701。**随后证明该判断错误**：6402 是蓝牙 HCI socket，与 modem 无关；
   `-netsim-args "-i 300"` 不仅无效，还导致蓝牙能力丢失。已回退。
7. 用 `-verbose` 捕获 qemu 真实 chardev：`socket,port=11649,host=::1,...,ipv6,id=modem`。
8. **TCP 层实测确定真正根因：本机所有出站 IPv6 连接被 WFP 拒绝（WSAEACCES 10013）** ——
   `::1` 与本机全局 IPv6 地址都失败，`127.0.0.1` 正常；`ping -6 ::1` 亦 100% 丢失，
   但 `netsh` 显示 ::1 地址存在且为首选。最可能来源是 `singbox_tun` 代理网卡（TUN + strict_route，
   该接口只有 link-local、无全局 IPv6）。
9. 结论：**修复只能由用户操作**（停用 sing-box / 关 TUN 或 strict_route / 为其启用 IPv6）。
   模拟器侧无任何可配开关（`emulator.exe` 仅 1.9 MB 启动器，`::1`/`ipv6` 只存在于 qemu 二进制内）。

**Phase 4（in_progress）**

10. 用**同一已装镜像**另建第二个 AVD `dSIM_B`（API 35 镜像下载损坏，改用此法，无需额外下载）。
11. 双开验证成功：`emulator-5554` → `ANDROID_ID=183097cc721be65e`，`emulator-5556` → `ce47bc8957cbc56e`，
    **互异** ✓。B 上装包/授权/设默认短信角色/broker TCP 连通全部成功。
12. DNS 试验：显式传公共 DNS（223.5.5.5 等）会让**两台都失败**；宿主实际 DNS 是 `172.18.0.2`
    （Docker/WSL 适配器，`nslookup` 可见），显式传它亦不佳；**自动探测最好**（B 曾成功）。
13. **发现新问题**：同时启动两实例时，A 完全**没有 `wlan0`**（只有 lo/dummy0，无默认路由），
    B 正常（`wlan0` = `10.0.2.16`）。怀疑是两实例**同时启动**导致 netsimd/wifi 初始化竞争。
    正在验证：保持 B 运行，单独重启 A。

### Test Results

| 测试 | 结果 |
|---|---|
| 无头启动（含 `-feature -Vulkan`） | ✅ 引导 30–49 秒 |
| 无头启动（不含该参数） | ❌ 进程活、5554/5555 不监听 |
| `pm grant` 8 项权限 | ✅ 全部成功 |
| `cmd role add-role-holder android.app.role.SMS` | ✅ `get-role-holders` 返回 com.example.dsim |
| `screencap` / `uiautomator dump` | ✅ 1080×2400 PNG / 完整元素树 |
| `netsimd -i 300` 独立运行 | ✅ 监听 6701（但与本问题无关） |
| **双开（两 AVD 同时运行）** | ✅ 两实例均被 adb 识别，`ANDROID_ID` 互异 |
| 设备 B broker TCP 连通 | ✅ `nc 35.172.255.228:1883` → OK |
| 设备 A broker TCP 连通 | ❌ `Network is unreachable`（无 `wlan0`） |
| 本机出站 IPv6（`::1` / 全局） | ❌ WSAEACCES 10013 |
| 本机出站 IPv4（`127.0.0.1`） | ✅ 正常 |
| `adb emu sms send` | ❌ 返回 OK 但短信未进入 framework（modem 缺失） |

### Files Created/Modified
- `.planning/2026-09-17-untitled-9566bb32/task_plan.md`（Phase 1 complete，Phase 2 blocked）
- `.planning/2026-09-17-untitled-9566bb32/findings.md`（F1 已更正、新增 F11–F13）
- `.planning/2026-09-17-untitled-9566bb32/progress.md`（本文件）
- `~/.workbuddy/MEMORY.md`（**修正**了此前关于 6402 的错误结论，补充 IPv6 根因、DNS、双开方法）
- `~/.android/avd/dSIM_B.avd` + `dSIM_B.ini`（新建第二个 AVD）
- 删除：`<SDK>/system-images/android-35`（下载损坏的残留）
- `.workbuddy/memory/2026-09-17.md`（追加第四轮调研结论）

### Error Log

| 错误 | 尝试次数 | 处置 |
|---|---|---|
| emulator 进程活但端口不监听 | 1 | `-feature -Vulkan` ✅ |
| `nohup &` 启动后进程消失 | 1 | 改用受管后台任务 ✅ |
| 新装应用收不到广播 | 1 | 先 `am start` 一次 ✅ |
| netsimd 绑 6402 得 10013 | 1 | 定性为蓝牙 HCI，**与本问题无关**（曾误判为根因） |
| modem 字符设备连接失败 | 4 | ①② 无效且有害 ③ `-verbose` 取到 chardev ④ **IPTCP 层实测锁定出站 IPv6 被拦** |
| PowerShell 工具输出为空 | 2 | 改用 `-verbose`；后续避免依赖 |
| `sdkmanager` 装 API 35 镜像 `Error reading Zip content` | 1 | 改为复用已装镜像建第二 AVD |
| 显式 DNS 导致两台都解析失败 | 1 | 回退为自动探测 |
| 双开时 A 无 `wlan0` | 1 | 疑为同时启动竞争；改为单独重启 A 验证 |

### Next Steps
1. 确认 A 单独重启后 `wlan0` 是否出现 → 若是，则规定"**错峰启动**"为多设备标准流程。
2. 若 A 仍无网 → 检查两实例的 netsim 端口冲突，必要时为每个实例分配独立 netsim 参数。
3. 将可用参数与启动顺序固化为脚本（Phase 5）。
4. 向用户交付 Phase 2 的修复指引（停用 sing-box TUN）。


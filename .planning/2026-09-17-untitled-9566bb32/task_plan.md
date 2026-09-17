# Task Plan: 模拟器多设备测试环境搭建

## Goal
在这台 Windows 机器上建立一套可重复、可由 AI 独立驱动的 dSIM 测试环境：单机能双开（≥2 个独立设备）、每台设备有可用的模拟 SIM 卡、短信收发链路打通，并能扩展到 3 台以上设备同时联调。

## Next Step
全部完成。Goal 已达成：双开、每台设备有可用模拟 SIM、短信收发链路打通、跨设备发现与跨设备发信均实测通过、
可扩展到 N 台设备（`emu-up.sh N`）。唯一残留是 §5.1 那个绕法不持久化（`isMockNoRootMode` 是内存开关）。

## Current Phase
全部 Phase complete

## Phases

### Phase 1: 能力边界与阻塞定位
- [x] 确认官方 AVD 可无头启动（`-feature -Vulkan` 必需）
- [x] 确认 adb 装包/授权/设默认短信角色可用
- [x] 定位短信不通根因（**出站 IPv6 被拦截** → modem chardev `host=::1` 连不上 → SIM `simSlotIndex=-1`）
- [x] 判定虚拟化边界：WSL2 无嵌套虚拟化 → Cuttlefish / redroid 出局
- [x] 判定官方 AVD 多卡能力：`-help-all` 无任何多卡开关，仅单卡
- **Status:** complete

### Phase 2: modem 修复
- [x] 排除端口保留段假设（`-i 300` 实测失败，且 6402 实为蓝牙 HCI socket）
- [x] 从 `-verbose` 日志捕获 qemu 真实 chardev：`socket,port=11649,host=::1,...,ipv6,id=modem`
- [x] 用 TCP 层实测证明本机**所有出站 IPv6 连接均被拦截**（`::1` 与全局 IPv6 地址都返回 WSAEACCES 10013）
- [x] **【用户动作】已执行**：关闭 sing-box TUN 模式
- [x] 复测：`mSimState[0]=LOADED`、`simSlotIndex=0`、chardev 错误归零
- **Status:** complete

### Phase 3: 短信链路打通
- [x] 端到端验证：`adb emu sms send` → `SmsReceiver` 捕获 → Room 落库
      （id=1, from=10086, body 完整, type=1, status=1, simId=1, mappingKey 走 `_UNBOUND` 兜底）
- [x] 验证通知：`dsim_loud_v1` 渠道、importance=4、震动模式正确、带 1 个动作
- [x] 验证非默认短信应用分支：移除默认短信角色后由 `SMS_RECEIVED` 捕获
      → **双接收器动作互斥规则实证通过**
- [x] **验证跨设备去重**：A 发信产生的 uuid 在两端各只有 1 条记录 ——
      B 把发出的短信广播到云端、A 收到后由 `checkUuidExists` 正确拦截，未产生重复
- **Status:** complete

### Phase 4: 双开与多设备
- [x] 创建第二个独立 AVD（`dSIM_B` / `dSIM_C`，同镜像、独立 userdata → 独立 `ANDROID_ID`）
- [x] 验证两实例同时运行、`deviceId` 互异
- [x] 验证设备有完整网络（`wlan0` = 10.0.2.16）且能连通 broker TCP 1883
- [x] 验证单台设备 SIM 已挂载、短信链路可用
- [x] **跨设备发现验证通过**：两端 `device_profiles` 互见（本机 LOCAL + 对端 REMOTE）
- [x] PING/PONG 快照每 20 秒稳定广播，含 SIM 信息
- [x] **SEND_CMD 端到端验证通过**（绕法：让 B 切无 Root 模式，两端 mappingKey 不再碰撞）
      —— A 发 `SEND_CMD` → B 用自己 SIM 实际发出并复用同一 uuid → 回 `SEND_CMD_RESULT`
      → A 的 status 由 0 更新为 1；**两端 uuid 完全一致**（C10 约束实测通过）
- [x] 固化启动/关闭脚本：`scripts/emu-up.sh`、`scripts/emu-down.sh`
- **Status:** complete

### Phase 5: 自动化封装与交付
- [x] `scripts/emu-up.sh` / `emu-down.sh` —— 启停 N 台设备
- [x] `scripts/uiauto.sh` —— adb+uiautomator UI 驱动（dump/tap/tapid/type/wait/shot/activities/wake/clearfield）
- [x] `scripts/onboard-device.sh` —— 装包+授权+设默认短信角色+走完 6 步引导（已在设备 B 上实测跑通）
- [x] `scripts/pull-app-db.sh` —— 拉取应用库（自动带 `-wal`/`-shm`）
- [x] `scripts/verify-dsim.sh` —— 回归验证 **12/12 PASS，退出码 0**，输出 ASCII 化以规避编码问题
- [x] `TESTING.md` —— 前置自检、启动参数、引导、短信链路、多设备、已知限制、未覆盖面
- [x] 更新 `AGENTS.md`：新增 §8 调试与测试入口，重写 §6 待办（10 项，含本轮新发现）
- **Status:** complete

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 用官方 AVD 而非 Cuttlefish/redroid 作为主力 | WSL2 内 `/dev/kvm` 不存在、`vmx/svm` 计数为 0 → 无嵌套虚拟化；Cuttlefish 强依赖 KVM。redroid/Waydroid 的 telephony/RIL 通常不可用，而本 App 的核心依赖正是 RIL |
| 交叉设备测试必须建独立 AVD，不用 `-read-only` | `-read-only` 双实例共享同一 `ANDROID_ID` → `deviceId` 相同 → 应用把对端消息当自身回声丢弃 |
| 第二台设备用**同一已装镜像**另建 AVD，而非下载新镜像 | 各 AVD 有独立 userdata → 独立 `ANDROID_ID`，已满足多设备前提；API 35 镜像下载损坏（`Error reading Zip content`），无需为此付时间成本 |
| 放弃 `-netsim-args "-i 300"` | 实测无效且副作用：模拟器的 netsim 客户端端口不由 `-i` 决定，加该参数后蓝牙能力丢失 |
| 不采用 `netsh` 改端口保留段 | 需管理员权限且会短暂中断 Docker/WSL 网络；且事后证明 6402 与 modem 无关，改它无收益 |

## Errors Encountered
| Error | Resolution |
|-------|------------|
| emulator 进程活着但 5554/5555 从不监听 | headless 下必须 `-feature -Vulkan`，否则卡在 lavapipe 软件 Vulkan 初始化 |
| `nohup emulator &` 启动后进程自行消失 | 必须用受管后台任务（`run_in_background`），否则命令结束时进程树被回收 |
| `adb emu sms send` 返回 OK 但应用无日志 | 首次误判为注入失败；实为 Android 16 新装应用 stopped 状态不接收广播，需先 `am start` 一次 |
| `dumpsys isub` 显示 `simSlotIndex=-1`、`Active subscriptions` 为空 | **根因：出站 IPv6 被拦截**。详见 findings F11 |
| 重复启动同一 AVD 报 "Running multiple emulators with the same AVD" | 前次实例仍在运行；用 `adb -s emulator-5554 emu kill` 干净关闭并清 `*.lock` |
| PowerShell 工具在本会话返回空输出（连 `Write-Output` 都空） | 改用模拟器自身 `-verbose` 输出；后续避免依赖该工具 |
| `sdkmanager` 装 API 35 镜像报 `Error reading Zip content from a SeekableByteChannel` | 下载损坏；改为复用已装镜像另建 AVD，绕开该问题 |
| 误判 6402 端口保留段为 modem 根因 | 6402 实为 `transport/socket.rs` 的**蓝牙 HCI** socket；已修正，并在 findings F8 记录 |


# Findings & Decisions

## Requirements
- 模拟器要能【双开】（≥2 个独立设备）
- 要能【模拟 SIM 卡】
- 后期要【好几台设备一起测试】（3+ 台联调）
- 测试必须可由 AI 独立完成，不依赖人工操作
- 允许联网调研，必要时可自行"魔改"模拟器

## Research Findings

### F1. netsimd 端口公式（★ 已确认，但结论已被 F11 推翻：与本问题无关）

> **重要更正**：该端口属于 `transport/socket.rs` 的 **HCI socket server，服务于蓝牙（rootcanal）**，
> **不是 modem**。取 6402 失败的后果是蓝牙仿真不可用，并不影响 modem。
> 本节的测量仍然有效（公式正确），但**不能当作 modem 问题的解法**。
> 实际验证：`-netsim-args "-i 300"` 启动后 modem 依旧失败，且**蓝牙能力丢失**（新出现
> `WARNING | Unable to connect to packet streamer, no bluetooth emulation available`）
> —— 因为模拟器的 netsim 客户端端口不由 `-i` 决定，`-i` 只移动了服务端。
> **结论：不要使用 `-netsim-args "-i ..."`。**

`netsimd` 的 HCI socket server 端口 = **`6401 + max(1, instance)`**，可用 `-i/--instance` 指定。

实测（`netsimd.exe -l -i N --no-cli-ui --no-web-ui`）：

| instance | socket 端口 | bind 结果 |
|---|---|---|
| 0 | 6402 | ❌ 10013 权限拒绝 |
| 1 | 6402 | ❌ 10013 |
| 2 | 6403 | ❌ 10013 |
| 50 | 6451 | ❌ 10013 |
| 200 | 6601 | ❌ 10013 |
| **300** | **6701** | ✅ `Hci socket server is listening on: 6701` |
| 320 | 6721 | ✅ 监听 |

gRPC 端口也随之递增（10682 → 10860），但始终落在非保留区。

### F2. 为什么 instance=300 能成

`netsh int ipv4 show excludedportrange protocol=tcp` 的保留段：

```
5357
6380 - 6479     ← instance 1..78
6480 - 6579     ← instance 79..178
6580 - 6679     ← instance 179..278
50000 - 50059   (* 托管排除)
59100           (* 托管排除)
```

保留段上界 **6679**，对应 instance 278。取 **instance ≥ 279** 即避开；**300** 留有余量（6701）。

**这是最优解：不需要管理员权限、不改 Windows 保留段、不影响 Docker/WSL 网络。**
仅需通过 emulator 的 `-netsim-args` 把 `-i 300` 透传给 netsimd。

### F3. 端口保留段来源

6380–6679 是 **Windows 托管排除（managed exclusion）**（`netsh` 输出标 `*`），由 Hyper-V / HNS
创建（宿主可见 `Hyper-V Virtual Ethernet Adapter`，Docker 29.2.1 在运行）。
绑定这类端口得到的是 **WSAEACCES (10013)** 而非"地址已占用"——这是最初难以定位的原因。

### F4. modem 未挂载的完整因果链

```
netsimd 绑 6402 失败（10013）
  → netsimd 的 HCI socket server 不可用
  → qemu 连不上 modem 字符设备：
    "Unable to connect character device modem: Failed to connect socket: I/O error"
  → guest 侧 modem 为空
  → dumpsys isub: simSlotIndex=-1, mSimState[0]=UNKNOWN, "Active subscriptions:" 为空,
                  areAllSubscriptionsLoaded=false
  → adb emu sms send 返回 OK，但短信从未进入 framework，应用零日志
```

关键判据：**`dumpsys isub` 里 `simSlotIndex=-1` 即 SIM 未挂上**。
此时无论怎么注入短信都是徒劳，不要浪费时间去查应用侧。

### F5. 虚拟化能力边界（决定技术选型）

| 项 | 实测 | 影响 |
|---|---|---|
| WSL2 发行版 | Ubuntu-24.04 / docker-desktop / Debian-12，均 v2 | 可用 |
| WSL2 内 `/dev/kvm` | **不存在** | — |
| WSL2 内 `vmx/svm` 计数 | **0** | **无嵌套虚拟化** |
| WSL2 内核 | 6.18.33.2-microsoft-standard-WSL2 | — |
| Docker | 29.2.1 可用 | — |
| 宿主 | 有 Hyper-V Virtual Ethernet Adapter；模拟器报告 `hasCompatibleHypervisor: Ok` | Windows 侧硬件加速可用 |

**结论：Cuttlefish 不可行。** 强依赖 KVM，而 WSL2 无嵌套虚拟化；官方亦仅支持 Linux x86/arm64 宿主。
启用嵌套虚拟化需改 `.wslconfig` 的 `nestedVirtualization=true` 并重启 WSL，且不保证该 Windows 版本支持。
redroid / Waydroid 同类问题，且其 telephony/RIL 通常不可用——而本 App 的核心依赖正是 RIL。

### F6. 官方 AVD 的多卡能力（待权威确认）

`emulator -help-all` 中**无任何多 SIM 开关**，只有 `-no-sim`（禁用 SIM）。
相关选项仅 `-phone-number`（单号码）、`-icc-profile <file>`（ICC 配置文件）、`-sim-access-rules-file`。

初步判断：**官方 AVD 单设备仅 1 张卡**。多卡需靠多设备覆盖，或深挖 `-icc-profile` 能力。
→ 待办：确认 `-icc-profile` 期望的文件格式，判断能否定义多张 ICC。

### F7. 为什么不用 `-read-only` 双开同一 AVD

`-read-only` 允许同一 AVD 跑多实例，但两实例共享同一 `ANDROID_ID`
→ `HardwareProbeUtils.getDeviceId` 返回相同值
→ `MqttSyncService.handleIncomingMessage` 第 ⑫ 步 `sms.deviceId == localDeviceId → return`
会把对端消息当成自身回声丢弃。**交叉设备测试必须建独立 AVD。**

### F8. 已被证伪的绕法（不要重复尝试）

| 尝试 | 结果 |
|---|---|
| `-modem-simulator-port 7000` | 无效。该参数控制 qemu↔modem 链路端口，不是 netsimd 的 socket server 端口 |
| `-phone-number` 单独使用 | 不解决 modem 缺失 |
| 换 `-port 5580` 等 | 无效。模拟器端口上限 5584，推算 netsimd 端口仍落在 6380–6679 内 |
| 在 AVD `config.ini` 里找 sim/modem 键 | 不存在此类键 |
| `netsh` 改保留段 / `net stop winnat` | **未采用**：需管理员权限且会短暂中断 Docker/WSL 网络，而 `-i 300` 已绕过，无需付此代价 |

### F9. 已验证可用的模拟器启动参数

```bash
SDK="/c/Users/admin/AppData/Local/Android/Sdk"
cd "$SDK/emulator" && ./emulator.exe -avd <AVD 名> \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -feature -Vulkan \
  -port <5554+2N> -phone-number <号码> \
  -netsim-args "-i <300+N>"
```

- `-feature -Vulkan`：headless 必需，否则卡在 lavapipe 软件 Vulkan 初始化（进程活、端口不开）
- `-port`：每实例不同（5554 / 5556 / 5558 …）
- `-netsim-args "-i <N>"`：**每实例必须唯一**，建议 300 / 301 / 302 递增
- **必须用受管后台任务启动**，`nohup ... &` 会在命令结束时被回收

### F10. AI 驱动界面测试的已验证能力

```bash
adb -s emulator-5554 exec-out screencap -p > shot.png        # 1080x2400 PNG，headless 下正常
adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml   # 元素树，据此换算坐标
adb -s emulator-5554 shell input tap X Y
adb -s emulator-5554 shell input text "abc"
adb -s emulator-5554 emu kill                                # 干净关闭
```

### F11. ★ modem 失败的真正根因：本机所有出站 IPv6 连接被拦截

从 `-verbose` 日志抓到的 qemu 真实 chardev 配置：

```
-chardev socket,port=11649,host=::1,nowait,nodelay,reconnect=10,ipv6,id=modem
DEBUG | started modem simulator host server at port: 11652
```

**modem 的字符设备走的是 IPv6 回环 `::1`**（`ipv6` 标志 + `host=::1`）。

本机 TCP 层实测（Python，`bind` + `connect` 自连）：

| 目标 | 结果 |
|---|---|
| `::1`（IPv6 回环） | ❌ `PermissionError [WinError 10013]` |
| 本机全局 IPv6 地址 `2409:8a5c:...:488f` | ❌ `PermissionError [WinError 10013]` |
| `127.0.0.1`（IPv4 回环） | ✅ 正常 |

`ping -6 ::1` 亦为「一般故障，100% 丢失」。

**结论：不是 ::1 地址缺失（`netsh interface ipv6 show address` 显示 ::1 存在且为首选），
而是出站 IPv6 连接被 WFP 过滤器以 WSAEACCES 拒绝。**

最可能的来源：**`singbox_tun` 代理网卡**（`netsh interface show interface` 可见，且该接口只有
link-local `fe80::` 地址、无全局 IPv6）。TUN 模式 + strict_route 会安装 WFP 过滤器强制流量入隧道，
而隧道无 IPv6 路由 → 直接拒绝。机器上另有 `http_proxy=127.0.0.1:9851` 的代理痕迹，与此吻合。

**修复方向（需用户操作）**：临时停用 sing-box / 关闭其 TUN 或 strict_route / 为其启用 IPv6。
这是环境层问题，模拟器侧没有任何可配置点——`emulator.exe` 仅 1.9 MB（启动器），
`::1` 与 `ipv6` 只出现在 `qemu-system-*.exe` 里，且 chardev 参数由内部生成，无外部开关。

### F12. DNS：必须显式指定 IPv4 解析器

未指定时，模拟器自动探测宿主 DNS。实测两实例表现不一致：
- `emulator-5556`：DNS 正常，`nc broker.emqx.io 1883` → `NC_CONNECT_OK`
- `emulator-5554`：`ping: unknown host broker.emqx.io`（DNS 解析失败）

结合 F11，高度怀疑自动探测选中了**IPv6 DNS 服务器**（被拦截后解析必然失败）。
模拟器提供 `-dns-server <servers>` 可显式指定。

**采用**：`-dns-server 223.5.5.5,119.29.29.29`（阿里 DNS + DNSPod，均为 IPv4）。

另注：验证 Android guest 联网**不能用 `/dev/tcp/...`**（那是 bash 特性，Android 的 mksh 不支持，
会报 `can't create ... No such file or directory`，极易误判为网络故障）。
正确方法：`toybox nc -w 3 <host> <port> </dev/null` 或 `ping -c 2 <host>`。

### F13. 双开已验证成功（关键交付）

| 实例 | AVD | 端口 | ANDROID_ID | `-phone-number` |
|---|---|---|---|---|
| A | `Medium_Phone_API_36.1` | 5554 | `183097cc721be65e` | 13800138001 |
| B | `dSIM_B`（同镜像另建） | 5556 | `ce47bc8957cbc56e` | 13800138002 |

两个 `ANDROID_ID` **互异** → 应用会识别为两台不同设备，满足跨设备联调前提。
B 上已验证：装包成功、8 项权限授权成功、默认短信角色设置成功、broker 1883 端口连通。

### F14. 旧 AVD 网络栈异常，新建 AVD 正常（已对比确认）

| AVD | 来源 | `hw.gsmModem` | 宾客 `wlan0` | 到 broker TCP |
|---|---|---|---|---|
| `Medium_Phone_API_36.1` | 项目中既有的旧 AVD | **缺失** | ❌ 无（只有 lo/dummy0，无默认路由） | ❌ `Network is unreachable` |
| `dSIM_B` | 本次新建 | `yes` | ✅ `10.0.2.16/24` + 默认路由 | ✅ OK |
| `dSIM_C` | 本次新建 | `yes` | （未启，配置一致） | — |

排查过程：
1. 先怀疑是"两实例同时启动导致 netsimd 竞争"→ **证伪**：保持 B 运行、单独重启 A，A 仍无 `wlan0`。
2. 对比两份 `config.ini`，发现唯一与电话/网络相关的差异是 A **缺少 `hw.gsmModem=yes`**。
3. 给 A 补上该键（已备份原文件）并重启 → **仍未修复**，A 依旧无 `wlan0`、`simSlotIndex=-1`。

**结论：A 这个 AVD 的状态已损坏，补配置无法恢复。**
处置：**弃用 `Medium_Phone_API_36.1`，多设备一律使用新建的 AVD**（`dSIM_B` / `dSIM_C` / …），
用 `avdmanager create avd -d medium_phone` 批量生成即可，成本很低（复用同一已装镜像，不额外下载）。

### F15. 已达尝试上限的项（按协议停止试错）

| 目标 | 尝试次数 | 结果 |
|---|---|---|
| 让 modem 挂上 | 4 | 未能解决；根因是宿主出站 IPv6 被拦，**只能由用户修复**（F11） |
| 让旧 AVD 恢复网络 | 3 | 未能解决；判定为 AVD 状态损坏，改走"弃用旧 AVD"（F14） |

两者均已在 task_plan 的 Decisions/Errors 中记录，后续不再重复同类尝试。

### F16. ★ IPv6 恢复后 modem 与短信链路全部打通（已验证）

用户关闭 sing-box TUN 模式后复测：

| 检查项 | 恢复前 | 恢复后 |
|---|---|---|
| 出站 `::1` TCP | ❌ WSAEACCES 10013 | ✅ 正常 |
| 出站全局 IPv6 TCP | ❌ WSAEACCES 10013 | ✅ 正常 |
| `ping -6 ::1` | 100% 丢失 | ✅ 0% 丢失 |
| `singbox_tun` 网卡 | 存在 | ✅ 已消失 |
| `mSimState[0]` | `UNKNOWN` | ✅ `LOADED` |
| `simSlotIndex` | `-1` | ✅ `0` |
| qemu modem chardev 错误 | 有 | ✅ 归零 |

端到端短信链路（`adb emu sms send 10086 "..."`）：

1. **采集**：logcat `dSIM_Receiver: Captured incoming SMS action=android.provider.Telephony.SMS_DELIVER, from=10086, subId=1, slotIndex=0, mappingKey=DEV_..._SUBID_1_SLOT_0_UNBOUND`
2. **落库**：拉出 Room 数据库（需同时取 `-wal` / `-shm`，否则只有 4 KB 空文件）验证到完整记录：
   `address=10086`、`body='【dSIM测试】验证码 246810，请勿泄露。'`、`type=1`、`status=1`、`simId=1`、
   `mappingKey=DEV_d9e0118b7d57f7c6_SUBID_1_SLOT_0_UNBOUND`、`deviceId=d9e0118b7d57f7c6`；
   4 张表 + `index_sms_messages_uuid` 唯一索引齐全（Room v5 schema 正确建立）。
3. **通知**：`dsim_loud_v1` 渠道、importance=4、震动 `[0,250,250,250]`、带 1 个动作（验证码复制）。
4. **双接收器互斥**：`cmd role remove-role-holder android.app.role.SMS` 后（角色归 Google Messages），
   同一条注入改由 `SMS_RECEIVED` 分支捕获 → **C6 约束在 Android 16 上实证通过**。

**结论：Phase 2 与 Phase 3 的主链路全部打通。**

### F17. ★ 重要发现：`ANDROID_ID` 对应用是按签名作用域隔离的

实测（同一台设备 `dSIM_B`）：

| 读取方 | 值 |
|---|---|
| 应用内 `Settings.Secure.getString(ANDROID_ID)`（即 `HardwareProbeUtils.getDeviceId`） | `d9e0118b7d57f7c6` |
| `adb shell settings get secure android_id`（设备级） | `ce47bc8957cbc56e` |

**两者不同，且都跨重启稳定**（已用重启验证设备级值不变，应用数据亦保留）。
原因是 Android 8.0 起 `ANDROID_ID` 按 **(应用签名密钥, 用户, 设备)** 三元组隔离。

#### 对 dSIM 的直接影响（此前未记录的风险）

`HardwareProbeUtils.getDeviceId()` 返回的是**按签名作用域**的值，因此：

- **debug 构建与 release 构建签名密钥不同 → `deviceId` 不同。**
  用户从调试版切到正式版（或反之），应用会把自己当成一台**全新设备**。
- 连带影响：`sms_messages.deviceId` 历史记录与新 `localDeviceId` 不再匹配；
  `DeviceProfile.isLocalDevice` 判定漂移；`dao.findPreferredLocalMappingKeyForAddress`
  （按 deviceId 过滤）查不到历史；`SmsSourceRepairManager` 的借卡修复判定受影响。
- 建议：`deviceId` 不应直接用 `ANDROID_ID`，改用**应用自管的持久 UUID**
  （首次启动生成并存入 prefs/数据库），或对 release 固定签名密钥并接受"切构建即换设备"。

> 注：该风险由 Android 官方行为 + 本机实测值差异推导，**未做 release 构建实测**
> （项目尚无签名配置，无法产出 release APK）。标记为待验证。

### F18. AVD 配置对照（供后续排查）

| 项 | 旧 AVD `Medium_Phone_API_36.1` | 新建 `dSIM_B` / `dSIM_C` |
|---|---|---|
| `hw.gsmModem` | 缺失 | `yes` |
| 宾客 `wlan0` | ❌ 无 | ✅ `10.0.2.16` |
| 现在是否可用 | 否（已弃用） | 是 |

新建 AVD 的正确方式（复用已装镜像，无需下载）：
```bash
avdmanager create avd -n dSIM_X -k "system-images;android-36.1;google_apis_playstore;x86_64" -d medium_phone --force
```

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 用 `-netsim-args "-i 300+N"` 绕过端口保留段 | 零权限、零系统改动、不影响 Docker/WSL；已实测 netsimd 成功监听 6701 |
| 主力沿用官方 AVD，不用 Cuttlefish/redroid | WSL2 无嵌套虚拟化（`/dev/kvm` 缺失、`vmx/svm`=0），Cuttlefish 强依赖 KVM；redroid/Waydroid 的 RIL 不可用而本 App 强依赖 RIL |
| 交叉设备测试必须建独立 AVD | `-read-only` 双实例共享 `ANDROID_ID`，会被应用当成自身回声丢弃 |
| 每设备 1 张 SIM，单机多卡暂不追求 | 官方 AVD 无多卡开关；多卡场景改由多设备覆盖 |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| 模拟器进程活着但 5554/5555 从不监听 | headless 必须 `-feature -Vulkan` |
| `nohup emulator &` 启动后进程消失 | 改用受管后台任务（`run_in_background`） |
| `adb emu sms send` 返回 OK 但应用无日志 | Android 16 新装应用 stopped 状态不接收广播，需先 `am start` 一次 |
| `dumpsys isub` 显示 `simSlotIndex=-1` | 根因 netsimd 绑 6402 失败 → 用 `-i 300` 解决 |
| 重复启动同一 AVD 报 multi-instance 错误 | 前次实例仍在运行；`adb emu kill` + 清 `*.lock` |

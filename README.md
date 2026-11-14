# ACEFIT NEO Companion

轻量、无广告、零网络权限的 **ACEFAST ACEFIT NEO (`FA003` / WQ7033)** 第三方 Android BLE 伴侣 App（纯 Java + Android SDK 原生直编，无第三方 SDK 依赖）。

> Unofficial open-source Android BLE companion app for **ACEFAST ACEFIT NEO (`FA003`)** open-ear earbuds. Built because newer official ACEFAST app versions dropped or broke BLE pairing/connection support for this model.

## 背景与解决的问题

新版官方 App（如 `v2.0.3`）在设备选择与扫码匹配层移除了旧款 `ACEFIT NEO` 的映射，导致蓝牙已正常配对、耳机硬件完全正常的 `ACEFAST ACEFIT NEO` 在 App 搜索界面点击后直接提示「设备连接失败」。

本项目从零实现了 `ACEFIT NEO` 的 `D0D2C5C2` BLE GATT 私有协议编解码器与极简 Android 客户端：

- **双耳实时状态监控**：左/右耳独立电量百分比、使用中/充电中状态、主从耳角色（`左耳为主` / `右耳为主`）。（注：该型号固件协议仅上报双耳电量，不上报充电仓电量。）
- **3 档 DSP 预设音效切换**：`原声` / `人声` / `重低音`，并支持读取当前生效音效。
- **8 槽位触控手势全自定义**：支持左/右耳 `单击`、`双击`、`三击`、`长按` 共 8 个独立槽位，可映射为 8 种动作并支持一键恢复出厂默认手势（`54112367`）：
  - `1` 播放 / 暂停
  - `2` 上一曲
  - `3` 下一曲
  - `4` 音量 +
  - `5` 音量 -
  - `6` 语音助手
  - `7` 游戏模式（耳机本地低延迟切换）
  - `8` 无作用
- **极简隐私设计**：仅申请 `BLUETOOTH_SCAN`（带 `neverForLocation` 标记）与 `BLUETOOTH_CONNECT` 权限（Android 11 兼容 `ACCESS_FINE_LOCATION`），**无联网权限、无统计 SDK**。
- **协议调试日志面板**：UI 底部可一键展开查看完整 BLE GATT TX/RX 十六进制报文与 CRC 校验日志。

## 协议笔记 (`D0D2C5C2` over BLE GATT)

`ACEFIT NEO (FA003)` 基于物奇（Wuqi）`WQ7033` 蓝牙音频 SoC，其 BLE 控制通道结构如下：

| 项目 | UUID | 属性 | 用途 |
| :--- | :--- | :--- | :--- |
| 主控制服务 | `00007033-0000-1000-8000-00805f9b34fb` | Service | 主通信服务 |
| 指令写入 | `00001001-0000-1000-8000-00805f9b34fb` | Write | 发送 `D0D2C5C2` 指令帧 |
| 状态通知 | `00001002-0000-1000-8000-00805f9b34fb` | Notify | 接收耳机异步回包 |
| 辅助服务 | `0131f5da-0000-1000-8000-00805f9b34fb` | Service | 辅助通知通道 |
| 辅助通知 | `0331f5da-0000-1000-8000-00805f9b34fb` | Notify | 连接时开启 CCCD 订阅 |

### 帧格式与校验

- **帧头**：`D0 D2 C5 C2`（4 字节）
- **帧长**：2 字节无符号整数（不含前 4 字节头与末尾 4 字节 CRC-32）
- **CRC-8**（位于 `offset 11`）：多项式 `0x31`（反射 `0x8C`），`init=0xFF`，`refin/refout=true`，`xorout=0xFF`，计算范围 `bytes[4..10]`
- **内层 CRC-16**（手势写入等指令）：取负载字节标准 IEEE CRC-32 的低 16 位（小端序）
- **帧尾标记**：`40 40` 后接 4 字节小端序标准 IEEE **CRC-32**（计算范围 `bytes[4 .. len-4)`）

> **局限性说明**：经实机与协议分析确认，`ACEFIT NEO` 的该版本固件不暴露游戏模式当前开关状态字（无法在 App 读取游戏模式是否开启，只能通过绑定的触控手势切换并听耳机提示音判断），也不支持 8 段自定义 EQ、查找耳机或 BLE OTA 升级。

## 构建、测试与安装

本项目不依赖 Gradle Wrapper，直接使用 Android SDK（`minSdk 30`、`targetSdk 35`、`javac 11+`）构建，APK 体积仅数十 KB。

### 1. 运行协议单元测试（无需手机或 Android SDK）

```bash
bash ./test_protocol.sh
```

### 2. 编译打包 APK（Linux / macOS / WSL）

需已安装 Android SDK（设置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`，默认回退 `/opt/android-sdk`）：

```bash
bash ./build.sh
```

生成安装包位于 `build/acefit-neo-companion.apk`。

### 3. 一键测试、编译并安装到手机

```bash
bash ./deploy.sh                # 安装到当前默认 ADB 设备
bash ./deploy.sh <device_id>    # 指定 ADB 设备序列号或无线调试 ip:port
```

## 目录结构

```text
.
├── AndroidManifest.xml
├── build.sh                     # 原生 aapt2 + javac + d8 + zipalign + apksigner 构建脚本
├── test_protocol.sh             # 协议编解码与 CRC 单元测试脚本
├── deploy.sh                    # 测试 + 编译 + ADB 安装与授权脚本
├── res/                         # 自适应应用图标与 DayNight 主题样式
├── src/com/acefit/neo/
│   ├── NeoProtocol.java         # D0D2C5C2 协议帧校验、指令构造与状态解析
│   ├── NeoBleClient.java        # 串行化 BLE GATT 状态机（MTU/CCCD/写入队列）
│   └── MainActivity.java        # 单页伴侣仪表盘界面
└── test/com/acefit/neo/
    └── NeoProtocolTest.java     # 实机抓包回放与校验和单元测试
```

## 免责声明

本项目为针对个人持有耳机的独立开源实现，与 ACEFAST 官方无任何关联。仅发送协议内标准的查询、音效切换与手势配置指令，不涉及固件刷写。使用本软件所产生的一切风险由使用者自行承担。

## License

[MIT](./LICENSE)

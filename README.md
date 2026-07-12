# SerendiModGuarder 🛡️

[English](#english) · [中文](#中文)

---

<a id="english"></a>

## English

SerendiModGuarder is a Fabric mod that detects and blocks players using forbidden client-side mods. **Both server and client must install this mod.**


### How It Works

SerendiModGuarder uses a **three-layer defense** to catch cheat mods:

| Layer | Phase | Method |
|-------|-------|--------|
| **① Login Query** | Login (LOGIN) | Server sends a random nonce → client responds with mod list + SHA256 signature. Kicks if missing, tampered, or containing forbidden mods. |
| **② Play Push** | After JOIN (PLAY) | Client proactively pushes full mod list 2 seconds after joining. Server checks against the blacklist again. |
| **③ Delayed Verify** | T+60s after JOIN | Server inspects plugin channels and cross-checks login vs play mod lists to detect late-loading or dynamic cheats. |

### Features

- ✅ **Three-layer defense** — SHA256-signed login query + play-phase push + delayed channel scan
- ✅ **Anti-tamper** — Mod list is signed with SHA256 + server nonce; tampering is detected
- ✅ **Configurable blacklist** — Manage forbidden mod keywords via `config/serendimodguarder.json`
- ✅ **Hot reload** — `/serendimodguarder reload` applies config changes without restart
- ✅ **Manual inspection** — `/serendimodguarder check <player>` shows detailed verification state and plugin channels
- ✅ **Configurable timeout** — Login query timeout (default 10s); unresponsive clients are kicked
- ✅ **Customizable messages** — Separate messages for kick, "must install", and timeout

### Requirements

- Minecraft **1.21.5** (26.2)
- **Fabric Loader** ≥0.19.3
- **Fabric API** (any version compatible with 1.21.5)
- **Clients must also install SerendiModGuarder** — otherwise they will be kicked

### Installation

1. Place the JAR in the server's `mods/` folder
2. **All players must also install the mod** on their client's `mods/` folder
3. Restart the server (clients restart or reconnect)
4. Config file auto-generated at `config/serendimodguarder.json`
5. Customize the forbidden mod list to your needs
6. Run `/serendimodguarder reload` to apply changes

### Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/serendimodguarder reload` | Reload configuration | OP level 2 |
| `/serendimodguarder check <player>` | View player verification state + plugin channels | OP level 2 |

### Configuration

Generated at `config/serendimodguarder.json` on first run:

```json
{
  "enabled": true,
  "debugMode": false,
  "loginTimeoutSeconds": 10,
  "kickMessage": "§c检测到你正在使用违规Mod: §e%mod%\n§c请移除后重新加入服务器！\n§7若您未使用作弊Mod，请确保安装了 §eSerendiModGuarder §7后再进入。",
  "mustInstallMessage": "§c本服务器要求必须安装 §eSerendiModGuarder §c才能进入！\n§7请访问服务器官网或联系管理员获取 Mod 文件。",
  "timeoutMessage": "§c登录验证超时！\n§7请确保安装了 §eSerendiModGuarder §7，然后重新加入。",
  "forbiddenMods": [
    "meteor", "wurst", "aoba", "bthack", "catlean",
    "eternity", "minced", "pubdlc", "thunderhack",
    "liquidbounce", "augustus", "aristois", "future",
    "impact", "lambda", "novoline", "phobos",
    "rusherhack", "salhack", "seppuku", "sigtools",
    "weepcraft", "xray", "jello", "inertia",
    "bleach", "nightx", "youtube", "kami",
    "forgehax", "exeter", "crystallix"
  ]
}
```

#### Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | Boolean | `true` | Master toggle — set to `false` to disable all checks |
| `debugMode` | Boolean | `false` | Logs all players' registered channels for fine-tuning |
| `loginTimeoutSeconds` | Integer | `10` | Login query timeout. Kicks client if no response within this time. |
| `kickMessage` | String | *(see above)* | Kick message. Placeholders: `%mod%`, `%player%`, `%reason%` |
| `mustInstallMessage` | String | *(see above)* | Message shown when client doesn't have the mod installed |
| `timeoutMessage` | String | *(see above)* | Message shown on login query timeout |
| `forbiddenMods` | String[] | *(see above)* | Keywords matched case-insensitively against mod IDs and plugin channels |

### Build

```bash
./gradlew build
```

Output: `build/libs/SerendiModGuarder-<version>.jar`

### License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](./LICENSE) file for details.

### Acknowledgements

This project references the design of [InertiaAntiCheat](https://github.com/DiffuseHyperion/InertiaAntiCheat) (Copyright © 2023 DiffuseHyperion, GPL-3.0).

---

<a id="中文"></a>

## 中文

SerendiModGuarder 是一款 Fabric Mod，用于检测并拦截使用了违规客户端 Mod 的玩家。**服务端和客户端都必须安装本 Mod。**

### 工作原理

SerendiModGuarder 采用 **三道防线** 来检测作弊 Mod：

| 防线 | 阶段 | 方式 |
|------|------|------|
| **① 登录查询** | 登录（LOGIN） | 服务端发送随机数 → 客户端返回 Mod 列表 + SHA256 签名。缺失、篡改、或包含违规 Mod 即踢出。 |
| **② 游戏推送** | 加入后（PLAY） | 客户端加入后主动推送完整 Mod 列表，服务端再次检查黑名单。 |
| **③ 延迟验证** | T+60 秒后 | 检查玩家插件频道、交叉比对登录和游戏阶段的 Mod 列表，防止动态加载作弊。 |

### 功能特性

- ✅ **三道防线** — SHA256 签名登录查询 + 游戏阶段推送 + 延迟频道扫描
- ✅ **防篡改** — Mod 列表使用 SHA256 + 服务端随机数签名，篡改可被检测
- ✅ **可配置黑名单** — 通过 `config/serendimodguarder.json` 管理违禁 Mod 关键词
- ✅ **热重载** — `/serendimodguarder reload` 即时生效，无需重启
- ✅ **手动检查** — `/serendimodguarder check <玩家>` 查看详细验证状态和频道信息
- ✅ **超时控制** — 登录查询超时（默认 10 秒），未响应客户端自动踢出
- ✅ **自定义消息** — 踢出、强制安装、超时等消息均可自定义

### 运行环境

- Minecraft **1.21.5**（26.2）
- **Fabric Loader** ≥0.19.3
- **Fabric API**（与 1.21.5 兼容的任意版本）
- **客户端必须同时安装 SerendiModGuarder**，否则将被踢出

### 安装方法

1. 将 JAR 放入服务端的 `mods/` 文件夹
2. **所有玩家也必须在客户端安装本 Mod**，放入 `mods/` 文件夹
3. 重启服务端（客户端重启或重新连接）
4. 配置文件自动生成在 `config/serendimodguarder.json`
5. 根据需要编辑配置文件中的违禁 Mod 列表
6. 在服务器中执行 `/serendimodguarder reload` 使配置生效

### 指令

| 指令 | 说明 | 权限 |
|------|------|------|
| `/serendimodguarder reload` | 热重载配置文件 | OP 2 级 |
| `/serendimodguarder check <玩家>` | 查看玩家验证状态和插件频道 | OP 2 级 |

### 配置说明

首次运行自动生成于 `config/serendimodguarder.json`：

```json
{
  "enabled": true,
  "debugMode": false,
  "loginTimeoutSeconds": 10,
  "kickMessage": "§c检测到你正在使用违规Mod: §e%mod%\n§c请移除后重新加入服务器！\n§7若您未使用作弊Mod，请确保安装了 §eSerendiModGuarder §7后再进入。",
  "mustInstallMessage": "§c本服务器要求必须安装 §eSerendiModGuarder §c才能进入！\n§7请访问服务器官网或联系管理员获取 Mod 文件。",
  "timeoutMessage": "§c登录验证超时！\n§7请确保安装了 §eSerendiModGuarder §7，然后重新加入。",
  "forbiddenMods": [
    "meteor", "wurst", "aoba", "bthack", "catlean",
    "eternity", "minced", "pubdlc", "thunderhack",
    "liquidbounce", "augustus", "aristois", "future",
    "impact", "lambda", "novoline", "phobos",
    "rusherhack", "salhack", "seppuku", "sigtools",
    "weepcraft", "xray", "jello", "inertia",
    "bleach", "nightx", "youtube", "kami",
    "forgehax", "exeter", "crystallix"
  ]
}
```

#### 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | 布尔 | `true` | 总开关，设为 `false` 禁用所有检测 |
| `debugMode` | 布尔 | `false` | 开启后记录每位玩家的频道信息，用于调优黑名单 |
| `loginTimeoutSeconds` | 整数 | `10` | 登录查询超时秒数。超过此时未响应的客户端将被踢出。 |
| `kickMessage` | 字符串 | *(见上)* | 踢出消息。占位符：`%mod%`、`%player%`、`%reason%` |
| `mustInstallMessage` | 字符串 | *(见上)* | 未安装本 Mod 时的提示消息 |
| `timeoutMessage` | 字符串 | *(见上)* | 登录查询超时时显示的踢出消息 |
| `forbiddenMods` | 字符串数组 | *(见上)* | 违禁 Mod 关键词列表，对 Mod ID 和插件频道进行不区分大小写的子串匹配 |

### 防线详解

**第 1 道防线 — 登录查询：**
1. 玩家连接服务器时，服务端发送一个随机数（nonce）
2. 已安装本 Mod 的客户端返回完整 Mod 列表 + SHA256 签名
3. 服务端验证签名：若签名不匹配则判定为数据被篡改
4. 检查 Mod 列表中是否包含违禁 Mod
5. 若客户端未安装本 Mod（未响应），则踢出并提示强制安装

**第 2 道防线 — 游戏阶段推送：**
1. 玩家加入游戏后 2 秒，客户端主动推送 Mod 列表
2. 服务端再次检查违禁 Mod
3. 可捕获在登录完成后才加载的 Mod

**第 3 道防线 — 延迟验证（T+60 秒）：**
1. 玩家加入 60 秒后，服务端检查其注册的 Fabric 插件频道
2. 许多作弊 Mod 会在频道中暴露特征标识
3. 交叉比对登录阶段和游戏阶段的 Mod 列表
4. 对异常情况（如 Mod 多但频道少）记录日志

### 构建

```bash
./gradlew build
```

输出文件：`build/libs/SerendiModGuarder-<version>.jar`

### 开源许可证

本项目基于 **GNU General Public License v3.0（GPL-3.0）** 开源。详见 [LICENSE](./LICENSE) 文件。

### 致谢

本项目参考了 [InertiaAntiCheat](https://github.com/DiffuseHyperion/InertiaAntiCheat)（Copyright © 2023 DiffuseHyperion，GPL-3.0）的设计思路。

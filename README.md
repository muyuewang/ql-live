# 轻流直播 (QL-Live)

一款纯净的 IPTV 电视直播客户端，提供 **安卓 TV** 与 **Windows 桌面** 两个版本。无广告、无会员墙、无社区、无多余权限，专注把直播频道稳定、快速地播出来。

- 安卓端：针对电视盒子 / 安卓 TV 优化，完整支持遥控器，双架构（arm64-v8a + armeabi-v7a）。
- 桌面端：基于 Tauri 的 Windows 应用，自带解析与播放，开箱即用。

预编译安装包请在仓库 **Releases** 页面获取。

---

## 功能特性

- **频道分组**：收藏 / 央视 / 卫视 / 高清 / 轮播 / 体育 / 影视 / 省份
- **EPG 节目单**：频道列表双行显示（频道名 + 当前节目），EPG 实时拉取
- **播放控制**：
  - 解码模式切换（硬解 / 软解 / 系统播放器）
  - 线路切换、卡顿自动换线、画面比例、自动旋转
- **TV 遥控器适配**：
  - **OK** 唤出频道列表，**MENU** 唤出设置
  - **上下键** 直接换台，**数字键** 跳转频道（带缓冲期大字提示）
  - **返回键** 优先关闭弹层；无弹层时二次确认退出
- **统一深色弹窗 UI**，焦点高亮适配大屏
- **DLNA 投送**（安卓端）
- **自定义节目源**，支持从文件 / 链接导入

---

## 仓库结构

```
ql-live/
├── android/                 # 安卓 TV 端源码与构建脚本
│   ├── src/                 # 应用 UI 源码（com.muyue.ql.myui 包）
│   │   └── com/muyue/ql/myui/
│   │       ├── MainActivity.java    # 主界面 / 遥控按键 / 选台 / 弹层
│   │       ├── PlayerEngine.java    # 播放器封装（解码 / 缓冲 / 线路切换）
│   │       ├── OnlineSource.java    # 在线频道源（分组列表 / 签名请求）
│   │       ├── EpgFetcher.java      # EPG 节目单拉取
│   │       ├── ChannelStore.java    # 频道数据模型 / 收藏持久化
│   │       ├── CustomSource.java    # 自定义节目源
│   │       └── DlnaCast.java        # DLNA 投送
│   ├── src_stub/            # 编译期桩（仅接口签名声明，供主工程引用）
│   └── scripts/
│       ├── build_myui.sh    # 一键构建：javac → d8 → apktool → 注入 dex → 签名
│       └── gen_icon.py      # 启动图标生成（PIL）
├── desktop/                 # Windows 桌面端源码（Tauri + Rust + 前端）
│   ├── src-tauri/           # Rust 核心：取源、解析、签名、窗口
│   │   ├── src/
│   │   │   ├── main.rs      # 应用入口 / 窗口 / 命令注册
│   │   │   ├── source.rs    # 分组 / 频道 / 线路解析（kdsvod、htmlplay 等）
│   │   │   ├── crypto.rs    # 请求签名 / AES / 解密
│   │   │   └── extra.rs     # 辅助功能（EPG、收藏、自定义源等）
│   │   ├── tauri.conf.json  # Tauri 配置
│   │   ├── Cargo.toml
│   │   ├── capabilities/    # 权限声明
│   │   └── icons/           # 应用图标
│   └── frontend/            # 前端界面（原生 HTML / CSS / JS，hls.js 播放）
│       ├── index.html
│       ├── css/style.css
│       └── js/app.js
├── LICENSE                  # MIT
└── README.md
```

---

## 安卓端构建

环境要求：

- JDK 21（`javac` / `java`）
- Android SDK 平台工具（`apktool`、`d8`）
- 构建依赖：`android-all.jar`（stub 编译）、`d8.jar`（DEX 构建）
- 签名：`uber-apk-signer.jar` 与你的 keystore

构建步骤（脚本已内置完整流程）：

```bash
# 1) 在 android/scripts/build_myui.sh 中确认工具链路径
#    - JDK 路径
#    - ../../toolchain/android-all.jar、d8.jar
#    - 签名 keystore（ks_new.jks 等，自行准备）
#    - 用于注入 dex 的 apktool 基础工程 proj2

# 2) 执行构建
bash android/scripts/build_myui.sh

# 产物：youtu_myui_signed.apk（已用你的 keystore 签名）
```

> 说明：构建基于一个已准备好的 apktool 基础工程（`proj2`），脚本负责编译 UI 源码、打包 `classes7.dex`、注入 assets 并完成签名。首次接入时请按注释调整本地路径。

安装到设备：

```bash
adb install -r youtu_myui_signed.apk
```

---

## 桌面端构建

环境要求：

- Rust 工具链（MSVC 目标）+ Cargo
- Node.js（用于 Tauri CLI，可选）
- Windows：安装 [WebView2](https://developer.microsoft.com/microsoft-edge/webview2/) 运行时
- Tauri v2 CLI：`cargo install tauri-cli` 或 `npx @tauri-apps/cli`

构建步骤：

```bash
cd desktop/src-tauri

# 方式一：cargo tauri
cargo tauri build

# 方式二：Tauri CLI（如已安装）
npx @tauri-apps/cli build
```

构建产物位于 `desktop/src-tauri/target/release/`：

- `轻流直播-单体版.exe`：可直接运行的单文件可执行程序
- `轻流直播_1.0.0_x64-setup.exe`：NSIS 安装包

> 前端为原生 HTML/CSS/JS（已内置 `hls.min.js`），无需额外打包步骤；Tauri 会直接加载 `frontend/` 目录。

---

## 使用说明

- **安卓端**：安装 APK 后打开，OK 键唤出频道列表，上下键换台，MENU 键打开设置（解码模式、画面比例、自动换线等）。
- **桌面端**：双击 `轻流直播-单体版.exe` 即可运行；首次使用建议在设置中确认节目源与解析线路。

---

## 目录约定

- 所有解析、签名、解密逻辑均在对应端源码内实现，便于审计与二次开发。
- 频道源与线路来自公开可访问的直播源服务，请根据当地法规与平台条款合理使用。

---

## License

MIT —— 详见 [LICENSE](LICENSE)。

# 轻流直播 (QL-Live)

轻流直播是一款干净的安卓电视直播客户端，无广告、无会员、无社区、无敏感权限。

## 特性

- 频道分组：收藏 / 央视 / 卫视 / 高清 / 轮播 / 体育 / 影视 / 省份
- 频道列表双行显示：频道名 + 当前节目（EPG 实时节目单）
- 解码模式切换（硬解 / 软解 / 系统播放器）、线路切换、卡顿自动换线、画面比例、自动旋转
- TV 遥控器完整支持：
  - **OK** 唤出频道列表，**MENU** 唤出设置
  - **上下键**直接换台，**数字键**直接跳转频道（1.2 秒缓冲期，屏幕大字显示选台号码）
  - **返回键**优先关闭弹层；无弹层时弹窗二次确认退出
- 统一的深色弹窗 UI，焦点高亮适配电视大屏
- DLNA 投送
- 双架构（arm64-v8a + armeabi-v7a），兼容电视盒子

## 目录结构

```
ql-live/
├── app/
│   ├── src/          # 轻流直播 UI 源码（myui 包）
│   │   └── com/muyue/ql/myui/
│   │       ├── MainActivity.java   # 主界面 / 遥控器按键 / 选台 / 弹层
│   │       ├── PlayerEngine.java   # 播放器封装（解码选项、缓冲策略、线路切换）
│   │       ├── OnlineSource.java   # 在线频道源（分组列表 / 签名请求）
│   │       ├── EpgFetcher.java     # EPG 节目单拉取
│   │       ├── ChannelStore.java   # 频道数据模型 / 收藏持久化
│   │       ├── CustomSource.java   # 自定义节目源
│   │       └── DlnaCast.java       # DLNA 投送
│   └── src_stub/     # 编译期桩（仅接口签名声明）
├── scripts/
│   ├── build_myui.sh # 一键构建：javac → d8 → 打包 → 签名
│   └── gen_icon.py   # 启动图标生成（PIL）
└── LICENSE
```

## 构建

```bash
# 修改 build_myui.sh 中的工程目录后执行
bash scripts/build_myui.sh
# 产物：youtu_myui_signed.apk（已用你的 keystore 签名）
```

## License

MIT（见 [LICENSE](LICENSE)）。

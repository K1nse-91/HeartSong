# HeartSong

![build](https://github.com/K1nse-91/HeartSong/actions/workflows/build.yml/badge.svg)

Minecraft **26.1 / 26.2 / 26.3** 的 Fabric 客户端模组：血量低于可自定义阈值时自动播放你选的 MP3，回到高血量自动停止。带游戏内曲库、音量与阈值滑块、删除确认。

作者：**K1nse_**　|　图标：粉心 + 均衡器

## 功能

- **血量联动**：低于「开始阈值」开始循环播放，高于「停止阈值」停止，两个阈值之间保持当前状态。
- **阈值可调**：界面内两根滑块分别设置开始 / 停止百分比，默认 20% / 40%，互相约束、自动保存。
- **曲库**：可导入多首 MP3，左侧列表单击切换；正在低血量播放时切歌会立即换曲继续。
- **导入即保存**：点「浏览并导入 MP3…」选中文件后直接校验、复制、选中，没有额外确认步骤；也支持把 MP3 拖进界面。
- **删除**：选中曲目后点「删除选中音乐」，需在 4 秒内再点一次确认，防止误删。
- **音量滑块**：0%～100%，与试听、自动播放共用。
- **快捷键**：默认 H，可在界面底部直接跳转原版按键绑定页修改，也出现在「选项 → 控制 → 按键绑定 → HeartSong」分类。
- **跨版本**：同一套源码编译出 26.1 / 26.2 / 26.3 三个包。

## 下载

在 [Releases](https://github.com/K1nse-91/HeartSong/releases) 下载对应游戏版本的 jar：

| 游戏版本 | 文件 | `depends.minecraft` |
|---|---|---|
| 26.1（含 26.1.x） | `heartsong-1.3.1+26.1.jar` | `26.1.x` |
| 26.2 | `heartsong-1.3.1+26.2.jar` | `26.2` |
| 26.3（含 26.3.x） | `heartsong-1.3.1+26.3.jar` | `26.3.x` |

只放**一个**对应版本的 jar 到 `mods`，不要同时放多个。

## 环境要求

- Minecraft Java Edition 26.1 / 26.2 / 26.3
- Fabric Loader **0.19.0+**
- Fabric API（对应游戏版本）
- Java 25
- 仅客户端；多人服务器只需要客户端安装

## 使用

1. 按 **H** 打开界面。
2. 「浏览并导入 MP3…」选择文件，或直接拖入 MP3。
3. 左侧点选曲目切换；「删除选中音乐」需点两次。
4. 拖动音量 / 开始阈值 / 停止阈值滑块，松手即保存。
5. 底部「更改快捷键（H）」进入原版按键绑定页，改完点「完成」返回。

播放规则：

| 当前血量 | 行为 |
|---|---|
| 低于开始阈值 | 自动开始循环播放 |
| 高于停止阈值 | 自动停止 |
| 两个阈值之间（含端点） | 保持当前状态，不开始也不停止 |

死亡、退出世界、切换维度会停止并重置；重生后若处于中间区间不会自动开始。

## 文件位置

```
config/heartsong/settings.json   设置（音量、阈值、当前曲目、开关）
config/heartsong/music/*.mp3     曲库（导入时复制，原文件可移动/删除）
```

旧版单曲 `config/heartsong/selected.mp3` 会在首次启动时自动迁移进 `music/` 并保持选中。

## 跨版本实现说明

- 26.1 的 `screen` 字段与 `setScreen` 在 `Minecraft` 上，26.2/26.3 移到 `Gui`，运行时反射兼容两种归属。
- 26.3 把 `InputConstants.Type.KEYSYM` 改名为 `KEYBOARD`，统一改用 `KeyMapping(String, int, Category)` 构造。
- 三个包分别用对应官方客户端与 Fabric API 编译：
  - 26.1 → Fabric API 0.155.3+26.1.2
  - 26.2 → Fabric API 0.161.0+26.2
  - 26.3 → Fabric API 0.165.2+26.3

## 构建

需要 JDK 25 与 Gradle 9.8+（Loom 1.19.3 要求）：

```bash
./gradlew build
```

产物在 `build/libs/`。

## 第三方

MP3 解码使用 [JLayer](https://github.com/umjammer/jlayer)（`com.googlecode.soundlibs:jlayer:1.0.1.4`，LGPL-2.0+），以独立嵌套 JAR 形式打包在 `META-INF/jars`，可替换。详见 jar 内 `THIRD-PARTY-NOTICES.txt`。

模组本身使用 MIT 许可证，见 [LICENSE](LICENSE)。

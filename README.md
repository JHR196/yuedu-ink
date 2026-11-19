<div align="center">

# 阅读 · 纸墨

**把 Legado 的前端整个重做了一遍：立体书架、书柜式分组、书柜总览。**

[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3DDC84.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF.svg)

[下载安装包](https://github.com/JHR196/yuedu-ink/releases) · [构建](#构建) · [上游 Legado](https://github.com/gedoor/legado)

</div>

基于开源阅读 [Legado](https://github.com/gedoor/legado)（提交 `da17bb2`）。**只改前端**：布局、样式、`ui/` 下的展示代码和默认主题。书源、规则解析、网络、数据库和阅读排版引擎都没有动，书源规则与原版通用。

## 它有什么不一样

| | |
|---|---|
| **立体书架** | 3D 书架视图：书脊朝外、封面朝前，木质层板，台灯光晕，底部书签尺可拖动定位。 |
| **书柜式分组** | 每个分组是书柜的一层，层板上有铜铭牌。上下拖动换层，点铭牌进入分组管理。 |
| **书柜总览** | 在立体书架上双指合拢，或从右上角菜单打开「书柜总览」。一层一类，书脊整齐排列，点一层放大进入。支持「标签」和「文件夹」两种分组样式。 |
| **全页面重做** | 浮动底栏、我的、发现、订阅、书籍详情、目录、阅读菜单、阅读样式面板、对话框，日夜两套主题。 |
| **阅读样式** | 新增「纸墨」预设（纸纹背景、衬线字）。启动页书法字标，新图标。 |

封面规则：有书源封面或自定义封面就用它，没有才用默认封面。总览里的书脊只有在用默认封面时才会写书名。

## 安装

从 [Releases](https://github.com/JHR196/yuedu-ink/releases) 下载 APK 安装即可。每次更新的内容见 [更新说明](更新说明.md)。

- 包名是 `io.legado.app.shelf3d`，**可与原版阅读共存**，两边数据互相独立。
- 安装包是 debug 签名，覆盖安装同包名的旧版会保留数据。
- 最低 Android 5.0（API 21）。
- 已在手机和平板上实机运行。

## 书源与内容

应用本身不提供任何书籍内容，本仓库也不附带书源文件，请在应用里自行导入。
安装包里只有 Legado 自带的少量默认书源。使用须知见上游的 [免责声明](app/src/main/assets/disclaimer.md)。

## 构建

需要 JDK 17 和 Android SDK。构建时加 `-Pshelf3d` 才会得到 `.shelf3d` 包名的实验包：

```bash
./gradlew :app:assembleAppDebug -Pshelf3d
```

产物在 `app/build/outputs/apk/app/debug/`。不加 `-Pshelf3d` 则按上游默认配置构建。

## 代码在哪

书柜相关的核心代码都在 `app/src/main/java/io/legado/app/ui/main/bookshelf/shelf3d/`：

| 文件 | 内容 |
|---|---|
| `Shelf3DView.kt` | 立体书架：透视绘制、封面与书脊、触摸与滑动 |
| `ShelfCabinetView.kt` | 书柜总览：胡桃木书柜、层板、书脊排列、双指缩放 |
| `Shelf3DItems.kt` | 把书和分组转成书架上的条目 |

设计规范和主题见 `app/src/main/res/` 下的 `drawable`、`values`。

## 第三方内容

- 启动页字标的字形取自 Ma Shan Zheng（SIL OFL 1.1，© The Ma Shan Zheng Project Authors），已转成矢量路径，见 `app/src/main/res/drawable/sp_wordmark.xml`。
- 书柜总览的交互思路参考过 Folia 播放器的唱片墙；画面和代码均为原创，没有使用 Folia 的代码或素材。
- 其余代码和图形资源由本项目原创或生成。

## 许可与上游

本项目是 Legado 的衍生作品，按 **GPL-3.0** 发布，见 [LICENSE](LICENSE)。
上游项目：<https://github.com/gedoor/legado>。

# 基于vibe coding的音乐实验室 V1 · QPlayerStyleMusic

一个独立的 Android 音乐播放器 **源码示例**，安卓端布局参考Qplayer安卓端播放器界面，基于Salt UI开发。

## 已包含

- 四个导航页：首页、搜索、本地、我的
- Android MediaStore 扫描本地音乐，适配 Android 8–15 的音频读取权限模式
- Media3 ExoPlayer + MediaSessionService 后台播放，系统媒体控制（通知栏、锁屏、耳机）
- 进度条、上一曲、下一曲、随机/循环、播放暂停
- 全屏播放页（封面、信息、进度条、控制按钮）
- 收藏和最近播放列表的简单本地持久化（SharedPreferences）
- 深色/浅色模式与 Salt UI 主题接入
- 小屏底部导航、宽屏左侧导航
- 封面图片优先从系统 MediaStore 读取，取不到使用渐变占位

## 工具链（初始约定）

- Android Studio：使用支持 AGP 9.1.1 的新版
- JDK 21
- Android SDK Platform 36 与 Build Tools 36.0.0
- Kotlin Compose Compiler 2.4.20
- Gradle 9.3.1 + Android Gradle Plugin 9.1.1
- Compose UI / Foundation / Animation 1.12.1
- Salt UI 3.0.0-beta02（匹配 Compose 1.12.1）
- Media3 1.11.1
- Android minSdk 26（Android 8.0）

由于生成源码的环境没有 Android SDK 和 Gradle 发行版，也不能联网下载依赖，**本仓库没有预先编译过 APK**。需要在开发机用 Android Studio 执行首次 Gradle Sync 和 Debug 构建。

## Windows 首次打开与构建

1. 安装 Android Studio / SDK Platform 36、JDK 21。Android Studio > Settings > Build Tools > Gradle 中选择 JDK 21。
2. 解压整个文件夹到英文、无空格路径，比如 `D:\Dev\QPlayerStyleMusic`。
3. 打开 PowerShell 并进入根目录：

```powershell
cd D:\Dev\QPlayerStyleMusic
powershell -ExecutionPolicy Bypass -File .\setup-gradle-windows.ps1
```

该脚本仅下载官方 Gradle 9.3.1 发行版，并使用官方 `gradle wrapper` 任务生成 `gradlew.bat` 和 `gradle/wrapper/gradle-wrapper.jar`；不会安装或运行来自 QPlayer 的任何代码。

4. 通过 Android Studio `File > Open` 打开项目根目录，等待 Gradle Sync。
5. 连接已启用 USB 调试的手机，运行 `app` 模块，或在 PowerShell 中执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成文件：`app\build\outputs\apk\debug\app-debug.apk`。

> 如果 Android Studio 初次导入需要先配置 SDK，请在 `File > Project Structure > SDK Location` 选择 Android SDK 路径，或按 Android Studio 提示生成 `local.properties`。不要将 `local.properties` 提交到 Git。

## 使用方式

1. 第一次进入程序，点击「授权读取本地音乐」。
2. 点击右上角刷新图标重新扫描歌曲。
3. 点击歌曲开始播放，点击底部迷你播放器的歌曲信息进入全屏播放页。
4. 全屏播放页右上角的歌词按钮目前显示 V0.2 功能占位，不会伪装已经支持歌词。
5. 在「我的」页面可以查看收藏、最近播放、切换深浅色主题。

## 源码地图

| 位置 | 功能 |
|---|---|
| `MainActivity.kt` | 四页导航，手机/宽屏适配，权限请求，全屏播放页切换 |
| `ui/theme/MusicTheme.kt` | SaltTheme + Material3 视觉令牌 |
| `ui/components/BottomNavigation.kt` | QPlayer 风格底部四导航 / 宽屏导航栏 |
| `ui/components/MiniPlayer.kt` | 迷你播放器 |
| `ui/screens/HomeScreen.kt` | 首页入口、推荐列表、最近添加 |
| `ui/screens/SearchScreen.kt` | 本地歌曲/艺术家/专辑搜索 |
| `ui/screens/LocalScreen.kt` | 全部本地音乐 |
| `ui/screens/MineScreen.kt` | 收藏、最近播放、设置 |
| `ui/screens/PlayerScreen.kt` | 全屏播放页 |
| `data/MusicScanner.kt` | Android MediaStore 扫描 |
| `viewmodel/MusicViewModel.kt` | 各屏共享状态、MediaController |
| `playback/MusicPlaybackService.kt` | Media3 ExoPlayer + MediaSessionService |
## APP运行示例
<img width="1440" height="3168" alt="Screenshot_2026-11-18-17-35-47-31_35825b49281837" src="https://github.com/user-attachments/assets/2084e2a4-5db3-4509-ba80-dd3bc10bb44a" />
<img width="1440" height="3168" alt="Screenshot_2026-11-18-17-35-53-42_35825b49281837" src="https://github.com/user-attachments/assets/19b87168-0754-4c01-91e5-cf62e135feff" />
<img width="1440" height="3168" alt="Screenshot_2026-11-18-17-35-54-88_35825b49281837" src="https://github.com/user-attachments/assets/e73dfc1b-2637-44b4-b7e6-dbc03d78b6c8" />
<img width="1440" height="3168" alt="Screenshot_2026-11-18-17-36-11-27_35825b49281837" src="https://github.com/user-attachments/assets/b0422878-0bc0-480d-aa6c-b64389e1c15f" />

## 后续开发建议

- V0.2：LRC / 逐字歌词、音频封面动态取色、真正的播放队列页
- V0.3：更细致的页面转场、通知栏歌曲封面兼容性、逐字歌词流体动画
- V0.4：Room 和 DataStore 替换原型 SharedPreferences，批量导入歌曲和歌词标签工具

## 设计与开源许可

- QPlayer 原仓库：https://github.com/TIMER-err/qplayer （Apache-2.0）
- Salt UI 原仓库：https://github.com/Moriafly/SaltUI （Apache-2.0）
- 本项目并未包含 QPlayer 原始 QML 文件、代码、品牌或媒体素材，所展示布局为独立实现。
- 依赖的各开源组件仍按其各自许可证使用。正式发布前应检查通知图标、隐私说明、第三方依赖许可证和目标 API 要求。


# BiliMusic

用哔哩哔哩作为音频来源的 Android 音乐播放器。Kotlin + Jetpack Compose + Media3。

## 功能
- 登录哔哩哔哩（应用内网页登录，Cookie 加密保存）
- 搜索 B 站视频里的音乐，只取音频流播放
- 后台播放、锁屏与通知栏控制、耳机按键（Media3 前台媒体服务）
- 播放队列：上一首 / 下一首 / 进度拖动 / 列表循环 / 单曲循环 / 随机
- 收藏歌曲（「我喜欢的」）、创建与管理歌单
- 把 B 站收藏夹一键导入为歌单
- 暖米白 + 陶土橙的浅色 / 深色界面（warm-restrained-ui）

## 得到 APK 的三种方式

**A. 不装任何东西：GitHub Actions（推荐）**
1. 新建一个 GitHub 仓库，把本目录全部内容推上去（分支 main）。
2. 打开仓库的 Actions 页，等 “Build APK” 跑完（约 5 分钟）。
3. 在该次运行页面底部的 Artifacts 里下载 `BiliMusic-debug-apk`，解压得到 `app-debug.apk`，传到手机安装。
   手机需要允许“安装未知来源应用”。

**B. Android Studio**
打开本目录 → 等待 Gradle 同步 → Build > Build APK(s)。
APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

**C. 命令行**
需要 JDK 17、Android SDK（platform 34）和 Gradle 8.7：`gradle assembleDebug`。

## 使用
1. 打开应用，底部「我的」→「登录哔哩哔哩」。
2. 「搜索」里输入歌名，点一首即开始播放，整份结果会成为播放队列。
3. 点心形图标收藏；点「更多」→「加入歌单」。
4. 在「我的」里可以把自己的 B 站收藏夹导入为歌单。

## 已知限制
- 没有在真机或 B 站线上接口上跑过，接口字段与风控策略以实际返回为准。B 站接口无公开承诺，可能随时变化。
- 多 P 视频只播放第一个分 P。
- 没有离线缓存。
- 仅限个人学习与自用；请遵守哔哩哔哩用户协议，尊重内容作者的版权。

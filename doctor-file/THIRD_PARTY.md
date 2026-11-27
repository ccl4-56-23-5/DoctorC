# FileDoctorC 开源声明与致谢

感谢以下项目的作者、维护者和贡献者。本项目继承的源码声明和各组件的许可证随对应文件保存。

## 复用或改编的源码与资源

| 项目 | 用途与位置 | 许可与声明 |
| --- | --- | --- |
| [ZipXtract · WirelessAlien](https://github.com/WirelessAlien/ZipXtract) | FileDoctorC 是其非官方衍生项目，继承归档管理和解压代码。上游提交见 [UPSTREAM.txt](app/src/main/assets/UPSTREAM.txt)。 | GPL-3.0-or-later。[许可证](License) |
| [Liquid-Glass-Android · QWEA0 / pandadog](https://github.com/QWEA0/Liquid-Glass-Android) | 将折射模型改编为 Android View 使用的透镜渲染。出处见 [UPSTREAM.txt](app/src/main/assets/UPSTREAM.txt)。 | MIT。[版权及许可](app/src/main/assets/LiquidGlass-MIT.txt) |
| [Haze · Christopher Banes](https://github.com/chrisbanes/haze) | 2.1.0 的独立 Fused 与 Rim 着色器用于底栏及搜索操作栏。 | [Apache-2.0](app/src/main/assets/Haze-2.1.0-LICENSE.txt) |
| [RikkaHub](https://github.com/rikkahub/rikkahub) | 按 3.3.1-custom.2 配置将深浅色外观响应与着色移植到 Android View。 | [AGPL-3.0](app/src/main/assets/RikkaHub-AGPL-3.0.txt) |
| [7-Zip-JBinding-4Android · omicronapps](https://github.com/omicronapps/7-Zip-JBinding-4Android) | Android 原生绑定包含 Igor Pavlov 的 7-Zip 及 p7zip 代码。[对应源码](third_party/sevenzipjbinding-16.02-2.03-source.zip)随仓库分发，归档内含上游声明。 | LGPL-2.1 及适用的 unRAR 限制。[7-Zip 声明](app/src/main/assets/7-Zip-License.txt) · [LGPL](app/src/main/assets/LGPL-2.1.txt) |
| [Simple Icons](https://simpleicons.org/) | 用于识别局域网设备品牌的图标。 | [CC0](app/src/main/assets/SimpleIcons-CC0.txt) · [商标免责声明](app/src/main/assets/SimpleIcons-DISCLAIMER.txt) |
| [libarchive 测试样本](https://github.com/libarchive/libarchive/tree/master/libarchive/test) | RAR 及多分卷归档测试资源。 | [样本来源与摘要](app/src/nativeAndroidTest/assets/extraction111/sources.json) · [许可](app/src/nativeAndroidTest/assets/extraction111/LICENSE.txt) |

FileDoctorC 使用 unRAR 组件读取 RAR 文件，其适用限制见 7-Zip 声明。

## 构建依赖

应用直接依赖及解析版本见 [app/build.gradle](app/build.gradle)。

| 项目 | 用途 | 许可 |
| --- | --- | --- |
| [zip4j](https://github.com/srikanth-lingala/zip4j) | ZIP 读写与加密 | Apache-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | 归档格式读写 | Apache-2.0 |
| [XZ for Java](https://tukaani.org/xz/java.html) | XZ 压缩和解压 | 0BSD |
| [Libarchive-Android](https://github.com/zhanghai/Libarchive-Android) 与 [libarchive](https://github.com/libarchive/libarchive) | 归档解压及原生格式支持 | 包装层 Apache-2.0、libarchive BSD 及各内含组件许可 |
| [zstd-jni](https://github.com/luben/zstd-jni) 与 [Zstandard](https://github.com/facebook/zstd) | Zstandard 压缩和解压 | BSD-2-Clause，Zstandard 见上游双许可 |
| [OkHttp](https://github.com/square/okhttp) 与 [Okio](https://github.com/square/okio) | TLS 客户端、按接口绑定的套接字及固定长度流式传输。OkHttp 固定为 4.12.0。 | Apache-2.0 |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 局域网 HTTP 服务 | [BSD-3-Clause](app/src/main/assets/NanoHTTPD-LICENSE.txt) |
| [BlurView](https://github.com/Dimezis/BlurView) | 背景模糊 | [Apache-2.0](app/src/main/assets/BlurView-LICENSE.txt) |
| [AndroidX Media3 ExoPlayer 1.10.1](https://github.com/androidx/media/tree/1.10.1) | 本地视频解封装、轨道选择及倍速播放，实际解码由 Android 提供 | [Apache-2.0](app/src/main/assets/Media3-1.10.1-LICENSE.txt) |
| [Glide](https://github.com/bumptech/glide) | 图片和缩略图加载 | BSD-2-Clause、Apache-2.0 及其组件许可 |
| [AndroidX](https://github.com/androidx/androidx) | Android 窗口、列表、相片元数据和组件支持 | Apache-2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin) 与 [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 语言工具与异步任务 | Apache-2.0 |
| [JUnit 4](https://github.com/junit-team/junit4) 与 [AndroidX Test](https://github.com/android/android-test) | JVM 与设备端测试 | EPL-1.0、Apache-2.0 |

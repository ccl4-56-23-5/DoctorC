# MookDoctorC 开源声明与致谢

感谢 gedoor、Ag2S、Unicode ICU、AndroidX Media3 和其他项目的作者、维护者及贡献者。

## 复用的源码与资源

| 项目 | 用途与位置 | 许可与声明 |
| --- | --- | --- |
| [阅读 Legado · gedoor](https://github.com/gedoor/legado) | 取自 [GitLink 镜像](https://www.gitlink.org.cn/mirrors/legado)提交 `78ec3228dfe23379aef45402e9728a3087eee856` 的本地 EPUB 解析和字符集检测模块。保留在 [reader 源码目录](app/src/main/java/local/bookdoctorc/reader/)。 | [GPL-3.0 与 Legado 版权声明](LICENSE) |
| [Ag2S EpubLib](https://github.com/ag2s20150909/EpubLib) | Legado 集成的 [EPUB 解析源码](app/src/main/java/local/bookdoctorc/reader/epub/)，上游基于 [epublib](https://github.com/psiegman/epublib) 及 epub4j。 | 对应上游和集成源码随附声明 |
| [ICU · Unicode, Inc. 与 IBM 等贡献者](https://github.com/unicode-org/icu) | Legado 集成的 [字符集识别源码](app/src/main/java/local/bookdoctorc/reader/charset/)，用于识别本地文本编码。 | [Unicode 许可](https://www.unicode.org/license.txt)及源码版权头 |
| [W3C XHTML](https://www.w3.org/TR/xhtml11/)、[DAISY NCX](https://www.daisy.org/z3986/2005/Z3986-2005.html) 与 Open eBook | EPUB 本地解析使用的 [DTD 与实体定义](app/src/main/resources/dtd/)。 | 各文件内的版权与使用条款 |

## Android 构建依赖

直接依赖及解析版本见 [app/build.gradle](app/build.gradle)。

| 项目 | 用途 | 许可 |
| --- | --- | --- |
| [AndroidX](https://github.com/androidx/androidx) | Android 基础库 | Apache-2.0 |
| [AndroidX Media3](https://github.com/androidx/media) | ExoPlayer、音频播放及系统媒体控制 | [Apache-2.0](app/src/main/assets/AndroidX-Media3-Apache-2.0.txt) |
| [jsoup](https://jsoup.org/) | EPUB 正文 HTML 解析 | MIT |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | EPUB 的 ZIP 归档读写 | Apache-2.0 |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 依赖解析中的协程库约束见构建文件 | Apache-2.0 |
| [Android desugar_jdk_libs](https://github.com/google/desugar_jdk_libs) | Android 上的 Java 核心库 API 兼容 | [上游许可与声明](https://github.com/google/desugar_jdk_libs/blob/master/LICENSE) |
| [JUnit 4](https://github.com/junit-team/junit4) | JVM 单元测试 | EPL-1.0 |

## CLM Studio

感谢 [.NET Runtime](https://github.com/dotnet/runtime)、[WPF](https://github.com/dotnet/wpf) 和 [.NET Windows Forms](https://github.com/dotnet/winforms) 团队。CLM Studio 的 Windows 安装包是自包含发行，随附 .NET 与 Windows Desktop 运行时的 MIT 许可和各自的第三方声明。安装后的程序目录中可查阅 `licenses/`。

安装程序使用 [Inno Setup](https://jrsoftware.org/isinfo.php) 构建。感谢 Jordan Russell、Martijn Laan 与贡献者。[安装器构建入口](clm-studio/installer/build-installer.ps1)

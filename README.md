# DoctorC 系列软件

DoctorC 提供 Android 文件管理与局域网互传，以及本地阅读、播客与音乐播放。

## 软件与下载

- [FileDoctorC（doctor-file）](doctor-file/) · Android 文件管理、压缩解压与局域网互传。[下载 Android APK 1.1.5+30](https://github.com/ccl4-56-23-5/DoctorC/releases/tag/filedoctorc-v1.1.5%2B30)
- [MookDoctorC（doctor-mook）](doctor-mook/) · Android 本地阅读、播客及音乐播放器，支持通过 FileDoctorC 迁移资料。[下载 Android APK 1.0.24+33](https://github.com/ccl4-56-23-5/DoctorC/releases/tag/mookdoctorc-v1.0.24%2B33)
  - [CLM Studio](doctor-mook/clm-studio/) · MookDoctorC 配套的 Windows 音频封装工具，在同一发行页下载 [Windows 安装包 0.2.1+5](https://github.com/ccl4-56-23-5/DoctorC/releases/tag/mookdoctorc-v1.0.24%2B33)。

各项目目录列出使用和构建方法。安装包的 SHA-256 附于发行说明。

## 开源致谢

感谢 ZipXtract、Liquid-Glass-Android、7-Zip、Legado、EpubLib、ICU、AndroidX Media3 与 .NET 桌面运行时的作者和维护者，以及本项目所用其他开源组件的贡献者。

- FileDoctorC 基于 WirelessAlien 的 ZipXtract，并使用 7-Zip 绑定及其他归档组件。液态玻璃折射实现改编自 pandadog 的 Liquid-Glass-Android。[源码来源与第三方许可](doctor-file/THIRD_PARTY.md)
- MookDoctorC 复用了 gedoor 的 Legado 所集成的 EPUB 解析与 ICU 字符集检测源码，播放使用 AndroidX Media3。配套 CLM Studio 使用 .NET 和 WPF。[源码来源与第三方许可](doctor-mook/THIRD_PARTY.md)

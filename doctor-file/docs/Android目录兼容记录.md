# Android 目录兼容记录

参考应用是长沙春觉信息科技有限公司的「解压缩全能王」4.9.9（`com.cjtec.uncompress`，版本码 125，targetSdk 33）。用于复核的应用宝 CDN 样本与 [应用宝条目](https://sj.qq.com/appdetail/com.cjtec.uncompress) 中的 MD5 `08F93DBEA2C9BA714E64D298AD2596B5` 一致，SHA-256 为 `81F0359E13790AD985C40439117F71EF0A550B16BC3B07DD6B235FEAD4B4B278`。

- 在 Android 15 模拟器（API 35，安全补丁 2024-09-05）中，只开启所有文件访问、关闭无线调试且未安装 Shizuku。参考应用从普通文件列表进入 Android/data，能打开另一个应用的三层子目录，并在内置文本编辑器中看到预置的正文。相同条件下，Android/obb 页面未列出预置的另一应用目录。
- 样本的 DEX 包含标准目录选择器、Shizuku 相关类，以及末尾附带 U+200B 的 `Android/data` 路径。本机实测没有调起目录选择器，没有该应用的外部存储文档树授权。ZipDoctorC 据此独立实现了兼容路径探测，未复制参考应用的代码或素材，也未引入对其 APK 的依赖。
- `AndroidFolderAccess` 只在用户进入内部共享存储的 Android/data 或 Android/obb 时尝试对应的兼容路径；正常路径能看到相同条目时继续使用正常路径。列表和路径栏显示普通目录名。文件操作使用实际可读路径。共享存储的 Android/media 继续使用普通目录。
- 兼容路径取决于 Android 实现和安全补丁，不是 Android 提供的正式授权能力。两条路径都不可读时显示限制并保留对本应用目录的访问。这一功能不涉及系统根目录的 `/data/data`。参见 [Android 存储限制](https://developer.android.com/about/versions/11/privacy/storage) 和 [CalyxOS 对外部存储路径问题的说明](https://calyxos.org/news/2025/05/21/external-storage-bypass/)。

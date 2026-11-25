# MookDoctorC 与 FileDoctorC 的迁移接口

MookDoctorC `1.0.20+29` 负责选模块、生成迁移文件和恢复。FileDoctorC `1.1.5+30` 或后续兼容版负责局域网设备选择、传输、校验和落盘。发送设备保留原有源文件。应用更名后，原协议标识继续供既有客户端识别。

## 发送端

1. 在「我的 → 用 FileDoctorC 迁移」选择书架、播客或音乐，点「交给 FileDoctorC」。MookDoctorC 按所选模块打入源文件及对应记录。
2. 通过显式 Intent 交接一份文件。目标包为 `local.sevenzip.android`，动作为 `local.sevenzip.android.action.SEND_BOOKDOCTOR_MIGRATION`，MIME 为 `application/vnd.bookdoctorc.migration+zip`。同一个 `content://` URI 同时放入 `Intent.EXTRA_STREAM` 和 `ClipData`，附加 `FLAG_GRANT_READ_URI_PERMISSION`。
3. FileDoctorC 先查 `OpenableColumns.DISPLAY_NAME` 与 `OpenableColumns.SIZE`，校验 MIME、URI 和 `.bdcm` 扩展名，再将文件流复制到自有暂存目录。交接的 `local.bookdoctorc.transfer` URI 只读。首次打开文件流会移除源暂存目录项，已打开的描述符持续可读直至关闭。查询元数据以及打开输入流的顺序影响交接。
4. 暂存成功后进入 FileDoctorC 的设备选择流程。若系统找不到接收 Intent 的 FileDoctorC，可先选择「保存到本机」，再传送保存的迁移文件。

## 接收端

- FileDoctorC 文件列表对落盘的 `.bdcm` 使用 `Intent.ACTION_VIEW`，目标为 `local.bookdoctorc`。MIME 沿用 `application/vnd.bookdoctorc.migration+zip`，URI 附带只读授权。
- 也可在 MookDoctorC 的「我的 → 用 FileDoctorC 迁移 → 恢复迁移文件」手动选择。收到文件后先由用户确认，应用随后校验并合并记录。

## 文件格式

本版默认建议名以 `MookDoctorC-迁移-` 开头。文件为 ZIP 容器，包含 `bookdoctorc-migration.json`、所选模块的源文件及记录。元数据继续使用 `format = "BookDoctorC"` 和 `schema = 1`。MookDoctorC 校验条目路径、大小及 SHA-256。FileDoctorC 将整份文件按普通传输载荷处理。

维护入口见 [发送、校验与恢复](app/src/main/kotlin/local/bookdoctorc/ModuleTransfer.kt)、[只读交接](app/src/main/java/local/bookdoctorc/ModuleTransferProvider.java) 和 [接收 Intent 声明](app/src/main/AndroidManifest.xml)。

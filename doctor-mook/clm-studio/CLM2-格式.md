# CLM 2 加密流式协议

格式版本 2.0，适用于 CLM Studio 和 MookDoctorC。

本协议取代 CLM v1 ZIP 草案。扩展名仍为 .clm。封面、目录和原始音频全部加密，音频不转码。v1 需重新封装，不能仅改文件名升级。以下约定足够让其他语言独立实现兼容的读写器。




## 数值、字符串与基本限制

- 除 nonce 中的分块序号使用大端外，二进制整数都为无符号小端。
- JSON 使用 UTF-8，无 BOM。
- 块明文上限 C = 65536 字节（64 KiB）；认证标签 T = 16 字节。
- 目录 JSON 明文长度 1–4194304 字节。
- 封面 1–8388608 字节，JPEG/PNG/WebP。
- 音轨数 1–100000；同时受目录 JSON 上限约束。
- 每个数据条目明文长度 1–274877906944 字节（256 GiB）。
- 所有 offset、length 的实现均使用至少 64 位整数，并检查加法溢出。

## 文件头（固定 64 字节）

|偏移|字节数|内容|
|---|---:|---|
|0|8|魔数，十六进制 43 4C 4D 32 45 4E 43 00，即 ASCII CLM2ENC 后接一个 NUL|
|8|4|version = 2|
|12|4|chunkSize = 65536|
|16|4|PBKDF2 iterations = 210000|
|20|4|manifestPlainLength = M|
|24|16|随机 salt|
|40|12|随机 manifestNonce|
|52|12|保留，必须全为零|



文件头后紧跟 M 字节目录密文和 16 字节目录标签。目录 AES-GCM 的 nonce 为 manifestNonce，AAD 为原始 64 字节文件头。输出顺序固定为 ciphertext || tag。认证失败必须报错，禁止继续解码音频。

payloadStart = 64 + M + 16。之后是封面数据、音轨 1、音轨 2……的分块密文，文件末尾不允许额外字节。

## 解密后的目录 JSON

示例只说明结构；offset、length 和 noncePrefix 必须填写真实值。

{
  "format": "clm",
  "version": 2,
  "title": "节目合集",
  "cover": {
    "offset": 0, "length": 55010,
    "noncePrefix": "<8字节的标准Base64>", "mime": "image/jpeg"
  },
  "tracks": [
    { "name": "第001集.mp3", "offset": 55026, "length": 12000000, "noncePrefix": "<另一个8字节的标准Base64>", "mime": "audio/mpeg" }
  ]
}

- cover 的 ordinal = 0；tracks[i] 的 ordinal = i + 1。
- offset 相对于 payloadStart；length 是解密后的原始字节数。
- name 是播放目录显示名称，允许中文，保留后缀以便识别音频类型。
- mime 常用值 audio/mpeg、audio/mp4、audio/aac、audio/wav、audio/flac、audio/ogg；封装支持的原始格式仍受目标设备解码器能力约束。
- encryptedLength(L) = L + ceil(L / 65536) * 16。
- 第一个条目 offset = 0；后一个 offset = 前一个 offset + 前一个 encryptedLength。
- 数据条目按目录顺序连续存储，不能重叠、不能留空洞；最后一个条目结束位置必须等于文件长度。
- 目录显示按文件名自然数字顺序初始化，用户可拖动。达到 100 条时，可识别的集号至少补成三位；封装使用最终显示顺序，源文件不改名。

## 分块加密

条目 E 的第 j 块（j 从 0 开始）：

P = min(65536, E.length - j * 65536)

nonce（12 字节）= E.noncePrefix（8 字节） || uint32-BE(j)

AAD（20 字节）=
  魔数（8字节）
  || uint32-LE(ordinal)
  || uint32-LE(j)
  || uint32-LE(P)

用 K、nonce、AAD 将 P 字节明文 AES-GCM 加密，写 P 字节密文，随后写 16 字节标签。不压缩，不补齐末块。即使条目长度恰好是块大小的整数倍，也不增加空块。

块 j 在文件内的绝对位置 =
  payloadStart + E.offset + j * (65536 + 16)。

最后一块前的每块长度固定，因此任意音频字节位置都能直接定位到对应密文块。

## 校验与实现

- Windows：desktop/ClmV2.cs，系统 AesGcm、Rfc2898DeriveBytes.Pbkdf2。
- Android：ClmContainer.kt、ClmArchive.kt，系统 AES/GCM/NoPadding、PBKDF2WithHmacSHA256。

算法及平台参考：
- NIST GCM 规范：https://csrc.nist.gov/pubs/sp/800/38/d/final
- Android MediaPlayer / MediaDataSource：https://developer.android.com/reference/android/media/MediaPlayer
- Android Os.pread：https://developer.android.com/reference/android/system/Os

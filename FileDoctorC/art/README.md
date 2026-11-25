# ZipDoctorC图形来源

1.0.8新增的 [重命名图标](<sources/SevenZipAndroid/1.0.8/app/src/native/res/drawable-nodpi/art_rename.png>) 直接复用用户已有 [LiteLaunch Renamer图标](<sources/LiteLaunchBatchRenamer/1.0.6/plugins/renamer.ico>) 的256×256帧，经Pillow转换为RGBA PNG；没有生成或重绘图形。该图标不属于下述历史ImageGen资源计数。

## 既有生成图形

本版沿用31项ImageGen原图（含启动器），30项界面/通知PNG。前30张沿用1.0.2，1.0.4新增的play.png是图片/视频分类里的播放徽标，使用内置ImageGen生成，未使用CLI或手工线条绘制图标。

原图在generated/，完整提示词与来源在manifest.json；新图另有play-generation.json。prepare_assets.py仅使用Lanczos缩放和RGBA保存，不绘制图标形状；asset-check.json记录原图摘要、透明通道与打包大小。新的原图为generated/play.png，对应Android资源art_play.png。

最终提示词：Use case: logo-brand. Asset type: a single tiny mobile video playback badge for ZipDoctorC Android file manager, supplied as raster PNG with true transparent background. Exactly one white circular disc, and a dark charcoal right-pointing play triangle neatly centered inside it. Front view, clean flat silhouette with extremely subtle dimensional softness like a polished Xiaomi file-manager media overlay. The circle is nearly pure white, the triangle is solid charcoal. Disc occupies about 86% of square canvas, generous transparent corners. This is a 20dp media thumbnail overlay so strong simple legibility matters; no surrounding tile, no file/document shape, no text, no words, no watermark, no extra symbols. Render the actual image with alpha transparency.

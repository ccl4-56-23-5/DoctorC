# Android 实现与 ZipDoctorC 校准例

只在 Android 材质工作中读取。本例经过 API35 x86_64 模拟器的真实 GPU/交互检查，不是实体手机、帧率、API24–32 或全部 GPU 的性能保证。

## 平台与合成

- `RenderEffect` 可处理 RenderNode；`RuntimeShader`/AGSL 路径需要相应平台能力。ZipDoctorC 的独立透镜使用 API33+，旧版本复用已经存在的 BlurView 兼容路径。
- BlurTarget 应为与玻璃分离的内容子树。记录背景到独立 RenderNode，避免截图→Bitmap→CPU 模糊→上传这一逐帧路径。重用效果与显示列表对象，按 detach/销毁释放资源。
- 采用“外层透镜效果、内层高斯输入”的组合：

```kotlin
val gaussian = RenderEffect.createBlurEffect(
    blurDp * density, blurDp * density, Shader.TileMode.CLAMP
)
backgroundNode.setRenderEffect(RenderEffect.createChainEffect(
    RenderEffect.createRuntimeShaderEffect(lensShader, "content"),
    gaussian
))
// backgroundNode 只记录独立背景。画完它后，再绘制未变形的子控件和边缘。
```

`createChainEffect(outer, inner)` 中 inner 先作为 outer 的输入。模糊应先于最终 SDF 轮廓/高光，否则高光和轮廓会被一起模糊。为采样和外凸留出背景边距；随位置/尺寸/主题变化正确更新 uniform。不要直接对含文字的玻璃 View 使用 `setRenderEffect(blur)`。

旧版 BlurView 的半径单位、有效范围和缩放行为按该版本核实，不把 GPU 的 dp 数值直接照搬进兼容参数。

## 可作为起点的参数

以下为 ZipDoctorC 1.0.10 的项目校准值，依据屏幕密度、背景、主题及目标可读性调整：

| 部位 | 项目起点 | 注意 |
| --- | --- | --- |
| 独立底栏/搜索圆 GPU 背景 | 高斯半径 2dp，转成物理像素 | 先前 3.5dp 擦弱了 12px 彩条的单侧折射信号；2dp 同时保留细节柔化与双侧重映射 |
| GPU 主体底色混合 | 浅色约 34%，深色约 44% | 指背景与中性底色混合，不是把整个控件 alpha 调低 |
| BlurView 兼容材质 | 半径 8；静止覆盖 alpha 浅 162/255、深 178/255 | 不宣称低版本设备已实测；按库的实际单位解释 |
| 已合成的渐隐顶层 | BlurView 半径 10，提高覆盖度 | 圆形按钮继承这层，避免重新采样原图的清晰洞 |
| 菜单/面板缓动 | `PathInterpolator(.2f, .75f, .2f, 1f)` 为进入示例 | 退出可使用协调的独立曲线，驱动应支持取消和反向接续 |

文字与图标保持 scaleX/scaleY=1；选区透镜单独变化。BlurView 内部不加改变控件尺寸的 LayoutTransition；尺寸由布局计算，材质与选区光学另行更新。

## 菜单与窗口

复用现有 `MotionPopup` / `MorphSurface` 或等价原生机制。固定目标正文排版，插值外轮廓和圆角。窗口收回时分别控制轮廓投影、面板填充和来源按钮的透明度。先使投影消失，再淡出填充并显露真实按钮。透明按钮的软件快照可能遗漏合成背景或裁剪，导致交接时短暂出现方块。编辑面板需适配 IME、安全区和分屏；返回键先处理输入法，再关闭面板。内容和窗口均销毁时清除动画与弹窗引用。

Android Activity 的 RESUMED 和 View 屏幕坐标就绪，不等于系统 SurfaceControl 启动动画已完成。屏幕像素检查可以在有时限内重复采样实际位置，待四个目标图标均存在后再保存证据；超时仍报告缺失，不用关闭系统动画掩盖问题。

## 酷安安卓版首页参考

从[酷安国内官网](https://www.coolapk.com/)安装安卓版 16.7.0（2610291，`com.coolapk.market`）后，在 Android 15 模拟器的仅浏览模式查看首页，并在「设置 → 界面显示」确认材质为「液态玻璃」。五个入口共用一条低矮、接近全宽的浮动圆角托盘。选区内缩，有轻微灰色遮蔽，选中的图标与文字同色。托盘边缘与底部阴影很淡，系统手势条独立可见。中央发布用实色强调，选区与独立动作应按各自语义分层。

ZipDoctorC 1.0.19 沿用 1.0.17 的两标签胶囊、独立搜索圆及前景素材与配色。参考用于玻璃主体、边缘、浅灰选区和松手轻微回弹。回弹只作用在选区位置，形变与光学强度单独复位。

若项目使用经典 View，且已有独立背景节点、运行时着色器与兼容材质，先在现有实现中调布局、材质与动效。若项目已用 Compose，可评估 [Backdrop](https://kyant.gitbook.io/backdrop) 的交互底栏示例。参考应用的构图可以独立于库选择验证。

## 已有实现线索

若当前机器仍有工程，可从 `D:\Codex\软件工程\source\ZipDoctorC` 的实际维护版本中按类名查找：

- `LiquidLensRenderer`：独立背景 RenderNode、效果链、SDF 轮廓、折射、色散与高光。
- `LiquidGlassView` / `GlassMaterial`：清晰前景、继承合成顶栏、兼容材质及选区管理。
- `DockGeometry` / `GestureDock`：胶囊、搜索圆及长按/拖动生命周期。
- `MorphPopup` / `NativePopups` / `UiMotion`：菜单和面板变形、统一缓动、来源恢复。
- `LiquidGlassRenderingTest`：细条纹柔化、双侧重映射及前景几何检查。

这些是参考入口，不是依赖。目录迁移后按类名找实际维护版本；复用代码时核对许可。现有透镜适配来源为 QWEA0/Liquid-Glass-Android 固定提交 `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`，原项目 MIT 声明已在应用中保留；ZipDoctorC 应用源码本身仍按其 GPL 许可处理。

平台依据：[RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)、[RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader)、[Android transitions](https://developer.android.com/develop/ui/views/animations/transitions)、[BlurView 上游](https://github.com/Dimezis/BlurView)。升级依赖或换平台时查当前官方文档，勿仅凭本例参数推定兼容。

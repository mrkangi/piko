# VR 播放（360° / 180° 反投影）

播放页在平面屏幕上观看 VR180 / 360 视频：片源按球面或鱼眼投影存储，播放时用 mpv 的用户着色器
重采样成正常透视画面，拖动画面改变视角。不做头部追踪，也不输出给头显。

## 代码位置

| 文件 | 职责 |
| --- | --- |
| `shared/.../media/player/PlaybackBackend.kt` | `VrProjection`、`VrEye`、`VrModeState`（限幅、拖动、缩放、回正、按文件名猜格式）与后端接口 |
| `shared/.../jvmSharedMain/.../MpvVrShader.kt` | 内置 `MAINPRESUB` 反投影着色器、`4x1 rgba8` 参数纹理编码与 `MpvVrShaderSession` 序号轮转 |
| `shared/.../media/player/PlayerScreenState.kt` | `vrMode` 状态与 `updateVrMode` / `panVr` / `zoomVr`，换集回正 |
| `app/.../MpvPlaybackBackend.kt` | Android libmpv：挂着色器、合并高频更新、恢复画面比例 |
| `desktopApp/.../MediampPlaybackBackend.kt` | 桌面 mpv：同上，着色器放 `PikoHome/cache/mpv` |
| `ui/.../player/PlayerGestures.kt` | VR 下拖动转视角、松手惯性阻尼滑行（Fling）、捏合缩放，其余手势让位 |
| `ui/.../player/PlayerSheets.kt` | 设置面板的「VR 视角」分节与投影 / 眼睛 / 鱼眼角度细调 |
| `ui/.../player/PlayerControls.kt` | 底栏 VR 快捷开关（一键开启、开启态高亮、再点退出；窄于 600dp 时收进「更多」） |
| `ui/.../player/PlayerOverlays.kt` | 底部「回正视角 / 退出 VR」胶囊 |

## mpv 侧约束

- **只用 `vo=gpu` 的用户着色器，不要用 FFmpeg `v360` 滤镜。** `v360` 在解码后走 CPU，
  会打断零拷贝硬解（4K/8K 手机与轻薄本直接掉帧），而且暂停时改角度不会重绘。
- 着色器挂 `//!HOOK MAINPRESUB`。这样反投影只作用在视频平面上，字幕后画、保持屏幕平铺；
  挂 `OUTPUT` 会把字幕一起卷进球面。
- **严禁在着色器中使用 `//!PARAM` 与 `glsl-shader-opts`（踩坑实录）：**
  - mpv 0.41.0 的 `//!PARAM` 指令仅在 `vo=gpu-next`（libplacebo 的 `pl_mpv_user_shader_parse`）中实现；
  - Piko 的 Android 端使用 `vo=gpu`，桌面端 MediaMP 使用 `vo=libmpv`（底层绑定 `video/out/gpu/libmpv_gpu.c`），
    二者走的都是传统 `video/out/gpu/user_shaders.c` 的 `parse_user_shader`（仅认识 `//!TEXTURE`、`//!HOOK`、
    `//!BIND`、`//!SAVE`、`//!DESC`、`//!OFFSET`、`//!ALIGN`、`//!WIDTH`、`//!HEIGHT`、`//!WHEN`、`//!COMPONENTS`、`//!COMPUTE`）。
  - 一旦文件里出现 `//!PARAM`，`vo=gpu` / `vo=libmpv` 在第一行就会报 `Unrecognized command 'PARAM fov'!`
    并直接中止解析该着色器文件，表现出来就是「UI 上开关和按钮都能点，但画面毫无变化」。
- **`//!TEXTURE PIKO_VR_PARAMS`（`4x1 rgba8`）零重编译传参方案 + 路径轮转：**
  - 7 个视角参数（`fov`, `yaw`, `pitch`, `roll`, `fisheye_fov`, `input_projection`, `eye`）由 `MpvVrShader.encodeParamsHex`
    按 16-bit 定点编码进 `4x1` 的 `rgba8` 纹理（16 字节，所有移动与桌面 GPU 100% 必选支持 `rgba8`；角度分辨率约 `0.0055°`），
    下方 `//!HOOK MAINPRESUB` 的 GLSL 代码固定不变。
  - 每次更新视角时，`vo=gpu` 的 `shader_cache` 因 GLSL 源码字符串完全未变而 100% 命中已编译的 GPU Program（**零 GLSL 重编译**），
    仅重传 16 字节参数纹理并置 `vo->want_redraw` 立即重绘（含暂停状态）。
  - 注意：`video/out/gpu/video.c` 的 `gl_video_setup_hooks` 会在内存中按文件路径字符串（`strcmp(p->files[n].path, path)`）
    缓存已读取的文件内容，重复传入相同路径不会重新读盘；因此 `MpvVrShaderSession` 每次写出新的序号文件 `piko_vr_<seq>.glsl`
    并通过 `no-osd change-list glsl-shaders set <path>` 下发，同时保留最近 4 份文件防止渲染线程异步读取时撞上文件被删的竞态。
  - Android 端显式设置 `gpu-dumb-mode=no`，防止部分移动 GPU 因未命中 16-bit FBO 自动回退 dumb-mode 而清空 `glsl-shaders`。
- 进入 VR 前必须 `keepaspect=no`（透视投影按窗口真实宽高比铺满），并清掉
  `video-zoom` / `video-pan-x` / `video-pan-y`；否则「框选填充」留下的缩放会叠在球面上。
  退出时按当前 `PlayerAspectRatio` 还原 `keepaspect` / `panscan`。

## 着色器实现注意

- **GLSL ES 不允许用 uniform 初始化全局变量。** 旋转矩阵必须在函数内计算，
  写成全局 `mat3 rot_yaw = mat3(cos(yaw), ...)` 在 Android (`opengl-es=yes`) 上编译失败。
- **GLSL `mat3` 按列主序（column-major）构造：**
  - 要让「向右拖画面（`yaw > 0`）看向左边、向下拖画面（`pitch > 0`）看向上方」符合抓住画面拖动的手感，
    `rot_yaw` 必须写成 `mat3(cy, 0.0, sy, 0.0, 1.0, 0.0, -sy, 0.0, cy)`（使 `dir.x = -sin(yaw)`），
    否则水平拖动方向会与垂直拖动方向相反。
  - 双眼模式（`EYE_BOTH`）拆左右半屏时，`render()` 内判断半屏宽高比折半（`aspect *= 0.5`）必须检查总模式 `eye_mode == EYE_BOTH`，
    不能检查已被替换成单眼（`EYE_LEFT` / `EYE_RIGHT`）的 `source_eye`。
- `asin` / `acos` 的入参要 `clamp`：旋转后的浮点误差可能给出 `1.0000001`，
  Adreno / Mali 上会返回 `NaN`，画面出现黑点。
- 左右并排（SBS）的双目片源要在眼睛边界内缩半个像素，否则双线性插值会把相邻眼睛的像素混进来。
- 首版只保留双线性采样（`HOOKED_tex`）。Lanczos / Mitchell 需要自写采样核，
  每个像素几十次取样，移动端不划算；确需提高清晰度再按设备能力开。
- 支持 5 种投影：360° 单目、360° 上下、180° 鱼眼、180° 左右半宽、180° 单目。
  EAC 立方体与柱面没有进首版。

## 交互与状态归属

- 拖动增量的累加放在 `PlayerScreenState.panVr` / `zoomVr`，**不要在 UI 里用捕获的
  `vrMode` 副本算新状态**：一帧内可能来好几个指针事件，都基于同一份旧状态会让位移丢失。
- 挂在长生命周期 `pointerInput` 里的回调与读数必须走 `rememberUpdatedState`，
  否则视角变了、手势块不重建，读到的还是首次组合时的值。
- VR 开启时单指/鼠标拖动归视角，横滑进退与竖滑亮度音量让位；双击进退、单击显隐、
  长按倍速保留。长按倍速期间的位移不算转视角（`boostingNow`）。
- `detectTransformGestures` 与点击检测可以共存：点击层只消费 down，变换检测用
  `requireUnconsumed = false` 仍能接管拖动；一旦拖动消费了 move，点击层自然判定为取消。
- 状态持有者里 `var vrMode` 的 setter 与 `fun setVrMode(...)` 会撞 JVM 签名，
  所以对外方法叫 `updateVrMode`。
- 不要依赖 `vrEnabled = vr?.enabled == true` 之后的智能转换：Android 与桌面两次编译
  对同一写法的判定并不一致，统一用 `vrMode?.let { ... }`。

## 已知限制

- 视角只在本次播放内有效，不落盘、不跨设备同步；换集会回正但保留投影格式。
- 开了 VR 后「画面比例 / 框选填充」入口隐藏，避免与透视投影叠加。
- 双眼输出（`VrEye.Both`）只是左右并排，给 3D 显示器或 VR 盒子用，普通屏幕看是两幅画。
- 底栏的 VR 快捷开关与设置面板共用 `VrModeState.toggled(title)`：开启时按文件名猜投影并回正视角，
  猜不中或要改眼睛 / 鱼眼角度再进设置面板细调。竖屏窄于 600dp 时它在底栏「更多」里，
  开启后「更多」按钮按开启态高亮，退出仍可点底部的「退出 VR」胶囊。

## 手测清单

1. 打开文件名含 `180` 的左右并排鱼眼片，点底栏 VR 图标（竖屏窄宽度时先点「更多」）：应直接进入「180° 鱼眼」且视角回正。
2. 拖动看四周，左右拖到头不应出现镜像或翻面；上下到 ±90° 停住。
3. 滚轮 / 双指缩放，视野限制在 40°–150°；鱼眼镜头角度可切 180/190/200。
4. 暂停后拖动仍实时更新；继续播放后硬解不中断（详情面板看 `hwdec-current`）。
5. 退出 VR，画面比例回到进入前的选择；框选填充不会被残留缩放影响。
6. 换下一集：视角回正、投影格式保留。
7. VR 开启时底栏图标高亮（窄屏时是「更多」按钮高亮），再点一次应退出；设置面板里的同名开关状态与之同步。

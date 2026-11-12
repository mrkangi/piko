# VR 播放（360° / 180° 反投影）

播放页在平面屏幕上观看 VR180 / 360 视频：片源按球面或鱼眼投影存储，播放时用 mpv 的用户着色器
重采样成正常透视画面，拖动画面改变视角。不做头部追踪，也不输出给头显。

## 代码位置

| 文件 | 职责 |
| --- | --- |
| `shared/.../media/player/PlaybackBackend.kt` | `VrProjection`、`VrEye`、`VrModeState`（限幅、拖动、缩放、回正、按文件名猜格式）与后端接口 |
| `shared/.../jvmSharedMain/.../MpvVrShader.kt` | 内置 `MAINPRESUB` 反投影着色器、gpu-next 的 `//!PARAM` 与回退用的 `4x1 rgba8` 参数纹理、pass 尺寸限幅与 `MpvVrShaderSession` 文件管理 |
| `shared/.../media/player/PlayerScreenState.kt` | `vrMode` 状态与 `updateVrMode` / `panVr` / `zoomVr`，换集回正 |
| `app/.../MpvPlaybackBackend.kt` | Android libmpv：挂着色器、合并高频更新、恢复画面比例 |
| `app/.../VrApplyQueue.kt` | Android VR 请求串行 drain：只留最新视角，保留输出刷新标志，不丢执行期间到来的请求 |
| `desktopApp/.../MediampPlaybackBackend.kt` | 桌面 mpv：同上，着色器放 `PikoHome/cache/mpv` |
| `ui/.../player/PlayerGestures.kt` | VR 下拖动转视角、松手惯性阻尼滑行（Fling）、捏合缩放，其余手势让位 |
| `ui/.../player/PlayerSheets.kt` | 设置面板的「VR 视角」分节与投影 / 眼睛 / 鱼眼角度细调 |
| `ui/.../player/PlayerControls.kt` | 底栏 VR 快捷开关（一键开启、开启态高亮、再点退出；窄于 600dp 时收进「更多」） |
| `ui/.../player/PlayerOverlays.kt` | 左侧竖栏：投影格式（点击轮换）、回正视角、退出 VR |

## mpv 侧约束

- **只用 mpv 的用户着色器，不要用 FFmpeg `v360` 滤镜。** `v360` 在解码后走 CPU，
  会打断零拷贝硬解（4K/8K 手机与轻薄本直接掉帧），而且暂停时改角度不会重绘。
- 着色器挂 `//!HOOK MAINPRESUB`。这样反投影只作用在视频平面上，字幕后画、保持屏幕平铺；
  挂 `OUTPUT` 会把字幕一起卷进球面。
- **Android 进 VR 时切到 `vo=gpu-next`，视角走 `//!PARAM` + `glsl-shader-opts`（主路径）：**
  - 着色器文件里的 7 个参数（`piko_fov`、`piko_yaw`、`piko_pitch`、`piko_roll`、
    `piko_fisheye_fov`、`piko_projection`、`piko_eye`）只在文件头声明一次，GLSL 源码与文件路径都不变。
  - 拖动视角时只写 `glsl-shader-opts`，libplacebo 把参数当 uniform 更新并重绘当前帧，
    **不重新读文件、不重编译 GLSL**。这是拖动流畅的关键。
  - `vo` 设为 `gpu-next,gpu`：设备不支持 libplacebo 时自动回落 `gpu`，不会黑屏。
  - VO 切换是异步的。刚下发 `vo` 时 `current-vo` 仍是 `gpu`，后端先按下面的纹理路径出画面，
    收到 `current-vo=gpu-next` 后再切到参数路径；桌面端 MediaMP 的 `vo=libmpv` 始终走纹理路径。
  - gpu-next 建立后，普通拖动只写一次 `glsl-shader-opts`。VO、Surface 尺寸、当前文件与 pass 尺寸
    只在进入 VR、输出变化或换文件时刷新；不要在每个触摸点读取 mpv 属性或重复绑定相同路径。
- **`vo=gpu` / `vo=libmpv` 的回退路径：`//!TEXTURE PIKO_VR_PARAMS`（`4x1 rgba8`）+ 文件轮转：**
  - 这两条传统渲染路径走 `video/out/gpu/user_shaders.c`，不认识 `//!PARAM`，
    文件里一旦出现就会整份丢弃，所以回退版本把参数编码进纹理。
  - 7 个视角参数（`fov`, `yaw`, `pitch`, `roll`, `fisheye_fov`, `input_projection`, `eye`）由 `MpvVrShader.encodeParamsHex`
    按 16-bit 定点编码进 `4x1` 的 `rgba8` 纹理（16 字节，所有移动与桌面 GPU 100% 必选支持 `rgba8`；角度分辨率约 `0.0055°`），
    下方 `//!HOOK MAINPRESUB` 的 GLSL 代码固定不变。
  - 每次更新视角时，`vo=gpu` 的 `shader_cache` 因 GLSL 源码字符串完全未变而 100% 命中已编译的 GPU Program（**零 GLSL 重编译**），
    仅重传 16 字节参数纹理并置 `vo->want_redraw` 立即重绘（含暂停状态）。
  - 注意：`vo=gpu` 的 `gl_video_setup_hooks` 会在内存中按文件路径字符串缓存已读取的文件内容，重复传入相同路径不会重新读盘；
    因此纹理路径每次写出新的序号文件 `piko_vr_<seq>.glsl` 并通过 `no-osd change-list glsl-shaders set <path>` 下发，
    同时保留最近 4 份文件防止渲染线程异步读取时撞上文件被删的竞态。参数路径只在进入 VR 或窗口尺寸变化时写文件。
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
- **VR 操作放在左侧竖栏**，不要放回底部居中：VR 画面铺满全屏时底部正中正好压住视线下沿，
  而左侧只是一条窄栏，也避开底栏、续播提示与中央播放键。竖栏随控件一起显隐；
  三个操作都用与中央播放键同一套半透明玻璃底加纯图标，不再用带文字的实色胶囊占画面。
- **投影格式按钮点一次轮换一种**：`DualFisheye180 → DualHalfEquirectangular180 →
  HalfEquirectangular180 → Equirectangular360 → DualVertEquirectangular360` 循环，
  只改 `projection`，保留当前朝向与视野，方便原地对比同一视角下的各种封装；不跨视频持久化。
- **着色器 pass 常速为屏幕的 1.25 倍，高倍速降到 1:1**（`//!WIDTH` / `//!HEIGHT`，并 clamp 到源分辨率）。
  `MAINPRESUB` 在放大之前执行，不给尺寸就是按源分辨率跑：8K 片源每帧要处理三千多万像素，
  再缩回屏幕，这是高分辨率 VR 掉帧的主因。限定后只渲染可见视野，像素量少一个数量级，
  1.25 倍还带一点超采样。倍速到 `1.5x` 及以上（`MpvVrShader.passScaleFor`）只渲染 1:1：
  解码与反投影的每秒工作量同时翻倍，先保证帧率，回到常速自动恢复超采样。
  窗口尺寸变化或跨过倍速阈值时，后端会按新尺寸重写一次着色器。
- **鱼眼采样直接用 xy 的单位向量算方位角**：`atan(y, x)` 后再取 `cos/sin` 与 `xy / length(xy)`
  等价，`atan(rho, z)` 又正好等于 `acos(z)`，每像素因此少一次 `atan` 与一对 `cos/sin`；
  `render()` 送进来的方向向量已是单位长度，采样里不再重复 `normalize`。
- **进入 VR 时的 `keepaspect` / `panscan` / `video-zoom` / `video-pan-*` 只在进入时写一次**，
  拖动视角只更新 `glsl-shader-opts`（回退路径只换文件）。原先每个触摸点都写这五项，会反复触发 vo 重新配置，拖动明显更卡。
- **VO、Surface、换文件和视角更新统一走 `VrApplyQueue`**。drain 执行期间调度标志保持置位，
  生产者在锁内替换最新请求；只有确认 pending 为空才清除调度标志。输出刷新请求与后续拖动合并时，
  模式取最新值，`refreshOutput` 取并集，避免最后一次拖动、退出或 Surface 恢复被旧任务吞掉。
- 参数着色器报错时每个播放器实例只回退一次到 gpu 纹理路径，不在两个 VO 之间反复切换。
  VR 输出路径与 pass 尺寸只在变化时写一条日志；播放信息面板的「视频输出」可直接查看 `current-vo`。

## 已知限制

- 视角只在本次播放内有效，不落盘、不跨设备同步；换集会回正但保留投影格式。
- 开了 VR 后「画面比例 / 框选填充」入口隐藏，避免与透视投影叠加。
- 双眼输出（`VrEye.Both`）只是左右并排，给 3D 显示器或 VR 盒子用，普通屏幕看是两幅画。
- 底栏的 VR 快捷开关与设置面板共用 `VrModeState.toggled(title)`：开启时按文件名猜投影并回正视角，
  猜不中或要改眼睛 / 鱼眼角度再进设置面板细调。竖屏窄于 600dp 时它在底栏「更多」里，
  开启后「更多」按钮按开启态高亮；退出与投影轮换都在左侧竖栏。

## 手测清单

1. 打开文件名含 `180` 的左右并排鱼眼片，点底栏 VR 图标（竖屏窄宽度时先点「更多」）：应直接进入「180° 鱼眼」且视角回正。
   左侧竖栏点投影按钮，应在五种格式间循环，且当前视角不动。
2. 拖动看四周，左右拖到头不应出现镜像或翻面；上下到 ±90° 停住。
3. 滚轮 / 双指缩放，视野限制在 40°–150°；鱼眼镜头角度可切 180/190/200。
4. 暂停后拖动仍实时更新；继续播放后硬解不中断（详情面板看 `hwdec-current`）。
5. 退出 VR，画面比例回到进入前的选择；框选填充不会被残留缩放影响。
6. 换下一集：视角回正、投影格式保留。
7. VR 开启时底栏图标高亮（窄屏时是「更多」按钮高亮），再点一次应退出；设置面板里的同名开关状态与之同步。

# VR 播放（360° / 180° 反投影）

播放页在平面屏幕上观看 VR180 / 360 视频：片源按球面或鱼眼投影存储，播放时用 mpv 的用户着色器
重采样成正常透视画面，拖动画面改变视角。不做头部追踪，也不输出给头显。

## 代码位置

| 文件 | 职责 |
| --- | --- |
| `shared/.../media/player/PlaybackBackend.kt` | `VrProjection`、`VrEye`、`VrModeState`（限幅、拖动、缩放、回正、按文件名猜格式）与后端接口 |
| `shared/.../jvmSharedMain/.../MpvVrShader.kt` | 内置 `piko_vr.glsl`、落盘、`glsl-shader-opts` 序列化 |
| `shared/.../media/player/PlayerScreenState.kt` | `vrMode` 状态与 `updateVrMode` / `panVr` / `zoomVr`，换集回正 |
| `app/.../MpvPlaybackBackend.kt` | Android libmpv：挂着色器、合并高频更新、恢复画面比例 |
| `desktopApp/.../MediampPlaybackBackend.kt` | 桌面 mpv：同上，着色器放 `PikoHome/cache/mpv` |
| `ui/.../player/PlayerGestures.kt` | VR 下拖动转视角、捏合缩放，其余手势让位 |
| `ui/.../player/PlayerSheets.kt` | 设置面板的「VR 视角」分节 |
| `ui/.../player/PlayerOverlays.kt` | 底部「回正视角 / 退出 VR」胶囊 |

## mpv 侧约束

- **只用 `vo=gpu` 的用户着色器，不要用 FFmpeg `v360` 滤镜。** `v360` 在解码后走 CPU，
  会打断零拷贝硬解（4K/8K 手机与轻薄本直接掉帧），而且暂停时改角度不会重绘。
- 着色器挂 `//!HOOK MAINPRESUB`。这样反投影只作用在视频平面上，字幕后画、保持屏幕平铺；
  挂 `OUTPUT` 会把字幕一起卷进球面。
- 视角用**一次整串** `setPropertyString("glsl-shader-opts", ...)` 下发，不要照搬
  `mpv360.lua` 的 `change-list ... add`：后者会不断往键值列表里追加，长时间拖动后列表越来越长。
  整串覆盖同样触发 `UPDATE_VIDEO`，不会重编译着色器。
- 选项键是 `<着色器文件名去掉扩展名>/<参数名>`，即 `piko_vr/yaw`。改文件名必须同步改
  `MpvVrShader.optionsOf`。
- 进入 VR 前必须 `keepaspect=no`（透视投影按窗口真实宽高比铺满），并清掉
  `video-zoom` / `video-pan-x` / `video-pan-y`；否则「框选填充」留下的缩放会叠在球面上。
  退出时按当前 `PlayerAspectRatio` 还原 `keepaspect` / `panscan`。
- mpv 在暂停时收到 `UPDATE_VIDEO` 会置 `vo->want_redraw`，所以暂停着拖动也能实时出画面，
  不需要额外 seek 或强制重绘。
- 参数声明的初始值会被 mpv 解析成 uniform；`//!TYPE ENUM int` 的枚举名会变成 `#define`。
  不要声明未使用的参数，`//!PARAM` 每个都算一个 uniform。

## 着色器实现注意

- **GLSL ES 不允许用 uniform 初始化全局变量。** 旋转矩阵必须在函数内计算，
  写成全局 `mat3 rot_yaw = mat3(cos(yaw), ...)` 在 Android (`opengl-es=yes`) 上编译失败。
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

## 手测清单

1. 打开文件名含 `180` 的左右并排鱼眼片，设置里开启 VR：默认应选中「180° 鱼眼」。
2. 拖动看四周，左右拖到头不应出现镜像或翻面；上下到 ±90° 停住。
3. 滚轮 / 双指缩放，视野限制在 40°–150°；鱼眼镜头角度可切 180/190/200。
4. 暂停后拖动仍实时更新；继续播放后硬解不中断（详情面板看 `hwdec-current`）。
5. 退出 VR，画面比例回到进入前的选择；框选填充不会被残留缩放影响。
6. 换下一集：视角回正、投影格式保留。

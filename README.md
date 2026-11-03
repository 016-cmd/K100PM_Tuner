# K100PM Tuner — 音质优化 Tuner APP（基于 ViPER4Android）
面向 **K100 Pro Max** 的伴生音质优化 Tuner 应用。它一方面与 **ViPER4Android** 风格音效驱动协作，
把卷积脉冲（Convolver）、DDC、均衡器等能力的参数、序列化与驱动下发封装成一套可靠、易用的调音界面；
另一方面提供**杜比调音、音乐播放、设备快照与频道管理**等独立工具，形成一套较完整的移动端音频调校工作台。
> 本工程**整体采用 GNU GPL v3.0** 授权。
---
## ✨ 主要功能
### 🎚️ 杜比调音引擎（Dolby Tuner）
- 读取并解析系统 **Dolby 模块** 的 XML 参数（`DolbyParams`、`DAX3 props`、频段偏移/基线解析）
- 在线实时调整杜比参数并写回系统，支持 **应用 / 恢复模块默认**
- 杜比预设的 **保存 / 加载 / 删除**，官方（Factory）预设检测与建议
### 🎛️ 音效会话（ViPER4Android 界面）
- ViPER4Android 音效页（`V4AScreen`）：卷积脉冲（Convolver）、DDC、均衡器、动态系统等音效开关与拉条
- 脉冲（Convolver）文件的 **导入 / 切换 / 删除**，AIDL 驱动批量路径下发
- 音频设备的 **快照记忆 / 自动切换 / 手动加载保存**（切设备自动恢复各自调音）
- 独立持久化：**SP 为参数真相源 + DB 存设备快照**，重启/退后台不丢值
### 🎵 音乐播放器
- 在线音乐搜索（网易云 / QQ / 酷我 / 酷狗官方搜索接口）+ **ExoPlayer** 流式播放
- 本地播放模式（`LocalModeScreen`）
- 收藏歌曲的本地数据库（`MusicDatabase` / `FavoriteSong`）
- 扩展媒体模式（`ExtModeScreen`）：附加系统媒体播放状态与元数据

### 🖥️ 系统界面与设置
- **液态玻璃（Liquid Glass）4-Tab 主框架**：主页 / 播放器 / 调音台 / 音效（Material 3 + backdrop 玻璃效果）
- 频道 Tuner 与 EQ 曲线可视化（`BandTunerSection` / `BandCurveCanvas`）
- 自定义卡片与模块状态面板（`ControlPanel` / `ModuleStatusCard` / `GlassCard`）
- 设置页（壁纸、外观等）
### 🔧 底层与稳定性
- **Root/驱动适配**：多种音效参数经 Root Shell / SHM / AIDL 会话下发到音频驱动
- 崩溃/ANR 捕获写入日志（离线排障），开机恢复服务
- 设备自动检测（`AudioOutputDetector`）
---
## 许可证
本工程（含代码、资源、文档）**基于 GNU General Public License v3.0（GPL-3.0）** 发布，
完整条款见 [`LICENSE`](./LICENSE)。
**为什么是 GPL-3.0？** 本工程中的**音效页 / 音效引擎（`com.k100pm.tuner.v4a2` 及其下层）**
是 ViPER4Android 的**衍生作品**，而所选上游均为 GPL-3.0 授权，因此本派生代码同样以 GPL-3.0 发布。
---
## 版权与派生声明
### 本工程自有部分（独立开发，同样置于 GPL-3.0）
- **杜比调音引擎**：`com.k100pm.tuner.service.*`（`DolbyTunerManager` 等——解析/写回系统 Dolby 参数与预设）
- **音乐播放器**：`com.k100pm.tuner.music.*`（在线搜索 / ExoPlayer 播放 / 收藏 / 本地与扩展模式）
- **主框架与界面**：`com.k100pm.tuner.ui.*`（4-Tab 液态玻璃框架、播放器页、频道 Tuner、设置、自定义组件）
- **音频链服务**：`com.k100pm.tuner.service`（`AudioChainService`、`DspManager` 等）
- **设备管理与声音数据层**：`com.k100pm.tuner.data.*`（设备快照/切换基础能力）
- **崩溃捕获、启动恢复、设备自动检测等运维能力**
### 音效引擎（V4A 衍生部分）
下列属于 **ViPER4Android 的衍生作品**，遵循上游 GPL-3.0 授权：
- 音效引擎与会话（`com.k100pm.tuner.v4a2.effect` / `...service` / `...audio` / `...viper`）
- 卷积脉冲（Convolver）、DDC、均衡器、动态系统等音效参数的模型、序列化与驱动下发
### 上游与致谢
| 组件 | 上游仓库 | 许可证 |
|---|---|---|
| App（ViPER4Android，Material 3 版） | https://github.com/likelikeslike/ViPER4Android | ✅ GPL-3.0 |
| Driver（ViPER4Android RE 驱动） | https://github.com/iscle/ViPER4Android-RE | ✅ GPL-3.0 |
| Driver Source（ViPERFX RE 驱动源码） | https://github.com/AndroidAudioMods/ViPERFX_RE | ⚠️ 无 LICENSE 文件，仅开源 |
> **关于 ViPERFX_RE**：该上游仓库当前未随附明确的 LICENSE 文件、其 README 也未声明许可证，
> 我们据其公开的开源属性与所在 V4A 生态引入并使用其思路。若作者（AndroidAudioMods / Martmists / Iscle 等）
> 主张相关著作权并希望我们停止使用，**请联系我们进行移除**，我们将立即按要求处理并更新本声明。

**原创致谢**：ViPER4Android 由社区与原作者（zhuhang / viper520 等社区成员）倾力维护与公开，
本工程基于社区维护的 RE 分支进行派生开发，特此致谢。
---
## 三方依赖致谢
本工程直接依赖以下主流开源库（均作为普通依赖引入），按其各自许可证致谢：
| 组件 | 用途 | 许可证 |
|---|---|---|
| AndroidX Compose（Compose BOM / Material3 / UI / Graphics / Tooling / Material-Icons / Animation） | 声明式 UI 框架 | ✅ Apache-2.0 |
| AndroidX Core / Lifecycle / Activity / Navigation | 基础能力与导航 | ✅ Apache-2.0 |
| Media3 ExoPlayer（exoplayer / hls / session） | 在线/本地音乐流式播放 | ✅ Apache-2.0 |
| AndroidX Media | 系统媒体通知样式 | ✅ Apache-2.0 |
| Room（runtime / ktx / compiler） | 本地数据库（预设/收藏/历史） | ✅ Apache-2.0 |
| Jetpack DataStore（preferences） | 音效偏好持久化（SP 真相源） | ✅ Apache-2.0 |
| KotlinX Coroutines | 异步与协程 | ✅ Apache-2.0 |
| Coil（coil-compose） | 壁纸图片加载 | ✅ Apache-2.0 |
| Gson | JSON 解析 | ✅ Apache-2.0 |
| Backdrop（Kyant0 / AndroidLiquidGlass，液态玻璃背景） | 液态玻璃视觉 | ✅ Apache-2.0 |
> **关于 Backdrop**：该组件（`io.github.kyant0:backdrop`）对应的开发仓库为
> [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass)，依据其 POM 声明采用 **Apache-2.0** 授权。
---
## 说明与免责
- 本工程为个人/社区音质优化用途，可能与系统音频架构深度耦合，请仅在具备必要知识的前提下使用。
- 你使用、修改、分发本工程时，须遵循 GPL-3.0 的条款，并保留本文件中的版权与来源声明。
- 若您对版权归属或本声明有任何疑问，欢迎通过仓库 Issues 联系我们。
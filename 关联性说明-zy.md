# 关联性说明（zy）

## 文档目的
该文档用于记录 APP 巡查流程中各功能模块的关联关系，确保修改一个功能时能同步评估并更新相关模块，避免出现功能回归。

## 当前任务背景（2026-04-21）
外场测试反馈了 3 个问题：
1. 巡查结束后返回菜单页，需要连续返回两次才能退出。
2. 速度常年显示 `0.0 km/h`，偶发异常值 `1111.4 km/h`。
3. 轨迹里程大多数为 `0 km`，仅一次 5 分钟测试显示 `2.27 km`。

## 功能关联关系
### 1) 巡查结束返回逻辑
- 入口：`MainMenuActivity` 打开 `InspectionActivity`。
- 结束：`InspectionActivity.finishInspection()` 负责结束巡查并跳回菜单。
- 关联风险：若结束时再次 `startActivity(MainMenuActivity)` 而不清理回退栈，会叠加多个菜单页实例，导致“需要返回两次”。

### 2) 速度显示逻辑
- 数据源：`LocationHelper` 通过 Fused + LocationManager 回调位置与速度。
- 展示层：`InspectionActivity` 读取回调并刷新 `speedText`。
- 关联风险：
  - GPS 速度在低速/弱信号场景常为 0。
  - 纯坐标差分在极短时间窗口下容易被定位抖动放大，产生极端异常值。
  - 不做精度过滤与平滑会导致速度显示忽高忽低。

### 3) 轨迹里程统计逻辑
- 采样：`InspectionActivity` 每次定位更新调用 `TrackRecorder.recordPoint()`。
- 上传：`TrackRecorder.stopAndUpload()` 上传轨迹点。
- 服务端落库：`server/api/routes.py -> /tracks` 计算并存储 `distance_km`。
- 关联风险：
  - 客户端采样周期过长会让短时巡查仅有 1 个点，里程为 0。
  - 客户端或服务端若不做漂移/跳点过滤，会出现里程异常偏大或偏小。

## 本次改动清单
### 安卓端
- `InspectionActivity`
  - 巡查结束跳转改为 `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`，避免菜单页重复入栈。
  - 速度计算增加定位精度门槛、时间窗门槛、最小位移门槛、最大速度门槛，并增加 3 点平滑窗口。
- `TrackRecorder`
  - 采样间隔从 10 秒调整为 3 秒，首点立即记录。
  - 新增漂移点过滤（<3m 不计）与异常跳点过滤（>120km/h 不计）。
  - 里程累计时同步应用过滤规则，提升统计稳定性。

### 服务端
- `server/api/routes.py`
  - 新增 `_calc_track_distance_km`，在 `/tracks` 入库前统一过滤漂移段与异常速度段，防止脏轨迹直接写入统计里程。

## 后续改动约束
1. 调整定位频率时，必须同步检查：
   - 客户端采样逻辑（`TrackRecorder`）；
   - 服务端里程计算逻辑（`/tracks`）。
2. 调整速度展示算法时，必须同步检查：
   - 速度阈值告警文案；
   - 轨迹里程过滤阈值，保持口径一致。
3. 修改巡查结束流程时，必须检查 Activity 回退栈行为，避免重复页面实例。

## 追加修复记录（2026-04-21 二次外测）
### 新反馈
1. 速度仍长期显示 `0 km/h`。
2. 步行 4 分钟里程仅 `0.03 km`。
3. 关键帧不上报/打不开。

### 关联性判断
- 速度与里程共享同一定位采样源，速度阈值过严会连带影响里程可信度判断。
- 关键帧链路包含 Android 采集与 Web 预览两端，任一端媒体类型处理不一致都会表现为“打不开”。

### 二次修复点
1. `InspectionActivity`
   - 放宽速度有效阈值：定位精度门槛、时间窗、最小位移。
   - 差分速度改为“回调时间差 + 经纬度距离”计算，避免 `location.time` 不稳定导致一直为 0。
   - 平滑算法改为优先均值非零速度，避免零值把真实速度长期压成 0。
2. `TrackRecorder`
   - 漂移阈值由 3 米调整为 0.8 米，避免低速步行被过度过滤。
3. `VideoRecorder`
   - 编码成功时也保留低频关键帧快照，确保 MP4 失败时仍可降级上传 JPEG 拼图。
   - 限制 MP4 体积，超限自动降级关键帧，减少上报失败。
   - 当缓冲为空但有当前帧时，也可生成单帧兜底图，避免“无视频字段”。
4. `server/templates/index.html`
   - 关键帧预览改为按真实 `blob.type` 动态展示 `img/video`，解决 MP4 被当图片导致打不开的问题。
   - 增加预览资源释放，避免 `ObjectURL` 内存泄漏。

## 追加修复记录（2026-04-21 三次外测）
### 新反馈
1. 轨迹时长正常（3分钟、5分钟），但速度仍显示 `0 km/h`。
2. 路程仍可能显示为 `0 km`。

### 关联性判断
- 速度显示优先级会影响巡查员现场反馈可信度，并间接影响对“里程是否异常”的人工判断。
- `LocationHelper` 已把设备位置对象的 `speed` 传给 `InspectionActivity`，但展示层若继续依赖二次 `hasSpeed` 判定，会把有效速度误判为 0。

### 本次修复点
1. `InspectionActivity`
   - 速度展示改为优先使用定位回调中的设备原生 `speed` 字段（m/s 转 km/h）。
   - 放宽速度有效精度门槛（80m -> 120m），减少弱信号下速度长期为 0 的概率。
2. `LocationHelper`
   - 在 Fused 定位外，新增 `LocationManager.GPS_PROVIDER` 并行持续更新（1s/0.5m）。
   - 解决“Fused回调长期返回缓存坐标，车上移动但轨迹仍为0”的场景。
3. `server/api/routes.py`
   - 在 `/tracks` 新增 `TRACK_DIAG` 结构化日志，打印点数量、里程、时长、首末点坐标及是否静止。
   - 用于快速区分是客户端未采到坐标变化，还是服务端距离计算异常。
4. `InspectionActivity`
   - 速度平滑策略升级为“5点中值滤波 + EMA指数平滑 + 单次变化限幅（2.5km/h）”。
   - 目标是降低车载场景下 `10km/h -> 4km/h` 的显示抖动，提升可读性。
5. `VideoRecorder`
   - MP4 上传策略改为“首次失败后延迟300ms重试一次，再降级关键帧”，减少偶发降级。
   - MP4 大小上限由 2MB 提升到 5MB，降低复杂画面下被误判“过大”导致的关键帧回退。
   - 增加 MP4 失败原因日志字段（如 `output_format_not_ready`、`encoded_buffer_empty`、`no_key_frame_in_buffer`），便于外测快速定位。
6. `server/templates/tracks.html`
   - 修复轨迹点击回放报错：不再依赖全局 `event.target`，改为显式传入点击元素 `this`。
   - 增加空轨迹点保护提示，避免点击后因 `path[0]` 为空导致前端异常。
7. `server/templates/tracks.html`
   - 新增 GPS(WGS84) 到高德(GCJ-02) 坐标转换（`AMap.convertFrom`），修复“人点落不到线路上”的偏移问题。
   - 回放动画改为复用已转换后的路径，保证“静态轨迹线”和“动态人点”使用同一坐标系。
8. `server/templates/tracks.html`
   - 坐标转换升级为“40点分段批量转换”，规避高德 `convertFrom` 单次点数限制导致的整段失败。
   - 单段转换失败时仅回退该段原始 GPS 点，并打印失败区间，避免全轨迹整体回退。
9. `server/templates/tracks.html`
   - 分段失败后新增“逐点转换”兜底流程，尽量保留可转换点，仅对失败点回退原始 GPS。
   - 增加单点失败日志（`index=...`），便于精确定位偏移来源点。
10. `server/templates/tracks.html`
   - 去除对 `AMap.convertFrom` 的在线依赖，改为前端本地 `WGS84 -> GCJ-02` 算法转换，避免整段/全段转换失败。
   - 对中国范围外坐标自动跳过偏移计算，保持原始坐标，避免海外点被错误偏移。
11. `TrackRecorder` + `InspectionActivity`
   - 轨迹采样新增精度门槛：定位精度大于 35 米时不记录轨迹点，减少“点落不到道路/线路偏移”。
   - `InspectionActivity` 记录轨迹时传入实时 `accuracy`，与速度展示使用同一定位质量来源。
12. `server/templates/tracks.html`
   - 轨迹回放页新增“上报点位叠加”：按当前轨迹的 `user_id + start_time + end_time` 拉取 `/api/reports` 并在地图标点。
   - 上报点与轨迹统一走本地 `WGS84 -> GCJ-02` 转换，确保点线坐标口径一致。
13. `server/api/routes.py`
   - 修复“生成今日日报实际生成昨天”的默认日期逻辑：`/daily-reports/generate` 在未传 `report_date` 时改为使用当天日期。
   - 保持手动传入 `report_date` 的行为不变，兼容补生成历史日报。
14. `server/templates/reports.html`
   - 修复“生成今日报告”按钮仍传昨天日期的问题：前端请求参数改为当天 `yyyy-MM-dd`。
   - 生成成功后自动刷新日期列表并选中当天，避免下拉仍停留在旧日期造成误判。
15. `server/templates/users.html`、`server/templates/reports.html`、`server/templates/tracks.html`
   - 统一管理端页面骨架样式（`max-width: 1400px`、导航栏风格、卡片圆角与阴影），修复菜单切页后宽度/视觉不一致。
   - 补充当前菜单高亮态，提升页面定位感。
   - 用户管理页表格增加横向滚动容器，避免小屏下布局挤压导致错位。
16. `server/templates/index.html`
   - 移除“当前用户”右侧重复导航按钮，仅保留退出按钮，避免顶部入口重复。
   - 审核筛选区固定按“上报时间”统计，移除时间依据下拉。
   - 用户筛选下拉排除管理员账号，仅保留采集用户。
   - 支持识别类型改为单行文本展示：`单体垃圾、占道经营`。
17. `server/api/routes.py` + `server/templates/tracks.html`
   - 巡查记录支持分页（每页10条）与筛选（采集用户、日期）。
   - 后端 `/tracks` 新增 `start_time/end_time` 查询条件，前端侧边栏新增上一页/下一页与页码信息。
18. `server/templates/reports.html`
   - 将“生成今日报告”与“选择日期”合并到同一工具栏行，减少纵向占用并统一操作区布局。
19. 上线前优化补充（2026-04-21）
   - `InspectionActivity` + `TrackRecorder`：新增可开关调试信息（长按速度文本开关），显示定位源、精度、轨迹点过滤结果，便于现场排障。
   - `server/templates/tracks.html`：新增“重置筛选”按钮，快速恢复巡查记录列表默认条件。
   - `server/templates/reports.html`：生成日报按钮增加防重复点击（生成中禁用 + 文案反馈 + 异常兜底恢复）。
   - `server/templates/index.html`：非管理员隐藏阈值设置与删除/审核入口，前端权限展示与后端鉴权口径保持一致。
20. `activity_main_menu.xml`（APP登录后首屏）
   - 优化首屏层级结构：顶部欢迎信息卡片 + 功能操作区 + 独立退出按钮，视觉更清晰。
   - 增加 `ScrollView` 与统一间距/按钮高度，提升不同屏幕尺寸下的稳定性与一致性。
21. `activity_login.xml`
   - 登录页样式与首页统一为卡片化结构：标题区 + 登录表单区，提升首屏视觉一致性。
   - 增加 `ScrollView` 适配小屏设备，统一输入框/按钮高度与间距，不改登录业务逻辑。
22. APP 图标优化
   - 更新 `ic_launcher_background.xml` 与 `ic_launcher_foreground.xml`，图标语义改为“定位针 + 楼宇 + 识别框”，贴合城市巡查识别业务。
   - 保持 Android 自适应图标结构不变（`mipmap-anydpi-v26/ic_launcher*.xml` 继续引用前景/背景矢量）。

### 后续联动约束
1. 若后续调整速度阈值或精度门槛，需同步验证：
   - `InspectionActivity` 速度展示；
   - `TrackRecorder` 采样过滤阈值；
   - 服务端轨迹里程过滤口径（`/tracks`）。
2. 若后续调整定位源策略，需同步验证：
   - `LocationHelper` 是否并行开启 GPS 原生定位；
   - 应用退出时是否执行 `removeUpdates`，避免定位监听泄漏。

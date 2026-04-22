# YOLOv8 城市管理智能识别系统

## 项目概述

基于 YOLOv8 的城市管理问题智能识别系统，实现移动端自动检测、上报，服务端审核管理。

## 功能模块

- **模型训练**: 单体垃圾、占道经营检测模型
- **Android端**: 用户登录、巡查检测、GPS定位、速度提示、视频录制、轨迹记录、自动上报
- **服务端**: 用户管理、API接口、Web审核后台、轨迹回放、每日统计报告

## 技术栈

| 模块 | 技术 |
|------|------|
| 模型训练 | Python + YOLOv8 |
| 移动端 | Android + Kotlin + ONNX Runtime + CameraX |
| 服务端 | Python + FastAPI + SQLite + APScheduler |
| 认证 | JWT Token |
| 地图服务 | 高德/百度API |

## 目录结构

```
├── model/           # 模型训练相关
├── server/          # 服务端代码
│   ├── api/         # API接口
│   ├── database/    # 数据模型
│   └── templates/   # Web页面
├── android/         # Android工程
└── docs/            # 文档
```

## 新增功能 (v2.0)

1. **用户系统**: 管理员/采集员角色区分，JWT认证
2. **APP主菜单**: 登录页、主菜单、巡查页分离
3. **速度提示**: GPS实时速度显示，超过15km/h红色警告
4. **视频录制**: 检测问题时自动录制5秒视频（前2秒缓存+后3秒）
5. **轨迹记录**: 每10秒记录GPS点，巡查结束时上传
6. **轨迹回放**: 后台地图展示巡查轨迹
7. **每日报告**: 自动生成统计报告（上报数量、类型分布、用户排行、巡查里程）

## 快速开始

### 服务端启动

```bash
cd server
pip install -r requirements.txt
python main.py
```

访问 http://localhost:8000 查看审核后台

**默认管理员账户**: admin / admin123

### Android端

1. 用 Android Studio 打开 android 目录
2. 修改 `ConfigManager.kt` 中的服务器地址
3. 运行安装

## API接口

| 接口 | 说明 |
|------|------|
| POST /api/auth/login | 用户登录 |
| POST /api/auth/register | 用户注册（管理员） |
| GET /api/users | 用户列表 |
| POST /api/reports | 上报问题 |
| GET /api/reports | 上报列表 |
| PUT /api/reports/{id}/review | 审核 |
| GET /api/videos/{id} | 获取视频 |
| POST /api/tracks | 上传轨迹 |
| GET /api/tracks | 轨迹列表 |
| GET /api/daily-reports | 每日报告 |
| GET /api/config/threshold | 阈值配置 |

## 上报数据格式

```json
{
  "image": "base64编码图片",
  "latitude": 30.5728,
  "longitude": 114.2793,
  "address": "XX路与XX路交叉口",
  "problem_type": "单体垃圾",
  "confidence": 0.87,
  "detect_time": "2026-04-17T14:30:00",
  "device_id": "Android-001",
  "video": "base64编码视频（可选）"
}
```

## 数据模型

| 表 | 说明 |
|------|------|
| users | 用户（管理员/采集员） |
| reports | 上报记录 |
| videos | 视频文件 |
| tracks | 巡查轨迹 |
| daily_reports | 每日统计报告 |
| system_config | 系统配置 |

## 后台页面

| 页面 | 地址 |
|------|------|
| 登录 | /login.html |
| 审核 | / |
| 用户管理 | /users.html |
| 轨迹回放 | /tracks.html |
| 每日报告 | /reports.html |
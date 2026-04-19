# YOLOv8 城市管理智能识别系统

## 项目概述

基于 YOLOv8 的城市管理问题智能识别系统，实现移动端自动检测、上报，服务端审核管理。

## 功能模块

- **模型训练**: 单体垃圾、占道经营检测模型
- **Android端**: 摄像头实时检测 + GPS定位 + 自动上报
- **服务端**: API接口 + Web审核后台

## 技术栈

| 模块 | 技术 |
|------|------|
| 模型训练 | Python + YOLOv8 |
| 移动端 | Android + Kotlin + ONNX Runtime |
| 服务端 | Python + FastAPI + SQLite |
| 地图服务 | 高德/百度API |

## 目录结构

```
├── model/           # 模型训练相关
├── server/          # 服务端代码
├── android/         # Android工程
└── docs/            # 文档
```

## 快速开始

### 服务端启动

```bash
cd server
pip install -r requirements.txt
python main.py
```

访问 http://localhost:8000 查看API文档

### Android端

1. 用 Android Studio 打开 android 目录
2. 配置服务器地址
3. 运行安装

## 上报数据格式

```json
{
  "image": "base64编码图片",
  "latitude": 30.5728,
  "longitude": 114.2793,
  "address": "XX路与XX路交叉口",
  "problem_type": "单体垃圾",
  "confidence": 0.87,
  "detect_time": "2026-04-17 14:30:00",
  "device_id": "设备唯一标识"
}
```
# 城市管理智能识别系统 - 部署指南

## 一、服务端部署

### 1. 环境要求
- Python 3.9+
- Windows/Linux/Mac

### 2. 安装依赖
```bash
cd server
pip install -r requirements.txt
```

### 3. 启动服务
```bash
python main.py
```

服务启动后：
- API文档: http://localhost:8000/docs
- 审核后台: http://localhost:8000

### 4. 测试接口
使用 Swagger 文档测试 API，或直接访问审核后台页面。

---

## 二、Android端部署

### 1. 环境要求
- Android Studio 2023+
- Android SDK 34
- JDK 17

### 2. 打开项目
用 Android Studio 打开 `android` 目录

### 3. 配置服务器地址
编辑 `ReportApi.kt`，修改 `serverUrl` 为实际服务器地址：
```kotlin
private val serverUrl = "http://你的服务器IP:8000"
```

### 4. 准备模型文件
模型文件已放置在 `android/app/src/main/assets/yolov8n.onnx`

如需重新导出模型：
```bash
cd model
python export_pretrained.py
# 导出的 yolov8n.onnx 会自动生成
# 复制到 android/app/src/main/assets/
```

### 5. 编译运行
点击 Android Studio 的 Run 按钮，安装到手机

---

## 三、模型训练（后续步骤）

当数据准备好后：

### 1. 数据准备
- 整理图片，确保无水印
- 使用 LabelImg 或 Roboflow 标注
- 导出为 YOLO 格式

### 2. 训练脚本
```python
from ultralytics import YOLO

# 加载预训练模型
model = YOLO("yolov8s.pt")

# 训练
model.train(
    data="datasets/data.yaml",
    epochs=100,
    imgsz=640
)

# 导出 TFLite
model.export(format="tflite")
```

### 3. 替换模型
将新模型复制到 Android assets 目录，替换原有文件

---

## 四、数据集格式

`datasets/data.yaml`:
```yaml
path: ./datasets
train: images/train
val: images/val

names:
  0: 单体垃圾
  1: 占道经营
```

---

## 五、常见问题

### Q: 服务端启动失败？
检查 Python 版本和依赖是否正确安装

### Q: Android 无法连接服务器？
1. 确保手机和服务器在同一网络
2. 检查服务器防火墙是否开放 8000 端口
3. Android 9+ 需要在 AndroidManifest.xml 中配置 `usesCleartextTraffic="true"`

### Q: 定位无法获取？
确保授予了定位权限，且手机 GPS 已开启
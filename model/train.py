import os
import sys

# 添加上级目录到路径
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ultralytics import YOLO

def train_model():
    """训练城市管理检测模型"""

    # 加载预训练模型 (建议使用 s 或 m 版本，平衡速度和精度)
    model = YOLO("yolov8s.pt")

    # 训练
    results = model.train(
        data="datasets/data.yaml",  # 数据集配置文件
        epochs=100,                 # 训练轮数
        imgsz=640,                  # 图像尺寸
        batch=16,                   # 批次大小
        name="city_detect",         # 实验名称
        device=0,                   # 使用 GPU (cpu 则改为 'cpu')
        patience=50,                # 早停耐心值
        save=True,                  # 保存模型
        plots=True                  # 绘制训练图表
    )

    print(f"训练完成！最佳模型保存在: {results.save_dir}")

def export_tflite():
    """导出 TFLite 模型用于 Android"""

    # 加载训练好的模型
    model = YOLO("runs/detect/city_detect/weights/best.pt")

    # 导出为 TFLite 格式
    model.export(
        format="tflite",
        imgsz=640,
        simplify=True
    )

    print("TFLite 模型导出完成！")
    print("文件位置: runs/detect/city_detect/weights/best.tflite")
    print("请将此文件复制到 Android 项目的 assets 目录")

if __name__ == "__main__":
    # 训练模型
    # train_model()

    # 导出 TFLite
    # export_tflite()

    print("请先准备数据集，然后取消注释上述函数执行")
"""
导出预训练 YOLOv8 模型为 ONNX 格式
用于 Android Demo 验证流程
"""

from ultralytics import YOLO

def export_pretrained_onnx():
    """导出预训练模型为 ONNX"""

    # 使用最小的 n 版本，适合移动端
    model = YOLO("yolov8n.pt")

    # 导出为 ONNX
    model.export(
        format="onnx",
        imgsz=640,
        simplify=True
    )

    print("导出完成！")
    print("文件位置: yolov8n.onnx")
    print("请将此文件复制到: android/app/src/main/assets/yolov8n.onnx")

if __name__ == "__main__":
    export_pretrained_onnx()
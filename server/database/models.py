from sqlalchemy import Column, Integer, String, Float, DateTime, Enum
from sqlalchemy.ext.declarative import declarative_base
from datetime import datetime
import enum

Base = declarative_base()

class ReportStatus(enum.Enum):
    PENDING = "pending"      # 待审核
    CONFIRMED = "confirmed"  # 已确认
    REJECTED = "rejected"    # 已作废

class Report(Base):
    """上报记录表"""
    __tablename__ = "reports"

    id = Column(Integer, primary_key=True, index=True)
    problem_type = Column(String(50), nullable=False, comment="问题类型")
    confidence = Column(Float, nullable=False, comment="置信度")
    latitude = Column(Float, nullable=False, comment="纬度")
    longitude = Column(Float, nullable=False, comment="经度")
    address = Column(String(200), comment="地址")
    image_path = Column(String(500), comment="图片存储路径")
    detect_time = Column(DateTime, comment="检测时间")
    device_id = Column(String(100), comment="设备ID")
    status = Column(Enum(ReportStatus), default=ReportStatus.PENDING, comment="状态")
    create_time = Column(DateTime, default=datetime.now, comment="创建时间")
    update_time = Column(DateTime, default=datetime.now, onupdate=datetime.now, comment="更新时间")
    reviewer = Column(String(50), comment="审核人")
    review_time = Column(DateTime, comment="审核时间")
    remark = Column(String(500), comment="备注")
from sqlalchemy import Column, Integer, String, Float, DateTime, Enum, Text, ForeignKey
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import relationship
from datetime import datetime
import enum

Base = declarative_base()

class ReportStatus(enum.Enum):
    PENDING = "pending"      # 待审核
    CONFIRMED = "confirmed"  # 已确认
    REJECTED = "rejected"    # 已作废

class UserRole(enum.Enum):
    ADMIN = "admin"          # 管理员
    COLLECTOR = "collector"  # 采集员

class User(Base):
    """用户表"""
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(50), unique=True, nullable=False, comment="用户名")
    password_hash = Column(String(128), nullable=False, comment="密码哈希")
    role = Column(Enum(UserRole), default=UserRole.COLLECTOR, comment="角色")
    name = Column(String(50), comment="姓名")
    phone = Column(String(20), comment="电话")
    create_time = Column(DateTime, default=datetime.now, comment="创建时间")

class Report(Base):
    """上报记录表"""
    __tablename__ = "reports"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), comment="上报用户ID")
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

    user = relationship("User", backref="reports")

class Video(Base):
    """视频表（关联上报记录）"""
    __tablename__ = "videos"

    id = Column(Integer, primary_key=True, index=True)
    report_id = Column(Integer, ForeignKey("reports.id"), nullable=False, comment="关联上报ID")
    video_path = Column(String(500), nullable=False, comment="视频存储路径")
    duration = Column(Integer, default=5, comment="视频时长(秒)")
    create_time = Column(DateTime, default=datetime.now, comment="创建时间")

    report = relationship("Report", backref="videos")

class Track(Base):
    """巡查轨迹表"""
    __tablename__ = "tracks"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    start_time = Column(DateTime, nullable=False, comment="开始时间")
    end_time = Column(DateTime, comment="结束时间")
    distance_km = Column(Float, default=0.0, comment="巡查里程(公里)")
    duration_min = Column(Integer, default=0, comment="巡查时长(分钟)")
    points_json = Column(Text, comment="轨迹点JSON")
    create_time = Column(DateTime, default=datetime.now, comment="创建时间")

    user = relationship("User", backref="tracks")

class DailyReport(Base):
    """每日统计报告表"""
    __tablename__ = "daily_reports"

    id = Column(Integer, primary_key=True, index=True)
    report_date = Column(String(10), unique=True, nullable=False, comment="报告日期 YYYY-MM-DD")
    stats_json = Column(Text, nullable=False, comment="统计数据JSON")
    create_time = Column(DateTime, default=datetime.now, comment="创建时间")

class SystemConfig(Base):
    """系统配置表"""
    __tablename__ = "system_config"

    key = Column(String(50), primary_key=True, comment="配置键")
    value = Column(String(200), nullable=False, comment="配置值")
    description = Column(String(200), comment="配置说明")
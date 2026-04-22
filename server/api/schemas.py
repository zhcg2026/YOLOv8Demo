from pydantic import BaseModel
from typing import Optional
from datetime import datetime
from enum import Enum

class ProblemType(str, Enum):
    GARBAGE = "单体垃圾"
    STALL = "占道经营"

class UserRole(str, Enum):
    ADMIN = "admin"
    COLLECTOR = "collector"

# ========== 用户相关 ==========

class UserLogin(BaseModel):
    """用户登录请求"""
    username: str
    password: str

class UserCreate(BaseModel):
    """用户创建请求"""
    username: str
    password: str
    role: UserRole = UserRole.COLLECTOR
    name: Optional[str] = None
    phone: Optional[str] = None

class UserResponse(BaseModel):
    """用户信息响应"""
    id: int
    username: str
    role: str
    name: Optional[str]
    phone: Optional[str]
    create_time: datetime

class TokenResponse(BaseModel):
    """登录token响应"""
    token: str
    user: UserResponse

# ========== 上报相关 ==========

class ReportCreate(BaseModel):
    """上报请求"""
    image: str  # base64编码图片
    latitude: float
    longitude: float
    address: Optional[str] = None
    problem_type: str
    confidence: float
    detect_time: datetime
    device_id: str
    video: Optional[str] = None  # base64编码视频(可选)

class ReportResponse(BaseModel):
    """上报响应"""
    id: int
    user_id: Optional[int]
    user_name: Optional[str]
    problem_type: str
    confidence: float
    latitude: float
    longitude: float
    address: Optional[str]
    image_url: str
    video_url: Optional[str]
    status: str
    create_time: datetime

    class Config:
        from_attributes = True

class ReportListResponse(BaseModel):
    """上报列表响应"""
    total: int
    items: list[ReportResponse]

class ReportReviewRequest(BaseModel):
    """审核请求"""
    status: str  # confirmed / rejected
    reviewer: Optional[str] = None
    remark: Optional[str] = None

class ReportStatsResponse(BaseModel):
    """各状态数量统计"""
    pending: int
    confirmed: int
    rejected: int
    total: int

class ReportBatchDeleteRequest(BaseModel):
    """批量删除"""
    ids: list[int]

class ReportDeleteOneRequest(BaseModel):
    """单条删除（POST，避免部分环境禁止 DELETE）"""
    id: int

class ThresholdResponse(BaseModel):
    """阈值配置响应"""
    value: float
    description: str

class ThresholdUpdateRequest(BaseModel):
    """阈值更新请求"""
    value: float

# ========== 轨迹相关 ==========

class TrackPoint(BaseModel):
    """轨迹点"""
    latitude: float
    longitude: float
    time: datetime

class TrackCreate(BaseModel):
    """轨迹上传请求"""
    start_time: datetime
    end_time: Optional[datetime] = None
    points: list[TrackPoint]

class TrackResponse(BaseModel):
    """轨迹响应"""
    id: int
    user_id: int
    user_name: Optional[str]
    start_time: datetime
    end_time: Optional[datetime]
    distance_km: float
    duration_min: int
    create_time: datetime

    class Config:
        from_attributes = True

class TrackListResponse(BaseModel):
    """轨迹列表响应"""
    total: int
    items: list[TrackResponse]

class TrackPointsResponse(BaseModel):
    """轨迹点响应"""
    track_id: int
    points: list[TrackPoint]

# ========== 每日报告相关 ==========

class DailyReportResponse(BaseModel):
    """每日报告响应"""
    id: int
    report_date: str
    stats: dict
    create_time: datetime

    class Config:
        from_attributes = True

class DailyReportListResponse(BaseModel):
    """每日报告列表响应"""
    total: int
    items: list[DailyReportResponse]


class SummaryReportResponse(BaseModel):
    """统计报表响应（按日/周/月）"""
    granularity: str
    anchor_date: str
    period_start: str
    period_end: str
    period_label: str
    stats: dict
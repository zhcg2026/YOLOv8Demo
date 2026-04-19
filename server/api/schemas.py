from pydantic import BaseModel
from typing import Optional
from datetime import datetime
from enum import Enum

class ProblemType(str, Enum):
    GARBAGE = "单体垃圾"
    STALL = "占道经营"

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

class ReportResponse(BaseModel):
    """上报响应"""
    id: int
    problem_type: str
    confidence: float
    latitude: float
    longitude: float
    address: Optional[str]
    image_url: str
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
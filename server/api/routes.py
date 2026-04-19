from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session
from sqlalchemy import func
from typing import Optional, Literal
import base64
import os
from datetime import datetime

from database import get_db
from database.models import Report, ReportStatus, SystemConfig
from api.schemas import (
    ReportCreate,
    ReportResponse,
    ReportListResponse,
    ReportReviewRequest,
    ReportStatsResponse,
    ReportBatchDeleteRequest,
    ReportDeleteOneRequest,
    ThresholdResponse,
    ThresholdUpdateRequest,
)

router = APIRouter()

# 图片存储目录
UPLOAD_DIR = "uploads"
os.makedirs(UPLOAD_DIR, exist_ok=True)

@router.post("/reports", response_model=ReportResponse)
async def create_report(report: ReportCreate, db: Session = Depends(get_db)):
    """接收上报数据"""
    # 检查置信度阈值
    threshold_config = db.query(SystemConfig).filter(SystemConfig.key == "confidence_threshold").first()
    if threshold_config:
        threshold = float(threshold_config.value)
        if report.confidence < threshold:
            raise HTTPException(
                status_code=400,
                detail=f"置信度 {report.confidence:.2f} 低于阈值 {threshold:.2f}，不予上报"
            )

    # 保存图片
    image_data = base64.b64decode(report.image)
    image_filename = f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_{report.device_id}.jpg"
    image_path = os.path.join(UPLOAD_DIR, image_filename)

    with open(image_path, "wb") as f:
        f.write(image_data)

    # 创建记录
    db_report = Report(
        problem_type=report.problem_type,
        confidence=report.confidence,
        latitude=report.latitude,
        longitude=report.longitude,
        address=report.address,
        image_path=image_path,
        detect_time=report.detect_time,
        device_id=report.device_id,
        status=ReportStatus.PENDING
    )
    db.add(db_report)
    db.commit()
    db.refresh(db_report)

    return ReportResponse(
        id=db_report.id,
        problem_type=db_report.problem_type,
        confidence=db_report.confidence,
        latitude=db_report.latitude,
        longitude=db_report.longitude,
        address=db_report.address,
        image_url=f"/api/images/{db_report.id}",
        status=db_report.status.value,
        create_time=db_report.create_time
    )

@router.get("/reports", response_model=ReportListResponse)
async def list_reports(
    status: Optional[str] = None,
    problem_type: Optional[str] = None,
    time_field: Literal["create", "detect"] = Query(
        "create",
        description="时间段字段：create=上报时间，detect=检测时间",
    ),
    start_time: Optional[datetime] = Query(None, description="起始时间（含）"),
    end_time: Optional[datetime] = Query(None, description="结束时间（含）"),
    page: int = 1,
    page_size: int = 20,
    db: Session = Depends(get_db),
):
    """获取上报列表（支持状态、类型、时间段）"""
    query = db.query(Report)

    if status:
        try:
            status_enum = ReportStatus(status)
        except ValueError:
            raise HTTPException(status_code=400, detail="无效的状态参数")
        query = query.filter(Report.status == status_enum)
    if problem_type:
        query = query.filter(Report.problem_type == problem_type)

    time_col = Report.create_time if time_field == "create" else Report.detect_time
    if time_field == "detect":
        query = query.filter(Report.detect_time.isnot(None))
    if start_time is not None and end_time is not None and start_time > end_time:
        raise HTTPException(status_code=400, detail="起始时间不能晚于结束时间")
    if start_time is not None:
        query = query.filter(time_col >= start_time)
    if end_time is not None:
        query = query.filter(time_col <= end_time)

    total = query.count()
    items = query.order_by(Report.create_time.desc()) \
        .offset((page - 1) * page_size) \
        .limit(page_size) \
        .all()

    return ReportListResponse(
        total=total,
        items=[
            ReportResponse(
                id=item.id,
                problem_type=item.problem_type,
                confidence=item.confidence,
                latitude=item.latitude,
                longitude=item.longitude,
                address=item.address,
                image_url=f"/api/images/{item.id}",
                status=item.status.value,
                create_time=item.create_time
            ) for item in items
        ]
    )


def _remove_report_image(report: Report) -> None:
    path = report.image_path
    if path and os.path.isfile(path):
        try:
            os.remove(path)
        except OSError:
            pass


def _compute_report_stats(db: Session) -> ReportStatsResponse:
    """按状态统计数量"""
    rows = (
        db.query(Report.status, func.count(Report.id))
        .group_by(Report.status)
        .all()
    )
    counts = {ReportStatus.PENDING: 0, ReportStatus.CONFIRMED: 0, ReportStatus.REJECTED: 0}
    for st, n in rows:
        if st in counts:
            counts[st] = int(n)
    total = int(sum(counts.values()))
    return ReportStatsResponse(
        pending=int(counts[ReportStatus.PENDING]),
        confirmed=int(counts[ReportStatus.CONFIRMED]),
        rejected=int(counts[ReportStatus.REJECTED]),
        total=total,
    )


@router.get("/report-stats", response_model=ReportStatsResponse)
async def report_stats_top(db: Session = Depends(get_db)):
    """各状态数量（独立路径，避免与 /reports/{id} 冲突导致 422）"""
    return _compute_report_stats(db)


@router.get("/reports/stats", response_model=ReportStatsResponse)
async def report_stats_nested(db: Session = Depends(get_db)):
    """同上，兼容旧前端"""
    return _compute_report_stats(db)


def _delete_report_by_id(db: Session, report_id: int) -> None:
    report = db.query(Report).filter(Report.id == report_id).first()
    if not report:
        raise HTTPException(status_code=404, detail="上报记录不存在")
    _remove_report_image(report)
    db.delete(report)
    db.commit()


@router.delete("/reports/{report_id}")
async def delete_report(report_id: int, db: Session = Depends(get_db)):
    """删除单条上报及本地图片（部分代理/环境可能返回 405，请优先用 POST /reports/remove-one）"""
    _delete_report_by_id(db, report_id)
    return {"message": "删除成功", "id": report_id}


@router.post("/reports/remove-one")
async def remove_one_report(body: ReportDeleteOneRequest, db: Session = Depends(get_db)):
    """单条删除（POST，避免 DELETE 被禁止时出现 405）"""
    _delete_report_by_id(db, body.id)
    return {"message": "删除成功", "id": body.id}


@router.post("/reports/batch-delete")
@router.post("/reports/batch-remove")
async def batch_delete_reports(body: ReportBatchDeleteRequest, db: Session = Depends(get_db)):
    """批量删除"""
    ids = list({i for i in body.ids if i > 0})
    if not ids:
        raise HTTPException(status_code=400, detail="请提供有效的记录 ID")
    reports = db.query(Report).filter(Report.id.in_(ids)).all()
    for r in reports:
        _remove_report_image(r)
        db.delete(r)
    db.commit()
    return {"message": "批量删除完成", "deleted": len(reports), "requested": len(ids)}


@router.get("/reports/{report_id}", response_model=ReportResponse)
async def get_report(report_id: int, db: Session = Depends(get_db)):
    """获取单条上报详情"""
    report = db.query(Report).filter(Report.id == report_id).first()
    if not report:
        raise HTTPException(status_code=404, detail="上报记录不存在")

    return ReportResponse(
        id=report.id,
        problem_type=report.problem_type,
        confidence=report.confidence,
        latitude=report.latitude,
        longitude=report.longitude,
        address=report.address,
        image_url=f"/api/images/{report.id}",
        status=report.status.value,
        create_time=report.create_time
    )

@router.put("/reports/{report_id}/review")
async def review_report(
    report_id: int,
    review: ReportReviewRequest,
    db: Session = Depends(get_db)
):
    """审核上报记录"""
    report = db.query(Report).filter(Report.id == report_id).first()
    if not report:
        raise HTTPException(status_code=404, detail="上报记录不存在")

    report.status = ReportStatus(review.status)
    report.reviewer = review.reviewer
    report.remark = review.remark
    report.review_time = datetime.now()
    db.commit()

    return {"message": "审核成功", "id": report_id, "status": report.status.value}

@router.get("/images/{report_id}")
async def get_image(report_id: int, db: Session = Depends(get_db)):
    """获取上报图片"""
    report = db.query(Report).filter(Report.id == report_id).first()
    if not report or not report.image_path:
        raise HTTPException(status_code=404, detail="图片不存在")

    return FileResponse(report.image_path)

@router.get("/config/threshold", response_model=ThresholdResponse)
async def get_threshold(db: Session = Depends(get_db)):
    """获取置信度阈值配置"""
    config = db.query(SystemConfig).filter(SystemConfig.key == "confidence_threshold").first()
    if not config:
        # 如果不存在，返回默认值
        return ThresholdResponse(value=0.5, description="上报置信度阈值，低于此值不上报")
    return ThresholdResponse(
        value=float(config.value),
        description=config.description or ""
    )

@router.put("/config/threshold", response_model=ThresholdResponse)
async def update_threshold(body: ThresholdUpdateRequest, db: Session = Depends(get_db)):
    """更新置信度阈值配置"""
    if body.value < 0.05 or body.value > 0.99:
        raise HTTPException(status_code=400, detail="阈值必须在 0.05 到 0.99 之间")

    config = db.query(SystemConfig).filter(SystemConfig.key == "confidence_threshold").first()
    if not config:
        config = SystemConfig(
            key="confidence_threshold",
            value=str(body.value),
            description="上报置信度阈值，低于此值不上报"
        )
        db.add(config)
    else:
        config.value = str(body.value)
    db.commit()

    return ThresholdResponse(
        value=float(config.value),
        description=config.description or ""
    )
from fastapi import APIRouter, Depends, HTTPException, Query, Header
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session
from sqlalchemy import func
from typing import Optional, Literal
import base64
import os
import json
from datetime import datetime, timedelta
import jwt
import bcrypt
import logging

from database import get_db
from database.models import Report, ReportStatus, SystemConfig, User, UserRole, Video, Track, DailyReport
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
    UserLogin,
    UserCreate,
    UserResponse,
    TokenResponse,
    TrackCreate,
    TrackResponse,
    TrackListResponse,
    TrackPointsResponse,
    TrackPoint,
    DailyReportResponse,
    DailyReportListResponse,
    SummaryReportResponse,
)

router = APIRouter()
logger = logging.getLogger("uvicorn.error")

# JWT配置
JWT_SECRET = "city_detect_secret_key_2026"
JWT_ALGORITHM = "HS256"
JWT_EXPIRE_HOURS = 24

# 图片/视频存储目录
UPLOAD_DIR = "uploads"
UPLOAD_IMAGES_DIR = os.path.join(UPLOAD_DIR, "images")
UPLOAD_VIDEOS_DIR = os.path.join(UPLOAD_DIR, "videos")
os.makedirs(UPLOAD_IMAGES_DIR, exist_ok=True)
os.makedirs(UPLOAD_VIDEOS_DIR, exist_ok=True)


# ========== 认证相关 ==========

def verify_password(plain_password: str, hashed_password: str) -> bool:
    return bcrypt.checkpw(plain_password.encode('utf-8'), hashed_password.encode('utf-8'))

def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode('utf-8'), bcrypt.gensalt()).decode('utf-8')

def create_token(user_id: int, username: str, role: str) -> str:
    expire = datetime.utcnow() + timedelta(hours=JWT_EXPIRE_HOURS)
    payload = {"user_id": user_id, "username": username, "role": role, "exp": expire}
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)

def decode_token(token: str) -> dict:
    try:
        return jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="Token已过期")
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=401, detail="无效Token")

def get_current_user(authorization: Optional[str] = Header(None), db: Session = Depends(get_db)) -> User:
    if not authorization:
        raise HTTPException(status_code=401, detail="未提供认证信息")
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="认证格式错误")
    token = authorization[7:]
    payload = decode_token(token)
    user = db.query(User).filter(User.id == payload["user_id"]).first()
    if not user:
        raise HTTPException(status_code=401, detail="用户不存在")
    return user

def require_admin(user: User = Depends(get_current_user)) -> User:
    if user.role != UserRole.ADMIN:
        raise HTTPException(status_code=403, detail="需要管理员权限")
    return user


@router.post("/auth/login", response_model=TokenResponse)
async def login(login_data: UserLogin, db: Session = Depends(get_db)):
    """用户登录"""
    user = db.query(User).filter(User.username == login_data.username).first()
    if not user:
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    if not verify_password(login_data.password, user.password_hash):
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    token = create_token(user.id, user.username, user.role.value)
    return TokenResponse(
        token=token,
        user=UserResponse(
            id=user.id,
            username=user.username,
            role=user.role.value,
            name=user.name,
            phone=user.phone,
            create_time=user.create_time
        )
    )


@router.post("/auth/register", response_model=UserResponse)
async def register(user_data: UserCreate, admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    """注册新用户（管理员权限）"""
    existing = db.query(User).filter(User.username == user_data.username).first()
    if existing:
        raise HTTPException(status_code=400, detail="用户名已存在")
    new_user = User(
        username=user_data.username,
        password_hash=hash_password(user_data.password),
        role=UserRole(user_data.role.value),
        name=user_data.name,
        phone=user_data.phone
    )
    db.add(new_user)
    db.commit()
    db.refresh(new_user)
    return UserResponse(
        id=new_user.id,
        username=new_user.username,
        role=new_user.role.value,
        name=new_user.name,
        phone=new_user.phone,
        create_time=new_user.create_time
    )


@router.get("/users/me", response_model=UserResponse)
async def get_me(user: User = Depends(get_current_user)):
    """获取当前用户信息"""
    return UserResponse(
        id=user.id,
        username=user.username,
        role=user.role.value,
        name=user.name,
        phone=user.phone,
        create_time=user.create_time
    )


@router.get("/users", response_model=list[UserResponse])
async def list_users(admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    """获取用户列表（管理员权限）"""
    users = db.query(User).all()
    return [
        UserResponse(
            id=u.id,
            username=u.username,
            role=u.role.value,
            name=u.name,
            phone=u.phone,
            create_time=u.create_time
        ) for u in users
    ]


@router.delete("/users/{user_id}")
async def delete_user(user_id: int, admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    """删除用户（管理员权限）"""
    if user_id == admin.id:
        raise HTTPException(status_code=400, detail="不能删除自己")
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
    db.delete(user)
    db.commit()
    return {"message": "删除成功", "id": user_id}


# ========== 上报相关 ==========

@router.post("/reports", response_model=ReportResponse)
async def create_report(
    report: ReportCreate,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    """接收上报数据（需要登录）"""
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
    image_filename = f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_{user.id}.jpg"
    image_path = os.path.join(UPLOAD_IMAGES_DIR, image_filename)

    with open(image_path, "wb") as f:
        f.write(image_data)

    # 创建记录
    db_report = Report(
        user_id=user.id,
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

    # 保存视频（如果有）- 实际为关键帧合成图片
    video_url = None
    if report.video:
        video_data = base64.b64decode(report.video)
        # 检测是否为JPEG（关键帧合成方案）或真实视频
        is_jpeg = video_data[:2] == b'\xff\xd8'  # JPEG magic number
        ext = "jpg" if is_jpeg else "mp4"
        video_filename = f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_{db_report.id}.{ext}"
        video_path = os.path.join(UPLOAD_VIDEOS_DIR, video_filename)
        with open(video_path, "wb") as f:
            f.write(video_data)
        db_video = Video(
            report_id=db_report.id,
            video_path=video_path,
            duration=5
        )
        db.add(db_video)
        db.commit()
        video_url = f"/api/videos/{db_report.id}"

    return ReportResponse(
        id=db_report.id,
        user_id=db_report.user_id,
        user_name=user.name or user.username,
        problem_type=db_report.problem_type,
        confidence=db_report.confidence,
        latitude=db_report.latitude,
        longitude=db_report.longitude,
        address=db_report.address,
        image_url=f"/api/images/{db_report.id}",
        video_url=video_url,
        status=db_report.status.value,
        create_time=db_report.create_time
    )


@router.get("/reports", response_model=ReportListResponse)
async def list_reports(
    status: Optional[str] = None,
    problem_type: Optional[str] = None,
    user_id: Optional[int] = None,
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
    """获取上报列表（支持状态、类型、用户、时间段）"""
    query = db.query(Report)

    if status:
        try:
            status_enum = ReportStatus(status)
        except ValueError:
            raise HTTPException(status_code=400, detail="无效的状态参数")
        query = query.filter(Report.status == status_enum)
    if problem_type:
        query = query.filter(Report.problem_type == problem_type)
    if user_id:
        query = query.filter(Report.user_id == user_id)

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
                user_id=item.user_id,
                user_name=item.user.name if item.user else None,
                problem_type=item.problem_type,
                confidence=item.confidence,
                latitude=item.latitude,
                longitude=item.longitude,
                address=item.address,
                image_url=f"/api/images/{item.id}",
                video_url=f"/api/videos/{item.id}" if db.query(Video).filter(Video.report_id == item.id).first() else None,
                status=item.status.value,
                create_time=item.create_time
            ) for item in items
        ]
    )


def _remove_report_files(report: Report, db: Session) -> None:
    # 删除图片
    if report.image_path and os.path.isfile(report.image_path):
        try:
            os.remove(report.image_path)
        except OSError:
            pass
    # 删除视频
    videos = db.query(Video).filter(Video.report_id == report.id).all()
    for v in videos:
        if v.video_path and os.path.isfile(v.video_path):
            try:
                os.remove(v.video_path)
            except OSError:
                pass
        db.delete(v)


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
    _remove_report_files(report, db)
    db.delete(report)
    db.commit()


@router.delete("/reports/{report_id}")
async def delete_report(report_id: int, db: Session = Depends(get_db)):
    """删除单条上报及本地图片/视频"""
    _delete_report_by_id(db, report_id)
    return {"message": "删除成功", "id": report_id}


@router.post("/reports/remove-one")
async def remove_one_report(body: ReportDeleteOneRequest, db: Session = Depends(get_db)):
    """单条删除（POST）"""
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
        _remove_report_files(r, db)
        db.delete(r)
    db.commit()
    return {"message": "批量删除完成", "deleted": len(reports), "requested": len(ids)}


@router.get("/report-summary", response_model=SummaryReportResponse)
@router.get("/reports/summary", response_model=SummaryReportResponse)
async def report_summary(
    granularity: Literal["day", "week", "month"] = Query("day"),
    anchor_date: Optional[str] = Query(None, description="锚点日期，格式 YYYY-MM-DD"),
    db: Session = Depends(get_db),
):
    """统计报表：支持按日/周/月聚合"""
    if anchor_date:
        try:
            anchor = datetime.strptime(anchor_date, "%Y-%m-%d")
        except ValueError:
            raise HTTPException(status_code=400, detail="anchor_date 格式错误，应为 YYYY-MM-DD")
    else:
        anchor = datetime.now()

    start_time, end_time, period_label = _period_range(granularity, anchor)
    stats = _build_summary_stats(db, start_time, end_time, period_label)
    return SummaryReportResponse(
        granularity=granularity,
        anchor_date=anchor.strftime("%Y-%m-%d"),
        period_start=start_time.strftime("%Y-%m-%d"),
        period_end=end_time.strftime("%Y-%m-%d"),
        period_label=period_label,
        stats=stats,
    )


@router.get("/reports/{report_id}", response_model=ReportResponse)
async def get_report(report_id: int, db: Session = Depends(get_db)):
    """获取单条上报详情"""
    report = db.query(Report).filter(Report.id == report_id).first()
    if not report:
        raise HTTPException(status_code=404, detail="上报记录不存在")

    return ReportResponse(
        id=report.id,
        user_id=report.user_id,
        user_name=report.user.name if report.user else None,
        problem_type=report.problem_type,
        confidence=report.confidence,
        latitude=report.latitude,
        longitude=report.longitude,
        address=report.address,
        image_url=f"/api/images/{report.id}",
        video_url=f"/api/videos/{report.id}" if db.query(Video).filter(Video.report_id == report.id).first() else None,
        status=report.status.value,
        create_time=report.create_time
    )


@router.put("/reports/{report_id}/review")
async def review_report(
    report_id: int,
    review: ReportReviewRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    """审核上报记录"""
    if user.role != UserRole.ADMIN:
        raise HTTPException(status_code=403, detail="需要管理员权限")
    report = db.query(Report).filter(Report.id == report_id).first()
    if not report:
        raise HTTPException(status_code=404, detail="上报记录不存在")

    report.status = ReportStatus(review.status)
    report.reviewer = user.name or user.username
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


@router.get("/videos/{report_id}")
async def get_video(report_id: int, db: Session = Depends(get_db)):
    """获取上报视频/关键帧合成图片"""
    video = db.query(Video).filter(Video.report_id == report_id).first()
    if not video or not video.video_path:
        raise HTTPException(status_code=404, detail="视频不存在")

    # 根据文件扩展名确定媒体类型
    if video.video_path.endswith('.jpg') or video.video_path.endswith('.jpeg'):
        return FileResponse(video.video_path, media_type="image/jpeg")
    else:
        return FileResponse(video.video_path, media_type="video/mp4")


# ========== 轨迹相关 ==========

def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """计算两点距离（公里）"""
    from math import radians, sin, cos, sqrt, atan2
    R = 6371.0  # 地球半径（公里）
    lat1_rad = radians(lat1)
    lat2_rad = radians(lat2)
    dlat = radians(lat2 - lat1)
    dlon = radians(lon2 - lon1)
    a = sin(dlat/2)**2 + cos(lat1_rad) * cos(lat2_rad) * sin(dlon/2)**2
    c = 2 * atan2(sqrt(a), sqrt(1-a))
    return R * c

@router.post("/tracks", response_model=TrackResponse)
async def create_track(
    track_data: TrackCreate,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    """上传巡查轨迹"""
    # 计算总距离
    total_distance = 0.0
    points = track_data.points
    for i in range(1, len(points)):
        total_distance += haversine_distance(
            points[i-1].latitude, points[i-1].longitude,
            points[i].latitude, points[i].longitude
        )

    # 计算时长（分钟）
    duration_min = 0
    if track_data.end_time and track_data.start_time:
        duration_min = int((track_data.end_time - track_data.start_time).total_seconds() / 60)

    # 轨迹诊断日志：用于定位“有时长但里程为0”的问题
    point_count = len(points)
    first_point = points[0] if point_count > 0 else None
    last_point = points[-1] if point_count > 0 else None
    stationary = (
        first_point is not None and
        last_point is not None and
        abs(first_point.latitude - last_point.latitude) < 1e-7 and
        abs(first_point.longitude - last_point.longitude) < 1e-7
    )
    logger.info(
        "TRACK_DIAG user_id=%s points=%s distance_km=%.4f duration_min=%s stationary=%s start=%s end=%s first=(%.6f,%.6f) last=(%.6f,%.6f)",
        user.id,
        point_count,
        total_distance,
        duration_min,
        stationary,
        track_data.start_time.isoformat() if track_data.start_time else None,
        track_data.end_time.isoformat() if track_data.end_time else None,
        first_point.latitude if first_point else 0.0,
        first_point.longitude if first_point else 0.0,
        last_point.latitude if last_point else 0.0,
        last_point.longitude if last_point else 0.0,
    )

    # 存储轨迹点JSON
    points_json = json.dumps([{
        "latitude": p.latitude,
        "longitude": p.longitude,
        "time": p.time.isoformat()
    } for p in points])

    db_track = Track(
        user_id=user.id,
        start_time=track_data.start_time,
        end_time=track_data.end_time,
        distance_km=round(total_distance, 2),
        duration_min=duration_min,
        points_json=points_json
    )
    db.add(db_track)
    db.commit()
    db.refresh(db_track)

    return TrackResponse(
        id=db_track.id,
        user_id=db_track.user_id,
        user_name=user.name or user.username,
        start_time=db_track.start_time,
        end_time=db_track.end_time,
        distance_km=db_track.distance_km,
        duration_min=db_track.duration_min,
        create_time=db_track.create_time
    )


@router.get("/tracks", response_model=TrackListResponse)
async def list_tracks(
    user_id: Optional[int] = None,
    start_time: Optional[datetime] = Query(None, description="轨迹开始时间（含）"),
    end_time: Optional[datetime] = Query(None, description="轨迹开始时间（含）结束边界"),
    page: int = 1,
    page_size: int = 20,
    db: Session = Depends(get_db)
):
    """获取轨迹列表"""
    query = db.query(Track)
    if user_id:
        query = query.filter(Track.user_id == user_id)
    if start_time is not None:
        query = query.filter(Track.start_time >= start_time)
    if end_time is not None:
        query = query.filter(Track.start_time <= end_time)

    total = query.count()
    items = query.order_by(Track.start_time.desc()) \
        .offset((page - 1) * page_size) \
        .limit(page_size) \
        .all()

    return TrackListResponse(
        total=total,
        items=[
            TrackResponse(
                id=t.id,
                user_id=t.user_id,
                user_name=t.user.name if t.user else None,
                start_time=t.start_time,
                end_time=t.end_time,
                distance_km=t.distance_km,
                duration_min=t.duration_min,
                create_time=t.create_time
            ) for t in items
        ]
    )


@router.get("/tracks/{track_id}/points", response_model=TrackPointsResponse)
async def get_track_points(track_id: int, db: Session = Depends(get_db)):
    """获取轨迹点（用于回放）"""
    track = db.query(Track).filter(Track.id == track_id).first()
    if not track:
        raise HTTPException(status_code=404, detail="轨迹不存在")

    points_data = json.loads(track.points_json) if track.points_json else []
    points = [
        TrackPoint(
            latitude=p["latitude"],
            longitude=p["longitude"],
            time=datetime.fromisoformat(p["time"])
        ) for p in points_data
    ]

    return TrackPointsResponse(track_id=track_id, points=points)


# ========== 每日报告相关 ==========

def generate_daily_report_data(db: Session, report_date: str) -> dict:
    """生成每日报告数据"""
    # 解析日期
    date_obj = datetime.strptime(report_date, "%Y-%m-%d")
    start_time = date_obj.replace(hour=0, minute=0, second=0)
    end_time = date_obj.replace(hour=23, minute=59, second=59)

    # 上报统计
    reports = db.query(Report).filter(
        Report.create_time >= start_time,
        Report.create_time <= end_time
    ).all()

    total_reports = len(reports)
    pending_count = sum(1 for r in reports if r.status == ReportStatus.PENDING)
    confirmed_count = sum(1 for r in reports if r.status == ReportStatus.CONFIRMED)
    rejected_count = sum(1 for r in reports if r.status == ReportStatus.REJECTED)

    # 按类型统计
    type_counts = {}
    for r in reports:
        type_counts[r.problem_type] = type_counts.get(r.problem_type, 0) + 1

    # 用户上报排行
    user_rank = {}
    for r in reports:
        if r.user_id:
            user_name = r.user.name if r.user else str(r.user_id)
            user_rank[user_name] = user_rank.get(user_name, 0) + 1
    user_rank_sorted = sorted(user_rank.items(), key=lambda x: -x[1])[:10]

    # 轨迹统计
    tracks = db.query(Track).filter(
        Track.start_time >= start_time,
        Track.start_time <= end_time
    ).all()
    total_distance = sum(t.distance_km for t in tracks)
    total_duration = sum(t.duration_min for t in tracks)

    return {
        "report_date": report_date,
        "total_reports": total_reports,
        "status_counts": {
            "pending": pending_count,
            "confirmed": confirmed_count,
            "rejected": rejected_count
        },
        "type_counts": type_counts,
        "user_rank": user_rank_sorted,
        "track_stats": {
            "total_distance_km": round(total_distance, 2),
            "total_duration_min": total_duration,
            "track_count": len(tracks)
        }
    }


def _period_range(granularity: Literal["day", "week", "month"], anchor: datetime) -> tuple[datetime, datetime, str]:
    anchor_day = anchor.replace(hour=0, minute=0, second=0, microsecond=0)
    if granularity == "day":
        start_time = anchor_day
        end_time = start_time + timedelta(days=1) - timedelta(seconds=1)
        period_label = start_time.strftime("%Y-%m-%d")
    elif granularity == "week":
        start_time = anchor_day - timedelta(days=anchor_day.weekday())
        end_time = start_time + timedelta(days=7) - timedelta(seconds=1)
        period_label = f"{start_time.strftime('%Y-%m-%d')} ~ {end_time.strftime('%Y-%m-%d')}"
    else:
        start_time = anchor_day.replace(day=1)
        if start_time.month == 12:
            next_month = start_time.replace(year=start_time.year + 1, month=1, day=1)
        else:
            next_month = start_time.replace(month=start_time.month + 1, day=1)
        end_time = next_month - timedelta(seconds=1)
        period_label = start_time.strftime("%Y-%m")
    return start_time, end_time, period_label


def _build_summary_stats(db: Session, start_time: datetime, end_time: datetime, period_label: str) -> dict:
    reports = db.query(Report).filter(
        Report.create_time >= start_time,
        Report.create_time <= end_time
    ).all()

    total_reports = len(reports)
    pending_count = sum(1 for r in reports if r.status == ReportStatus.PENDING)
    confirmed_count = sum(1 for r in reports if r.status == ReportStatus.CONFIRMED)
    rejected_count = sum(1 for r in reports if r.status == ReportStatus.REJECTED)

    type_counts = {}
    for r in reports:
        type_counts[r.problem_type] = type_counts.get(r.problem_type, 0) + 1

    user_rank = {}
    for r in reports:
        if r.user_id:
            user_name = r.user.name if r.user else str(r.user_id)
            user_rank[user_name] = user_rank.get(user_name, 0) + 1
    user_rank_sorted = sorted(user_rank.items(), key=lambda x: -x[1])[:10]

    tracks = db.query(Track).filter(
        Track.start_time >= start_time,
        Track.start_time <= end_time
    ).all()
    total_distance = sum(t.distance_km for t in tracks)
    total_duration = sum(t.duration_min for t in tracks)

    return {
        "report_date": period_label,
        "total_reports": total_reports,
        "status_counts": {
            "pending": pending_count,
            "confirmed": confirmed_count,
            "rejected": rejected_count
        },
        "type_counts": type_counts,
        "user_rank": user_rank_sorted,
        "track_stats": {
            "total_distance_km": round(total_distance, 2),
            "total_duration_min": total_duration,
            "track_count": len(tracks)
        }
    }


@router.get("/daily-reports", response_model=DailyReportListResponse)
async def list_daily_reports(
    page: int = 1,
    page_size: int = 30,
    db: Session = Depends(get_db)
):
    """获取每日报告列表"""
    query = db.query(DailyReport).order_by(DailyReport.report_date.desc())
    total = query.count()
    items = query.offset((page - 1) * page_size).limit(page_size).all()

    return DailyReportListResponse(
        total=total,
        items=[
            DailyReportResponse(
                id=r.id,
                report_date=r.report_date,
                stats=json.loads(r.stats_json) if r.stats_json else {},
                create_time=r.create_time
            ) for r in items
        ]
    )


@router.post("/daily-reports/generate", response_model=DailyReportResponse)
async def generate_report(
    report_date: Optional[str] = None,
    admin: User = Depends(require_admin),
    db: Session = Depends(get_db)
):
    """手动生成每日报告（管理员权限）"""
    if not report_date:
        report_date = datetime.now().strftime("%Y-%m-%d")

    # 检查是否已存在
    existing = db.query(DailyReport).filter(DailyReport.report_date == report_date).first()
    if existing:
        # 更新现有报告
        stats = generate_daily_report_data(db, report_date)
        existing.stats_json = json.dumps(stats)
        db.commit()
        db.refresh(existing)
        return DailyReportResponse(
            id=existing.id,
            report_date=existing.report_date,
            stats=stats,
            create_time=existing.create_time
        )

    # 创建新报告
    stats = generate_daily_report_data(db, report_date)
    new_report = DailyReport(
        report_date=report_date,
        stats_json=json.dumps(stats)
    )
    db.add(new_report)
    db.commit()
    db.refresh(new_report)

    return DailyReportResponse(
        id=new_report.id,
        report_date=new_report.report_date,
        stats=stats,
        create_time=new_report.create_time
    )


# ========== 配置相关 ==========

@router.get("/config/threshold", response_model=ThresholdResponse)
async def get_threshold(db: Session = Depends(get_db)):
    """获取置信度阈值配置"""
    config = db.query(SystemConfig).filter(SystemConfig.key == "confidence_threshold").first()
    if not config:
        return ThresholdResponse(value=0.5, description="上报置信度阈值，低于此值不上报")
    return ThresholdResponse(
        value=float(config.value),
        description=config.description or ""
    )


@router.put("/config/threshold", response_model=ThresholdResponse)
async def update_threshold(body: ThresholdUpdateRequest, admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    """更新置信度阈值配置（管理员权限）"""
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
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse
import os
from datetime import datetime, timedelta
import json

from database import init_db, SessionLocal
from api import router as api_router
from database.models import DailyReport, Report, ReportStatus, Track

app = FastAPI(title="城市管理智能识别系统", version="2.0.0")

# 挂载静态文件
if not os.path.exists("uploads"):
    os.makedirs("uploads")
if not os.path.exists("uploads/images"):
    os.makedirs("uploads/images")
if not os.path.exists("uploads/videos"):
    os.makedirs("uploads/videos")
app.mount("/uploads", StaticFiles(directory="uploads"), name="uploads")

# 注册API路由
app.include_router(api_router, prefix="/api")

# 静态页面路由
@app.get("/", response_class=HTMLResponse)
async def index():
    with open("templates/index.html", "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read(), headers={"Cache-Control": "no-store"})

@app.get("/login.html", response_class=HTMLResponse)
async def login_page():
    with open("templates/login.html", "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read(), headers={"Cache-Control": "no-store"})

@app.get("/users.html", response_class=HTMLResponse)
async def users_page():
    with open("templates/users.html", "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read(), headers={"Cache-Control": "no-store"})

@app.get("/tracks.html", response_class=HTMLResponse)
async def tracks_page():
    with open("templates/tracks.html", "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read(), headers={"Cache-Control": "no-store"})

@app.get("/reports.html", response_class=HTMLResponse)
async def reports_page():
    with open("templates/reports.html", "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read(), headers={"Cache-Control": "no-store"})


def generate_daily_report_task():
    """定时生成每日报告"""
    db = SessionLocal()
    try:
        # 生成昨天的报告
        yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")

        # 检查是否已存在
        existing = db.query(DailyReport).filter(DailyReport.report_date == yesterday).first()
        if existing:
            print(f"报告 {yesterday} 已存在，跳过")
            return

        # 解析日期
        date_obj = datetime.strptime(yesterday, "%Y-%m-%d")
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

        stats = {
            "report_date": yesterday,
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

        new_report = DailyReport(
            report_date=yesterday,
            stats_json=json.dumps(stats)
        )
        db.add(new_report)
        db.commit()
        print(f"已生成每日报告: {yesterday}")
    except Exception as e:
        print(f"生成每日报告失败: {e}")
    finally:
        db.close()


@app.on_event("startup")
async def startup():
    """应用启动时初始化数据库和定时任务"""
    init_db()
    print("数据库初始化完成")

    # 启动定时任务
    try:
        from apscheduler.schedulers.background import BackgroundScheduler
        scheduler = BackgroundScheduler()
        # 每天00:05执行
        scheduler.add_job(generate_daily_report_task, 'cron', hour=0, minute=5)
        scheduler.start()
        print("定时任务已启动：每天00:05生成每日报告")
    except ImportError:
        print("未安装apscheduler，定时任务未启动。请运行: pip install apscheduler")

    print("服务启动成功，访问 http://localhost:8000 查看审核后台")


@app.on_event("shutdown")
async def shutdown():
    """应用关闭时停止定时任务"""
    try:
        from apscheduler.schedulers.background import BackgroundScheduler
        for scheduler in BackgroundScheduler._instances:
            scheduler.shutdown()
    except:
        pass


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
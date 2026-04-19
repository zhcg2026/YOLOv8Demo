from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse
import os

from database import init_db
from api import router as api_router

app = FastAPI(title="城市管理智能识别系统", version="1.0.0")

# 挂载静态文件
if not os.path.exists("uploads"):
    os.makedirs("uploads")
app.mount("/uploads", StaticFiles(directory="uploads"), name="uploads")

# 注册API路由
app.include_router(api_router, prefix="/api")

# 静态页面路由
@app.get("/", response_class=HTMLResponse)
async def index():
    with open("templates/index.html", "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read(), headers={"Cache-Control": "no-store"})

@app.on_event("startup")
async def startup():
    """应用启动时初始化数据库"""
    init_db()
    print("数据库初始化完成")
    print("服务启动成功，访问 http://localhost:8000 查看审核后台")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
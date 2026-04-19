from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from .models import Base, SystemConfig

# SQLite数据库，方便Demo演示
DATABASE_URL = "sqlite:///./city_detect.db"

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

def init_db():
    """初始化数据库"""
    Base.metadata.create_all(bind=engine)

    # 初始化默认配置
    db = SessionLocal()
    try:
        threshold_config = db.query(SystemConfig).filter(SystemConfig.key == "confidence_threshold").first()
        if not threshold_config:
            threshold_config = SystemConfig(
                key="confidence_threshold",
                value="0.5",
                description="上报置信度阈值，低于此值不上报"
            )
            db.add(threshold_config)
            db.commit()
    finally:
        db.close()

def get_db():
    """获取数据库会话"""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
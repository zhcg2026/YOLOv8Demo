from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from .models import Base, SystemConfig, User, UserRole
import bcrypt

# SQLite数据库，方便Demo演示
DATABASE_URL = "sqlite:///./city_detect.db"

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

def hash_password(password: str) -> str:
    """使用bcrypt哈希密码"""
    return bcrypt.hashpw(password.encode('utf-8'), bcrypt.gensalt()).decode('utf-8')

def init_db():
    """初始化数据库"""
    Base.metadata.create_all(bind=engine)

    # 初始化默认配置和用户
    db = SessionLocal()
    try:
        # 置信度阈值配置
        threshold_config = db.query(SystemConfig).filter(SystemConfig.key == "confidence_threshold").first()
        if not threshold_config:
            threshold_config = SystemConfig(
                key="confidence_threshold",
                value="0.5",
                description="上报置信度阈值，低于此值不上报"
            )
            db.add(threshold_config)

        # 默认管理员账户
        admin_user = db.query(User).filter(User.username == "admin").first()
        if not admin_user:
            admin_user = User(
                username="admin",
                password_hash=hash_password("admin123"),
                role=UserRole.ADMIN,
                name="管理员",
                phone=""
            )
            db.add(admin_user)
            print("已创建默认管理员账户: admin / admin123")

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
import os
import tempfile

import pytest

# DB זמני ומבודד לכל הרצת בדיקות — לפני ייבוא האפליקציה
_tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
_tmp.close()
os.environ["DATABASE_URL"] = f"sqlite:///{_tmp.name}"

from fastapi.testclient import TestClient  # noqa: E402

from app.database import Base, engine  # noqa: E402
from app.main import app  # noqa: E402
from app.seed import seed_policies  # noqa: E402
from app.database import SessionLocal  # noqa: E402


@pytest.fixture(scope="session", autouse=True)
def _setup_db():
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        seed_policies(db)
    yield
    Base.metadata.drop_all(bind=engine)
    engine.dispose()  # לשחרר את קובץ ה-SQLite לפני מחיקה (Windows)
    try:
        os.unlink(_tmp.name)
    except PermissionError:
        pass


@pytest.fixture
def client():
    return TestClient(app)

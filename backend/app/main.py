from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse, RedirectResponse

from .database import Base, SessionLocal, engine
from .routers import admin, clients, devices, enrollment, policies, requests
from .seed import seed_policies

STATIC_DIR = Path(__file__).resolve().parent / "static"


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        seed_policies(db)
    yield


app = FastAPI(title="Sinun API", version="0.1.0", lifespan=lifespan)

app.include_router(admin.router)
app.include_router(clients.router)
app.include_router(policies.router)
app.include_router(enrollment.router)
app.include_router(devices.router)
app.include_router(requests.router)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/", include_in_schema=False)
def root():
    return RedirectResponse(url="/admin")


@app.get("/admin", include_in_schema=False)
def admin_panel():
    return FileResponse(STATIC_DIR / "admin.html")

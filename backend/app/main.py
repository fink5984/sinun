from contextlib import asynccontextmanager

from fastapi import FastAPI

from .database import Base, SessionLocal, engine
from .routers import devices, enrollment, requests
from .seed import seed_policies


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        seed_policies(db)
    yield


app = FastAPI(title="Sinun API", version="0.1.0", lifespan=lifespan)

app.include_router(enrollment.router)
app.include_router(devices.router)
app.include_router(requests.router)


@app.get("/health")
def health():
    return {"status": "ok"}

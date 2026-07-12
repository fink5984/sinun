from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from sqlalchemy import func
from sqlalchemy.orm import Session

from .. import models
from ..database import get_db

router = APIRouter(prefix="/api", tags=["admin"])


@router.get("/stats")
def dashboard_stats(db: Session = Depends(get_db)):
    total_devices = db.query(func.count(models.Device.id)).scalar() or 0
    unprotected = db.query(func.count(models.Device.id)).filter(
        models.Device.protection_status != "protected"
    ).scalar() or 0
    pending_requests = db.query(func.count(models.OpeningRequest.id)).filter(
        models.OpeningRequest.status == models.RequestStatus.pending
    ).scalar() or 0

    start_of_day = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    blocks_today = db.query(func.count(models.Event.id)).filter(
        models.Event.event_type == "block",
        models.Event.created_at >= start_of_day,
    ).scalar() or 0

    return {
        "devices": total_devices,
        "unprotected": unprotected,
        "pending_requests": pending_requests,
        "blocks_today": blocks_today,
    }


@router.get("/events")
def list_events(limit: int = 100, event_type: str | None = None, db: Session = Depends(get_db)):
    q = db.query(models.Event, models.Device.device_name).outerjoin(
        models.Device, models.Event.device_id == models.Device.id
    )
    if event_type:
        q = q.filter(models.Event.event_type == event_type)
    rows = q.order_by(models.Event.created_at.desc()).limit(min(limit, 500)).all()
    return [
        {
            "id": ev.id,
            "device_id": ev.device_id,
            "device_name": name or "—",
            "event_type": ev.event_type,
            "details": ev.details,
            "created_at": ev.created_at,
        }
        for ev, name in rows
    ]

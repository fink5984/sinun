from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..policy_engine import compile_for_device

router = APIRouter(prefix="/api/devices", tags=["devices"])

DEFAULT_POLICY_ID = "basic_kosher_001"


@router.post("/enroll", response_model=schemas.DeviceOut)
def enroll_device(payload: schemas.DeviceEnroll, db: Session = Depends(get_db)):
    """זרימת ההצטרפות של הלקוח: מזין קוד חד-פעמי → המכשיר נקשר למשתמש ול-policy."""
    code = db.get(models.EnrollmentCode, payload.code.strip().upper())
    if code is None:
        raise HTTPException(status_code=404, detail="invalid code")
    if not code.is_valid(datetime.now(timezone.utc)):
        raise HTTPException(status_code=409, detail="code already used or expired")

    policy_id = code.policy_id or (DEFAULT_POLICY_ID if db.get(models.Policy, DEFAULT_POLICY_ID) else None)
    device = models.Device(
        user_id=code.user_id,
        device_name=payload.device_name,
        android_version=payload.android_version,
        manufacturer=payload.manufacturer,
        model=payload.model,
        agent_version=payload.agent_version,
        last_seen=datetime.now(timezone.utc),
        policy_id=policy_id,
    )
    db.add(device)
    db.flush()  # כדי לקבל device.id לפני קישור הקוד
    code.used_by_device_id = device.id
    db.commit()
    db.refresh(device)
    return device


@router.post("/register", response_model=schemas.DeviceOut)
def register_device(payload: schemas.DeviceRegister, db: Session = Depends(get_db)):
    """רישום ישיר ללא קוד — לפיתוח/בדיקות בלבד (בפרודקשן משתמשים ב-enroll)."""
    device = models.Device(
        device_name=payload.device_name,
        android_version=payload.android_version,
        manufacturer=payload.manufacturer,
        model=payload.model,
        agent_version=payload.agent_version,
        last_seen=datetime.now(timezone.utc),
        policy_id=DEFAULT_POLICY_ID if db.get(models.Policy, DEFAULT_POLICY_ID) else None,
    )
    db.add(device)
    db.commit()
    db.refresh(device)
    return device


@router.post("/heartbeat", response_model=schemas.DeviceOut)
def heartbeat(payload: schemas.Heartbeat, db: Session = Depends(get_db)):
    device = db.get(models.Device, payload.device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    device.last_seen = datetime.now(timezone.utc)
    device.protection_status = payload.protection_status
    if payload.agent_version:
        device.agent_version = payload.agent_version
    db.commit()
    db.refresh(device)
    return device


@router.get("/{device_id}/policy", response_model=schemas.PolicyPayload)
def get_policy(device_id: str, db: Session = Depends(get_db)):
    device = db.get(models.Device, device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    if device.policy is None:
        raise HTTPException(status_code=404, detail="no policy assigned")
    return compile_for_device(device)


@router.post("/{device_id}/events", status_code=201)
def report_event(device_id: str, payload: schemas.EventIn, db: Session = Depends(get_db)):
    if db.get(models.Device, device_id) is None:
        raise HTTPException(status_code=404, detail="device not found")
    event = models.Event(device_id=device_id, event_type=payload.event_type, details=payload.details)
    db.add(event)
    db.commit()
    return {"id": event.id}


@router.get("", response_model=list[schemas.DeviceOut])
def list_devices(db: Session = Depends(get_db)):
    return db.query(models.Device).all()

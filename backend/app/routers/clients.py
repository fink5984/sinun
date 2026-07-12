"""ניהול לקוחות (users) — כל לקוח מקבץ מכשירים תחתיו, עם policy ברירת מחדל."""

from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from .. import models
from ..database import get_db
from ..routers.enrollment import _generate_code

router = APIRouter(prefix="/api/clients", tags=["clients"])

DEFAULT_POLICY_ID = "basic_kosher_001"


class ClientCreate(BaseModel):
    name: str
    phone: str | None = None


class DeviceBrief(BaseModel):
    id: str
    device_name: str
    protection_status: str
    policy_id: str | None
    last_seen: datetime | None
    model_config = {"from_attributes": True}


class ClientOut(BaseModel):
    id: str
    name: str
    phone: str | None
    status: str
    devices: list[DeviceBrief]
    model_config = {"from_attributes": True}


@router.get("", response_model=list[ClientOut])
def list_clients(db: Session = Depends(get_db)):
    return db.query(models.User).filter(models.User.role == "user").all()


@router.post("", response_model=ClientOut, status_code=201)
def create_client(payload: ClientCreate, db: Session = Depends(get_db)):
    client = models.User(name=payload.name, phone=payload.phone, role="user", status="active")
    db.add(client)
    db.commit()
    db.refresh(client)
    return client


class ClientCodeOut(BaseModel):
    code: str


@router.post("/{client_id}/enrollment-code", response_model=ClientCodeOut, status_code=201)
def create_client_code(client_id: str, db: Session = Depends(get_db)):
    """קוד הצטרפות שמשייך את המכשיר ללקוח הזה ול-policy ברירת המחדל."""
    if db.get(models.User, client_id) is None:
        raise HTTPException(status_code=404, detail="client not found")
    policy_id = DEFAULT_POLICY_ID if db.get(models.Policy, DEFAULT_POLICY_ID) else None
    code = models.EnrollmentCode(
        code=_generate_code(db),
        user_id=client_id,
        policy_id=policy_id,
        expires_at=datetime.now(timezone.utc) + timedelta(hours=72),
    )
    db.add(code)
    db.commit()
    return ClientCodeOut(code=code.code)

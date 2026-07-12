import secrets
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db

router = APIRouter(prefix="/api/enrollment", tags=["enrollment"])

# תווים ללא בלבול חזותי (בלי 0/O/1/I) — הלקוח מקליד ידנית
_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def _generate_code(db: Session) -> str:
    for _ in range(20):
        code = "".join(secrets.choice(_ALPHABET) for _ in range(8))
        if db.get(models.EnrollmentCode, code) is None:
            return code
    raise HTTPException(status_code=500, detail="failed to generate unique code")


@router.post("/codes", response_model=schemas.EnrollmentCodeOut, status_code=201)
def create_code(payload: schemas.EnrollmentCodeCreate, db: Session = Depends(get_db)):
    """המנהל מייצר קוד חד-פעמי בפאנל ומוסר אותו ללקוח."""
    if payload.policy_id and db.get(models.Policy, payload.policy_id) is None:
        raise HTTPException(status_code=404, detail="policy not found")

    expires_at = None
    if payload.expires_in_hours:
        expires_at = datetime.now(timezone.utc) + timedelta(hours=payload.expires_in_hours)

    code = models.EnrollmentCode(
        code=_generate_code(db),
        user_id=payload.user_id,
        policy_id=payload.policy_id,
        expires_at=expires_at,
    )
    db.add(code)
    db.commit()
    db.refresh(code)
    return code


@router.get("/codes", response_model=list[schemas.EnrollmentCodeOut])
def list_codes(db: Session = Depends(get_db)):
    return db.query(models.EnrollmentCode).order_by(models.EnrollmentCode.created_at.desc()).all()

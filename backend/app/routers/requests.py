from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db

router = APIRouter(prefix="/api/requests", tags=["requests"])


@router.post("", response_model=schemas.RequestOut, status_code=201)
def create_request(payload: schemas.RequestCreate, db: Session = Depends(get_db)):
    device = db.get(models.Device, payload.device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    req = models.OpeningRequest(
        device_id=payload.device_id,
        request_type=payload.request_type,
        target=payload.target,
        reason=payload.reason,
    )
    db.add(req)
    db.commit()
    db.refresh(req)
    return req


@router.get("", response_model=list[schemas.RequestOut])
def list_requests(status: str | None = None, db: Session = Depends(get_db)):
    q = db.query(models.OpeningRequest)
    if status:
        q = q.filter(models.OpeningRequest.status == status)
    return q.order_by(models.OpeningRequest.created_at.desc()).all()


@router.post("/{request_id}/approve", response_model=schemas.RequestOut)
def approve_request(request_id: str, payload: schemas.RequestDecision, db: Session = Depends(get_db)):
    req = _pending_request(request_id, db)
    req.status = models.RequestStatus.approved
    req.admin_note = payload.admin_note
    req.approved_minutes = payload.approved_minutes

    # אישור = הוספת rule ל-policy של המכשיר (זמני אם הוגדרו דקות)
    device = db.get(models.Device, req.device_id)
    if device is not None and device.policy_id is not None:
        expires = None
        if payload.approved_minutes:
            expires = datetime.now(timezone.utc) + timedelta(minutes=payload.approved_minutes)
        rule_type = models.RuleType.domain if req.request_type == "domain" else models.RuleType.package
        db.add(models.PolicyRule(
            policy_id=device.policy_id,
            rule_type=rule_type,
            value=req.target,
            action=models.RuleAction.allow,
            priority=10,  # אישורים ידניים גוברים על כללים כלליים
            expires_at=expires,
        ))

    db.commit()
    db.refresh(req)
    return req


@router.post("/{request_id}/reject", response_model=schemas.RequestOut)
def reject_request(request_id: str, payload: schemas.RequestDecision, db: Session = Depends(get_db)):
    req = _pending_request(request_id, db)
    req.status = models.RequestStatus.rejected
    req.admin_note = payload.admin_note
    db.commit()
    db.refresh(req)
    return req


def _pending_request(request_id: str, db: Session) -> models.OpeningRequest:
    req = db.get(models.OpeningRequest, request_id)
    if req is None:
        raise HTTPException(status_code=404, detail="request not found")
    if req.status != models.RequestStatus.pending:
        raise HTTPException(status_code=409, detail=f"request already {req.status.value}")
    return req

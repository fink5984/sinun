from datetime import datetime, timezone
import hashlib
import secrets

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..policy_engine import compile_for_device, effective_app_actions

router = APIRouter(prefix="/api/devices", tags=["devices"])

DEFAULT_POLICY_ID = "basic_kosher_001"

# עדיפויות override פר-מכשיר: נמוך גובר. חסימה/פתיחה ידנית ממתג האפליקציות.
_APP_OVERRIDE_PRIORITY = 15


def _device_rules(device: models.Device) -> list[models.PolicyRule]:
    """כל הכללים החלים על המכשיר: גלובליים (מה-policy) + override פר-מכשיר."""
    rules = list(device.override_rules)
    if device.policy is not None:
        rules += list(device.policy.rules)
    return rules


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


@router.get("/{device_id}")
def device_details(device_id: str, db: Session = Depends(get_db)):
    """פרטי מכשיר מלאים לפאנל: חומרה, בעלים, וכללי override פר-מכשיר."""
    device = db.get(models.Device, device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    owner = db.get(models.User, device.user_id) if device.user_id else None
    return {
        "id": device.id,
        "device_name": device.device_name,
        "owner_name": owner.name if owner else None,
        "owner_id": device.user_id,
        "android_version": device.android_version,
        "manufacturer": device.manufacturer,
        "model": device.model,
        "agent_version": device.agent_version,
        "protection_status": device.protection_status,
        "policy_id": device.policy_id,
        "last_seen": device.last_seen,
        "override_rules": [
            {
                "id": r.id,
                "rule_type": r.rule_type.value,
                "value": r.value,
                "action": r.action.value,
                "expires_at": r.expires_at,
            }
            for r in device.override_rules
        ],
    }


@router.delete("/{device_id}", status_code=204)
def delete_device(device_id: str, db: Session = Depends(get_db)):
    """מחיקת מכשיר וכל הנתונים הקשורים אליו (כללי override, אירועים, בקשות)."""
    device = db.get(models.Device, device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    db.query(models.Event).filter(models.Event.device_id == device_id).delete()
    db.query(models.OpeningRequest).filter(models.OpeningRequest.device_id == device_id).delete()
    db.query(models.EnrollmentCode).filter(
        models.EnrollmentCode.used_by_device_id == device_id
    ).update({models.EnrollmentCode.used_by_device_id: None})
    db.delete(device)
    db.commit()


@router.post("/{device_id}/uninstall-code")
def generate_uninstall_code(device_id: str, db: Session = Depends(get_db)):
    """המנהל מייצר קוד חד-פעמי להסרת האפליקציה. תקף 30 דקות."""
    device = db.get(models.Device, device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    # קוד 6 ספרות
    code = f"{secrets.randbelow(1_000_000):06d}"
    device.uninstall_code_hash = hashlib.sha256(code.encode()).hexdigest()
    from datetime import timedelta
    device.uninstall_code_expires_at = datetime.now(timezone.utc) + timedelta(minutes=30)
    db.commit()
    return {"code": code, "expires_minutes": 30}


@router.post("/{device_id}/verify-uninstall")
def verify_uninstall_code(device_id: str, payload: schemas.UninstallVerify, db: Session = Depends(get_db)):
    """האפליקציה מאמתת את הקוד שהמשתמש הזין. אם תקין — מנקה ומחזיר authorized=true."""
    device = db.get(models.Device, device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    if device.uninstall_code_hash is None or device.uninstall_code_expires_at is None:
        raise HTTPException(status_code=409, detail="no uninstall code issued")
    if datetime.now(timezone.utc) > device.uninstall_code_expires_at:
        device.uninstall_code_hash = None
        device.uninstall_code_expires_at = None
        db.commit()
        raise HTTPException(status_code=410, detail="code expired")
    expected = hashlib.sha256(payload.code.strip().encode()).hexdigest()
    if expected != device.uninstall_code_hash:
        raise HTTPException(status_code=403, detail="invalid code")
    # קוד נכון — נוקה מיד (חד-פעמי)
    device.uninstall_code_hash = None
    device.uninstall_code_expires_at = None
    db.commit()
    return {"authorized": True}


# ===================== מלאי אפליקציות פר-מכשיר =====================

@router.post("/{device_id}/apps", status_code=200)
def report_apps(device_id: str, payload: schemas.AppInventory, db: Session = Depends(get_db)):
    """ה-agent מדווח את רשימת האפליקציות המותקנות. הרשימה מסונכרנת במלואה:
    אפליקציות חדשות נוספות, קיימות מתעדכנות, ומה שהוסר מהמכשיר נמחק מהמלאי."""
    if db.get(models.Device, device_id) is None:
        raise HTTPException(status_code=404, detail="device not found")

    now = datetime.now(timezone.utc)
    reported = {a.package_name: a for a in payload.apps}
    existing = {
        row.package_name: row
        for row in db.query(models.DeviceApp).filter(models.DeviceApp.device_id == device_id).all()
    }

    for pkg, app in reported.items():
        row = existing.get(pkg)
        if row is None:
            db.add(models.DeviceApp(
                device_id=device_id,
                package_name=pkg,
                app_name=app.app_name,
                is_system=app.is_system,
                installer=app.installer,
                first_seen=now,
                last_seen=now,
            ))
        else:
            row.app_name = app.app_name or row.app_name
            row.is_system = app.is_system
            row.installer = app.installer or row.installer
            row.last_seen = now

    # אפליקציות שכבר לא מותקנות — הסרה מהמלאי (שמירה על עדכניות)
    for pkg, row in existing.items():
        if pkg not in reported:
            db.delete(row)

    db.commit()
    return {"count": len(reported)}


@router.get("/{device_id}/apps", response_model=list[schemas.DeviceAppOut])
def list_device_apps(
    device_id: str,
    include_system: bool = False,
    db: Session = Depends(get_db),
):
    """רשימת האפליקציות שעל המכשיר עם הסטטוס האפקטיבי (פתוח/חסום) של כל אחת."""
    device = db.get(models.Device, device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")

    actions = effective_app_actions(_device_rules(device))
    device_override = {
        r.value
        for r in device.override_rules
        if r.rule_type == models.RuleType.package
    }

    q = db.query(models.DeviceApp).filter(models.DeviceApp.device_id == device_id)
    if not include_system:
        q = q.filter(models.DeviceApp.is_system.is_(False))
    apps = q.order_by(models.DeviceApp.app_name.is_(None), models.DeviceApp.app_name).all()

    out: list[schemas.DeviceAppOut] = []
    for a in apps:
        action = actions.get(a.package_name)
        status = "blocked" if action == "block" else "allowed"
        if a.package_name in device_override:
            source = "device"
        elif action is not None:
            source = "policy"
        else:
            source = "default"
        out.append(schemas.DeviceAppOut(
            package_name=a.package_name,
            app_name=a.app_name,
            is_system=a.is_system,
            status=status,
            source=source,
            last_seen=a.last_seen,
        ))
    return out


class AppStatusIn(BaseModel):
    action: str  # allow / block


@router.post("/{device_id}/apps/{package_name}/status")
def set_device_app_status(
    device_id: str,
    package_name: str,
    payload: AppStatusIn,
    db: Session = Depends(get_db),
):
    """מתג פתוח/חסום לאפליקציה בודדת במכשיר — יוצר/מסיר override פר-מכשיר.
    'block' → כלל חסימה פר-מכשיר. 'allow' → הסרת החסימה (וביטול חסימה גלובלית
    ע"י כלל allow פר-מכשיר אם צריך)."""
    device = db.get(models.Device, device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    if payload.action not in ("allow", "block"):
        raise HTTPException(status_code=422, detail="action must be allow or block")

    # מנקים override קיים לאותה חבילה (מתחילים נקי)
    db.query(models.PolicyRule).filter(
        models.PolicyRule.device_id == device_id,
        models.PolicyRule.rule_type == models.RuleType.package,
        models.PolicyRule.value == package_name,
    ).delete()

    if payload.action == "block":
        db.add(models.PolicyRule(
            device_id=device_id,
            rule_type=models.RuleType.package,
            value=package_name,
            action=models.RuleAction.block,
            priority=_APP_OVERRIDE_PRIORITY,
        ))
    else:
        # אם ה-policy הגלובלי חוסם את החבילה — צריך כלל allow פר-מכשיר שיגבר עליו.
        globally_blocked = device.policy is not None and any(
            r.rule_type == models.RuleType.package
            and r.action == models.RuleAction.block
            and r.value == package_name
            for r in device.policy.rules
        )
        if globally_blocked:
            db.add(models.PolicyRule(
                device_id=device_id,
                rule_type=models.RuleType.package,
                value=package_name,
                action=models.RuleAction.allow,
                priority=_APP_OVERRIDE_PRIORITY,
            ))

    db.commit()
    return {"package_name": package_name, "action": payload.action}


@router.get("/{device_id}/requests", response_model=list[schemas.RequestOut])
def device_requests(device_id: str, status: str | None = None, db: Session = Depends(get_db)):
    """בקשות הפתיחה של מכשיר מסוים (לתצוגה במסך המכשיר בפאנל)."""
    q = db.query(models.OpeningRequest).filter(models.OpeningRequest.device_id == device_id)
    if status:
        q = q.filter(models.OpeningRequest.status == status)
    return q.order_by(models.OpeningRequest.created_at.desc()).all()


@router.get("/{device_id}/events")
def device_events(device_id: str, limit: int = 50, event_type: str | None = None, db: Session = Depends(get_db)):
    """אירועי החסימה של מכשיר מסוים."""
    q = db.query(models.Event).filter(models.Event.device_id == device_id)
    if event_type:
        q = q.filter(models.Event.event_type == event_type)
    rows = q.order_by(models.Event.created_at.desc()).limit(min(limit, 200)).all()
    return [
        {"id": e.id, "event_type": e.event_type, "details": e.details, "created_at": e.created_at}
        for e in rows
    ]

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from .. import models
from ..database import get_db

router = APIRouter(prefix="/api/policies", tags=["policies"])


class PolicyOut(BaseModel):
    id: str
    name: str
    model_config = {"from_attributes": True}


class BlockedAppIn(BaseModel):
    package_name: str


@router.get("", response_model=list[PolicyOut])
def list_policies(db: Session = Depends(get_db)):
    return db.query(models.Policy).all()


@router.get("/{policy_id}/blocked-apps", response_model=list[str])
def list_blocked_apps(policy_id: str, db: Session = Depends(get_db)):
    rules = db.query(models.PolicyRule).filter(
        models.PolicyRule.policy_id == policy_id,
        models.PolicyRule.rule_type == models.RuleType.package,
        models.PolicyRule.action == models.RuleAction.block,
    ).all()
    return [r.value for r in rules]


@router.post("/{policy_id}/blocked-apps", status_code=201)
def block_app(policy_id: str, payload: BlockedAppIn, db: Session = Depends(get_db)):
    if db.get(models.Policy, policy_id) is None:
        raise HTTPException(status_code=404, detail="policy not found")
    pkg = payload.package_name.strip()
    exists = db.query(models.PolicyRule).filter(
        models.PolicyRule.policy_id == policy_id,
        models.PolicyRule.rule_type == models.RuleType.package,
        models.PolicyRule.action == models.RuleAction.block,
        models.PolicyRule.value == pkg,
    ).first()
    if exists is None:
        db.add(models.PolicyRule(
            policy_id=policy_id,
            rule_type=models.RuleType.package,
            value=pkg,
            action=models.RuleAction.block,
            priority=20,
        ))
        db.commit()
    return {"package_name": pkg}


@router.delete("/{policy_id}/blocked-apps/{package_name}", status_code=204)
def unblock_app(policy_id: str, package_name: str, db: Session = Depends(get_db)):
    db.query(models.PolicyRule).filter(
        models.PolicyRule.policy_id == policy_id,
        models.PolicyRule.rule_type == models.RuleType.package,
        models.PolicyRule.action == models.RuleAction.block,
        models.PolicyRule.value == package_name,
    ).delete()
    db.commit()

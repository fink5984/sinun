from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from .. import models
from ..database import get_db
from ..policy_engine import compile_policy

router = APIRouter(prefix="/api/policies", tags=["policies"])


class PolicyOut(BaseModel):
    id: str
    name: str
    description: str | None = None
    default_action: str
    rules_count: int = 0
    model_config = {"from_attributes": True}


class BlockedAppIn(BaseModel):
    package_name: str


class DomainIn(BaseModel):
    domain: str
    action: str = "block"  # allow / block


class DomainOut(BaseModel):
    domain: str
    action: str


@router.get("", response_model=list[PolicyOut])
def list_policies(db: Session = Depends(get_db)):
    policies = db.query(models.Policy).all()
    return [
        PolicyOut(
            id=p.id,
            name=p.name,
            description=p.description,
            default_action=p.default_action.value,
            rules_count=len(p.rules),
        )
        for p in policies
    ]


@router.get("/{policy_id}/compiled")
def get_compiled_policy(policy_id: str, db: Session = Depends(get_db)):
    """תצוגת ה-policy המקומפל (כפי שה-agent צורך) — לתצוגה בפאנל."""
    policy = db.get(models.Policy, policy_id)
    if policy is None:
        raise HTTPException(status_code=404, detail="policy not found")
    return compile_policy(policy)


@router.get("/{policy_id}/domains", response_model=list[DomainOut])
def list_domains(policy_id: str, db: Session = Depends(get_db)):
    rules = db.query(models.PolicyRule).filter(
        models.PolicyRule.policy_id == policy_id,
        models.PolicyRule.rule_type == models.RuleType.domain,
    ).all()
    return [DomainOut(domain=r.value, action=r.action.value) for r in rules]


@router.post("/{policy_id}/domains", status_code=201)
def add_domain(policy_id: str, payload: DomainIn, db: Session = Depends(get_db)):
    if db.get(models.Policy, policy_id) is None:
        raise HTTPException(status_code=404, detail="policy not found")
    if payload.action not in ("allow", "block"):
        raise HTTPException(status_code=422, detail="action must be allow or block")
    domain = payload.domain.strip().lower()
    if not domain:
        raise HTTPException(status_code=422, detail="domain required")
    action = models.RuleAction(payload.action)
    exists = db.query(models.PolicyRule).filter(
        models.PolicyRule.policy_id == policy_id,
        models.PolicyRule.rule_type == models.RuleType.domain,
        models.PolicyRule.value == domain,
    ).first()
    if exists is not None:
        exists.action = action
    else:
        db.add(models.PolicyRule(
            policy_id=policy_id,
            rule_type=models.RuleType.domain,
            value=domain,
            action=action,
            priority=30,
        ))
    db.commit()
    return {"domain": domain, "action": payload.action}


@router.delete("/{policy_id}/domains/{domain}", status_code=204)
def remove_domain(policy_id: str, domain: str, db: Session = Depends(get_db)):
    db.query(models.PolicyRule).filter(
        models.PolicyRule.policy_id == policy_id,
        models.PolicyRule.rule_type == models.RuleType.domain,
        models.PolicyRule.value == domain,
    ).delete()
    db.commit()


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

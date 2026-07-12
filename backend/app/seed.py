"""טעינת ה-policy הידני מ-policies/*.json לתוך ה-DB בעליית השרת (אם לא קיים)."""

import json
from pathlib import Path

from sqlalchemy.orm import Session

from . import models

POLICIES_DIR = Path(__file__).resolve().parent.parent / "policies"


def seed_policies(db: Session) -> None:
    for path in sorted(POLICIES_DIR.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        policy_id = data["policy_id"]
        if db.get(models.Policy, policy_id) is not None:
            continue

        policy = models.Policy(
            id=policy_id,
            name=data.get("name", policy_id),
            description=data.get("description"),
            default_action=models.RuleAction(data.get("default_network_action", "block")),
        )
        db.add(policy)

        for domain in data.get("allowed_domains", []):
            db.add(models.PolicyRule(
                policy_id=policy_id, rule_type=models.RuleType.domain,
                value=domain, action=models.RuleAction.allow,
            ))
        for domain in data.get("blocked_domains", []):
            db.add(models.PolicyRule(
                policy_id=policy_id, rule_type=models.RuleType.domain,
                value=domain, action=models.RuleAction.block,
            ))
        for app in data.get("allowed_apps", []):
            db.add(models.PolicyRule(
                policy_id=policy_id, rule_type=models.RuleType.package,
                value=app["package_name"], action=models.RuleAction.allow,
                extra={"signature_sha256": app.get("signature_sha256")},
            ))
        for app in data.get("blocked_apps", []):
            db.add(models.PolicyRule(
                policy_id=policy_id, rule_type=models.RuleType.package,
                value=app["package_name"], action=models.RuleAction.block,
            ))

        db.commit()

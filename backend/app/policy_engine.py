"""קומפילציה של policy + rules לפורמט ה-JSON שה-agent צורך."""

import zlib
from datetime import datetime, timezone

from .models import Policy, PolicyRule, RuleAction, RuleType
from .schemas import AllowedApp, BlockedApp, PolicyPayload


def _active(rule: PolicyRule) -> bool:
    if rule.expires_at is None:
        return True
    expires = rule.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    return expires > datetime.now(timezone.utc)


def compile_policy(policy: Policy) -> PolicyPayload:
    allowed_domains: list[str] = []
    blocked_domains: list[str] = []
    allowed_apps: list[AllowedApp] = []
    blocked_apps: list[BlockedApp] = []

    rules = sorted((r for r in policy.rules if _active(r)), key=lambda r: r.priority)
    for rule in rules:
        if rule.rule_type == RuleType.domain:
            (allowed_domains if rule.action == RuleAction.allow else blocked_domains).append(rule.value)
        elif rule.rule_type == RuleType.package:
            if rule.action == RuleAction.allow:
                sig = (rule.extra or {}).get("signature_sha256")
                allowed_apps.append(AllowedApp(package_name=rule.value, signature_sha256=sig))
            else:
                blocked_apps.append(BlockedApp(package_name=rule.value))

    payload = PolicyPayload(
        policy_id=policy.id,
        version=0,
        default_network_action=policy.default_action.value,
        default_app_action=policy.default_action.value,
        allowed_domains=allowed_domains,
        blocked_domains=blocked_domains,
        allowed_apps=allowed_apps,
        blocked_apps=blocked_apps,
        support={"allow_opening_requests": True, "temporary_opening_minutes": 30},
    )
    # גרסה דטרמיניסטית מהתוכן — ה-agent משווה כדי לדעת אם צריך לרענן cache
    payload.version = zlib.crc32(payload.model_dump_json(exclude={"version"}).encode())
    return payload

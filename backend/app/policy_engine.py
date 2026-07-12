"""קומפילציה של policy גלובלי + override פר-מכשיר לפורמט ה-JSON שה-agent צורך."""

import zlib
from datetime import datetime, timezone

from .models import Device, Policy, PolicyRule, RuleAction, RuleType
from .schemas import AllowedApp, BlockedApp, PolicyPayload


def _active(rule: PolicyRule) -> bool:
    if rule.expires_at is None:
        return True
    expires = rule.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    return expires > datetime.now(timezone.utc)


def compile_for_device(device: Device) -> PolicyPayload:
    """כללי המכשיר (override, priority נמוך = גובר) מתמזגים מעל כללי ה-policy הגלובלי."""
    policy = device.policy
    if policy is None:
        raise ValueError("device has no policy")

    global_rules = list(policy.rules)
    override_rules = list(device.override_rules)
    return _compile(policy, global_rules + override_rules)


def compile_policy(policy: Policy) -> PolicyPayload:
    """קומפילציה של policy גלובלי בלבד (ללא הקשר מכשיר) — לתצוגה בפאנל."""
    return _compile(policy, list(policy.rules))


def effective_app_actions(rules: list[PolicyRule]) -> dict[str, str]:
    """מכריע פעולה יחידה לכל package לפי priority (נמוך גובר). כלל override פר-מכשיר
    (priority נמוך) גובר על כלל גלובלי — כך מתג פתוח/חסום בפאנל עובד נכון."""
    decision: dict[str, str] = {}
    for rule in sorted((r for r in rules if _active(r)), key=lambda r: r.priority):
        if rule.rule_type == RuleType.package and rule.value not in decision:
            decision[rule.value] = rule.action.value
    return decision


def _compile(policy: Policy, rules: list[PolicyRule]) -> PolicyPayload:
    allowed_domains: list[str] = []
    blocked_domains: list[str] = []
    allowed_apps: list[AllowedApp] = []
    blocked_apps: list[BlockedApp] = []

    active_rules = [r for r in rules if _active(r)]

    # דומיינים: כל הכללים הפעילים (המנוע בצד ה-agent מכריע לפי specificity).
    for rule in sorted(active_rules, key=lambda r: r.priority):
        if rule.rule_type == RuleType.domain:
            (allowed_domains if rule.action == RuleAction.allow else blocked_domains).append(rule.value)

    # אפליקציות: פעולה יחידה לכל package (override פר-מכשיר גובר על גלובלי).
    app_actions = effective_app_actions(active_rules)
    app_sig = {
        r.value: (r.extra or {}).get("signature_sha256")
        for r in active_rules
        if r.rule_type == RuleType.package and r.action == RuleAction.allow
    }
    for pkg, action in app_actions.items():
        if action == RuleAction.allow.value:
            allowed_apps.append(AllowedApp(package_name=pkg, signature_sha256=app_sig.get(pkg)))
        else:
            blocked_apps.append(BlockedApp(package_name=pkg))

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

import enum
import uuid
from datetime import datetime, timezone

from sqlalchemy import JSON, DateTime, Enum, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base


def _uuid() -> str:
    return str(uuid.uuid4())


def _now() -> datetime:
    return datetime.now(timezone.utc)


class RuleType(str, enum.Enum):
    domain = "domain"
    package = "package"
    app_signature = "app_signature"
    category = "category"
    time_limit = "time_limit"
    url_pattern = "url_pattern"


class RuleAction(str, enum.Enum):
    allow = "allow"
    block = "block"


class RequestStatus(str, enum.Enum):
    pending = "pending"
    approved = "approved"
    rejected = "rejected"


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    name: Mapped[str] = mapped_column(String(120))
    phone: Mapped[str | None] = mapped_column(String(32))
    role: Mapped[str] = mapped_column(String(20), default="user")  # user / admin
    status: Mapped[str] = mapped_column(String(20), default="active")

    devices: Mapped[list["Device"]] = relationship(back_populates="user")


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    user_id: Mapped[str | None] = mapped_column(ForeignKey("users.id"))
    device_name: Mapped[str] = mapped_column(String(120))
    android_version: Mapped[str | None] = mapped_column(String(20))
    manufacturer: Mapped[str | None] = mapped_column(String(60))
    model: Mapped[str | None] = mapped_column(String(60))
    agent_version: Mapped[str | None] = mapped_column(String(20))
    last_seen: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    protection_status: Mapped[str] = mapped_column(String(20), default="unknown")  # protected / unprotected / unknown
    policy_id: Mapped[str | None] = mapped_column(ForeignKey("policies.id"))
    # קוד חד-פעמי להסרת אפליקציית הסינון (מאוחסן כ-SHA-256)
    uninstall_code_hash: Mapped[str | None] = mapped_column(String(64))
    uninstall_code_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    user: Mapped[User | None] = relationship(back_populates="devices")
    policy: Mapped["Policy | None"] = relationship()
    # כללי override פר-מכשיר (פתיחה "ברמת המשתמש") — גוברים על ה-policy הגלובלי
    override_rules: Mapped[list["PolicyRule"]] = relationship(
        primaryjoin="Device.id == PolicyRule.device_id",
        cascade="all, delete-orphan",
    )


class Policy(Base):
    __tablename__ = "policies"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(120))
    description: Mapped[str | None] = mapped_column(Text)
    default_action: Mapped[RuleAction] = mapped_column(Enum(RuleAction), default=RuleAction.block)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

    rules: Mapped[list["PolicyRule"]] = relationship(
        back_populates="policy",
        cascade="all, delete-orphan",
        primaryjoin="Policy.id == PolicyRule.policy_id",
    )


class PolicyRule(Base):
    __tablename__ = "policy_rules"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    # כלל שייך ל-policy גלובלי (חל על כל המכשירים איתו) או ל-override פר-מכשיר.
    policy_id: Mapped[str | None] = mapped_column(ForeignKey("policies.id"))
    device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id"))  # None = כלל גלובלי
    rule_type: Mapped[RuleType] = mapped_column(Enum(RuleType))
    value: Mapped[str] = mapped_column(String(500))  # domain / package name / pattern
    action: Mapped[RuleAction] = mapped_column(Enum(RuleAction))
    priority: Mapped[int] = mapped_column(Integer, default=100)
    extra: Mapped[dict | None] = mapped_column(JSON)  # e.g. {"signature_sha256": "..."}
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))  # לאישורים זמניים

    policy: Mapped[Policy | None] = relationship(back_populates="rules")


class App(Base):
    __tablename__ = "apps"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    package_name: Mapped[str] = mapped_column(String(255), index=True)
    app_name: Mapped[str | None] = mapped_column(String(255))
    signing_cert_sha256: Mapped[str | None] = mapped_column(String(64))
    installer: Mapped[str | None] = mapped_column(String(255))
    version_code: Mapped[int | None] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(20), default="unknown")  # approved / blocked / unknown


class DeviceApp(Base):
    """מלאי האפליקציות המותקנות פר-מכשיר. ה-agent מדווח את הרשימה המלאה
    כל כמה דקות; הפאנל מציג אותה עם מתג פתוח/חסום לכל אפליקציה."""

    __tablename__ = "device_apps"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    package_name: Mapped[str] = mapped_column(String(255), index=True)
    app_name: Mapped[str | None] = mapped_column(String(255))
    is_system: Mapped[bool] = mapped_column(default=False)
    installer: Mapped[str | None] = mapped_column(String(255))
    first_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    last_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)


class OpeningRequest(Base):
    __tablename__ = "requests"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"))
    request_type: Mapped[str] = mapped_column(String(20))  # domain / app
    target: Mapped[str] = mapped_column(String(500))
    reason: Mapped[str | None] = mapped_column(Text)
    status: Mapped[RequestStatus] = mapped_column(Enum(RequestStatus), default=RequestStatus.pending)
    admin_note: Mapped[str | None] = mapped_column(Text)
    approved_minutes: Mapped[int | None] = mapped_column(Integer)  # None = קבוע
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)


class EnrollmentCode(Base):
    """קוד חד-פעמי שהמנהל מייצר ומוסר ללקוח. הלקוח מזין אותו באפליקציה כדי לקשור
    את המכשיר למשתמש ול-policy הנכונים."""

    __tablename__ = "enrollment_codes"

    code: Mapped[str] = mapped_column(String(12), primary_key=True)
    user_id: Mapped[str | None] = mapped_column(ForeignKey("users.id"))
    policy_id: Mapped[str | None] = mapped_column(ForeignKey("policies.id"))
    used_by_device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id"))
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

    def is_valid(self, now: datetime) -> bool:
        if self.used_by_device_id is not None:
            return False
        if self.expires_at is None:
            return True
        expires = self.expires_at
        if expires.tzinfo is None:
            expires = expires.replace(tzinfo=timezone.utc)
        return expires > now


class Event(Base):
    __tablename__ = "events"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"))
    event_type: Mapped[str] = mapped_column(String(60), index=True)  # block / vpn_down / permission_revoked / ...
    details: Mapped[dict | None] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

from datetime import datetime

from pydantic import BaseModel


# --- Devices ---

class DeviceRegister(BaseModel):
    device_name: str
    android_version: str | None = None
    manufacturer: str | None = None
    model: str | None = None
    agent_version: str | None = None


class DeviceOut(BaseModel):
    id: str
    device_name: str
    protection_status: str
    policy_id: str | None
    last_seen: datetime | None

    model_config = {"from_attributes": True}


class Heartbeat(BaseModel):
    device_id: str
    protection_status: str  # protected / unprotected
    agent_version: str | None = None


# --- Policy (הפורמט שה-agent צורך) ---

class AllowedApp(BaseModel):
    package_name: str
    signature_sha256: str | None = None
    action: str = "allow"


class BlockedApp(BaseModel):
    package_name: str
    action: str = "block"


class PolicyPayload(BaseModel):
    policy_id: str
    version: int
    default_network_action: str
    default_app_action: str
    allowed_domains: list[str]
    blocked_domains: list[str]
    allowed_apps: list[AllowedApp]
    blocked_apps: list[BlockedApp]
    support: dict


# --- Events ---

class EventIn(BaseModel):
    event_type: str
    details: dict | None = None


# --- Requests ---

class RequestCreate(BaseModel):
    device_id: str
    request_type: str  # domain / app
    target: str
    reason: str | None = None


class RequestDecision(BaseModel):
    admin_note: str | None = None
    approved_minutes: int | None = None  # None = אישור קבוע


class RequestOut(BaseModel):
    id: str
    device_id: str
    request_type: str
    target: str
    reason: str | None
    status: str
    admin_note: str | None
    created_at: datetime

    model_config = {"from_attributes": True}

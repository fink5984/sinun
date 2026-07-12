"""בדיקות מקצה-לקצה על ה-flow המרכזי: enrollment, policy, בקשות, שכבות policy."""

POLICY_ID = "basic_kosher_001"


def test_health(client):
    assert client.get("/health").json() == {"status": "ok"}


def test_seeded_policy_is_default_deny(client):
    dev = client.post("/api/devices/register", json={"device_name": "seed-check"}).json()
    policy = client.get(f"/api/devices/{dev['id']}/policy").json()
    assert policy["default_network_action"] == "block"
    assert policy["default_app_action"] == "block"
    assert "google.com" in policy["allowed_domains"]
    assert "example-bad-site.com" in policy["blocked_domains"]


def test_enrollment_code_flow(client):
    # המנהל מייצר קוד → הלקוח מזין → המכשיר נקשר ל-policy
    code = client.post("/api/enrollment/codes", json={"policy_id": POLICY_ID}).json()["code"]
    assert len(code) == 8

    dev = client.post("/api/devices/enroll", json={"code": code, "device_name": "Client Phone"}).json()
    assert dev["policy_id"] == POLICY_ID

    # קוד חד-פעמי: שימוש שני נכשל
    reuse = client.post("/api/devices/enroll", json={"code": code, "device_name": "Second"})
    assert reuse.status_code == 409


def test_enroll_with_invalid_code(client):
    resp = client.post("/api/devices/enroll", json={"code": "NOPE9999", "device_name": "x"})
    assert resp.status_code == 404


def test_heartbeat_updates_protection_status(client):
    dev = client.post("/api/devices/register", json={"device_name": "hb"}).json()
    hb = client.post("/api/devices/heartbeat", json={
        "device_id": dev["id"], "protection_status": "protected",
    }).json()
    assert hb["protection_status"] == "protected"


def test_event_reporting(client):
    dev = client.post("/api/devices/register", json={"device_name": "ev"}).json()
    resp = client.post(f"/api/devices/{dev['id']}/events", json={
        "event_type": "block", "details": {"domain": "bad.example"},
    })
    assert resp.status_code == 201


def test_device_scoped_approval_does_not_leak_to_others(client):
    """אישור 'ברמת המשתמש' (device) משפיע רק על המכשיר המבקש, לא על אחרים."""
    dev_a = client.post("/api/devices/register", json={"device_name": "A"}).json()
    dev_b = client.post("/api/devices/register", json={"device_name": "B"}).json()

    req = client.post("/api/requests", json={
        "device_id": dev_a["id"], "request_type": "domain",
        "target": "user-only.example", "reason": "test",
    }).json()
    approved = client.post(f"/api/requests/{req['id']}/approve", json={"scope": "device"}).json()
    assert approved["status"] == "approved"

    policy_a = client.get(f"/api/devices/{dev_a['id']}/policy").json()
    policy_b = client.get(f"/api/devices/{dev_b['id']}/policy").json()
    assert "user-only.example" in policy_a["allowed_domains"]
    assert "user-only.example" not in policy_b["allowed_domains"]  # לא דלף למכשיר אחר


def test_global_approval_applies_to_all_devices(client):
    """אישור 'ברמת הסינון' (global) חל על כל המכשירים עם אותו policy."""
    dev_a = client.post("/api/devices/register", json={"device_name": "GA"}).json()
    dev_b = client.post("/api/devices/register", json={"device_name": "GB"}).json()

    req = client.post("/api/requests", json={
        "device_id": dev_a["id"], "request_type": "domain",
        "target": "everyone.example", "reason": "test",
    }).json()
    client.post(f"/api/requests/{req['id']}/approve", json={"scope": "global"})

    policy_b = client.get(f"/api/devices/{dev_b['id']}/policy").json()
    assert "everyone.example" in policy_b["allowed_domains"]  # דלף לכולם — כמצופה


def test_reject_request(client):
    dev = client.post("/api/devices/register", json={"device_name": "rej"}).json()
    req = client.post("/api/requests", json={
        "device_id": dev["id"], "request_type": "app",
        "target": "com.bad.app", "reason": "no",
    }).json()
    rejected = client.post(f"/api/requests/{req['id']}/reject", json={"admin_note": "denied"}).json()
    assert rejected["status"] == "rejected"

    # לא ניתן לאשר בקשה שכבר טופלה
    again = client.post(f"/api/requests/{req['id']}/approve", json={})
    assert again.status_code == 409


def test_policy_version_changes_after_approval(client):
    """ה-agent מזהה policy חדש לפי version — אישור חייב לשנות אותו."""
    dev = client.post("/api/devices/register", json={"device_name": "ver"}).json()
    v1 = client.get(f"/api/devices/{dev['id']}/policy").json()["version"]

    req = client.post("/api/requests", json={
        "device_id": dev["id"], "request_type": "domain",
        "target": "fresh.example", "reason": "t",
    }).json()
    client.post(f"/api/requests/{req['id']}/approve", json={"scope": "device"})

    v2 = client.get(f"/api/devices/{dev['id']}/policy").json()["version"]
    assert v1 != v2

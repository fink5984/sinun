from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse

from .database import Base, SessionLocal, engine
from .routers import admin, clients, devices, enrollment, policies, requests
from .seed import seed_policies

STATIC_DIR = Path(__file__).resolve().parent / "static"

_BLOCK_PAGE_TEMPLATE = """\
<!DOCTYPE html>
<html lang="he" dir="rtl">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>גישה חסומה — Sinun</title>
  <style>
    :root{{--bg:#0d1117;--surface:#161b22;--border:#30363d;--text:#e6edf3;--muted:#8b949e;--red:#f85149;--blue:#58a6ff;--green:#3fb950;}}
    *{{box-sizing:border-box;margin:0;padding:0;}}
    body{{background:var(--bg);color:var(--text);font-family:-apple-system,"Segoe UI",Roboto,Arial,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;padding:20px;}}
    .card{{background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:32px 24px 24px;max-width:440px;width:100%;box-shadow:0 20px 60px rgba(0,0,0,.5);animation:up .3s ease;}}
    @keyframes up{{from{{opacity:0;transform:translateY(16px)}}to{{opacity:1;transform:none}}}}
    .icon{{width:68px;height:68px;background:rgba(248,81,73,.12);border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:34px;margin:0 auto 18px;}}
    h1{{font-size:22px;font-weight:800;color:var(--red);text-align:center;margin-bottom:6px;}}
    .domain{{text-align:center;font-family:monospace;font-size:14px;color:var(--muted);margin-bottom:12px;word-break:break-all;}}
    .badge{{display:inline-block;background:rgba(248,81,73,.14);border:1px solid rgba(248,81,73,.3);color:var(--red);padding:3px 12px;border-radius:20px;font-size:11px;font-weight:700;margin-bottom:18px;}}
    .badge-wrap{{text-align:center;}}
    p{{font-size:13.5px;color:var(--muted);text-align:center;line-height:1.65;margin-bottom:22px;}}
    hr{{border:none;border-top:1px solid var(--border);margin-bottom:20px;}}
    .btn{{display:flex;align-items:center;justify-content:center;gap:7px;width:100%;padding:13px;border-radius:10px;font-size:14.5px;font-weight:700;border:none;cursor:pointer;margin-bottom:10px;font-family:inherit;}}
    .btn-primary{{background:var(--blue);color:#fff;}}
    .btn-ghost{{background:transparent;border:1px solid var(--border);color:var(--muted);}}
    .form-section{{display:none;margin-top:4px;}}
    .form-section.show{{display:block;}}
    label{{font-size:12px;color:var(--muted);display:block;margin-bottom:6px;text-align:right;}}
    textarea{{width:100%;background:var(--bg);border:1px solid var(--border);color:var(--text);padding:10px;border-radius:8px;font-size:13px;font-family:inherit;resize:vertical;min-height:72px;direction:rtl;outline:none;}}
    .btn-send{{background:var(--green);color:#fff;width:100%;padding:12px;border-radius:9px;border:none;font-size:14px;font-weight:700;cursor:pointer;margin-top:10px;font-family:inherit;}}
    .ok{{display:none;color:var(--green);font-size:13px;text-align:center;margin-top:10px;padding:10px;background:rgba(63,185,80,.1);border-radius:8px;}}
    .ok.show{{display:block;}}
  </style>
</head>
<body>
  <div class="card">
    <div class="icon">🛡️</div>
    <h1>גישה חסומה</h1>
    <div class="domain">{domain}</div>
    <div class="badge-wrap"><span class="badge">🌐 אתר חסום</span></div>
    <p>גישה לאתר זה חסומה על-ידי מנהל המערכת.<br>לפתיחה שלח בקשה למנהל.</p>
    <hr/>
    <button class="btn btn-primary" onclick="showForm()" id="btnReq">📨 בקש פתיחה</button>
    <button class="btn btn-ghost" onclick="history.back()">← חזרה</button>
    <div class="form-section" id="formSec">
      <label>סיבת הבקשה (אופציונלי):</label>
      <textarea id="reason" placeholder="למה אתה צריך גישה לאתר זה?"></textarea>
      <button class="btn-send" onclick="sendReq()">✉️ שלח למנהל</button>
      <div class="ok" id="okMsg">✅ הבקשה נשלחה! המנהל יחזור אליך בהקדם.</div>
    </div>
  </div>
  <script>
    var domain = "{domain}", deviceId = "{device_id}";
    function showForm(){{ document.getElementById("formSec").classList.add("show"); document.getElementById("btnReq").style.display="none"; }}
    function sendReq(){{
      var reason = document.getElementById("reason").value.trim() || "ללא סיבה";
      fetch("/api/requests",{{method:"POST",headers:{{"Content-Type":"application/json"}},
        body:JSON.stringify({{device_id:deviceId,request_type:"domain",target:domain,reason:reason}})
      }}).then(function(){{
        document.getElementById("okMsg").classList.add("show");
        document.querySelector(".btn-send").disabled=true;
      }}).catch(function(){{ alert("שגיאה בשליחת הבקשה — נסה שוב."); }});
    }}
  </script>
</body>
</html>
"""


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        seed_policies(db)
    yield


app = FastAPI(title="Sinun API", version="0.1.0", lifespan=lifespan)

app.include_router(admin.router)
app.include_router(clients.router)
app.include_router(policies.router)
app.include_router(enrollment.router)
app.include_router(devices.router)
app.include_router(requests.router)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/blocked", include_in_schema=False)
def block_page(request: Request, domain: str = "", device_id: str = ""):
    """דף חסימה לדפדפן — מוצג כשדומיין חסום מנותב לשרת זה ב-DNS.
    תואם HTTP בלבד; HTTPS ייכשל ב-TLS (ללא MITM — לפי תכנון)."""
    safe_domain = domain[:253].replace('"', '').replace('<', '').replace('>', '') or "unknown"
    safe_device = device_id[:36].replace('"', '')
    html = _BLOCK_PAGE_TEMPLATE.replace("{domain}", safe_domain).replace("{device_id}", safe_device)
    return HTMLResponse(content=html)


@app.get("/", include_in_schema=False)
def root():
    return RedirectResponse(url="/admin")


@app.get("/admin", include_in_schema=False)
def admin_panel():
    return FileResponse(STATIC_DIR / "admin.html")

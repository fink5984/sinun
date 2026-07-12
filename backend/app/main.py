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
  <link rel="preconnect" href="https://fonts.googleapis.com"/>
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin/>
  <link href="https://fonts.googleapis.com/css2?family=Heebo:wght@400;500;600;700;800&display=swap" rel="stylesheet"/>
  <style>
    :root{--bg1:#eef3fb;--bg2:#dbe7f6;--card:#fff;--ink:#16283d;--muted:#63748b;--faint:#94a2b6;--line:#e6ecf5;--navy:#173a67;--brand:#2f6cdf;--brand-ink:#1e5ac0;--amber:#b9832e;--amber-bg:#fbf3e2;--green:#1f9254;--green-bg:#e6f4ec;}
    *{box-sizing:border-box;margin:0;padding:0;}
    body{font-family:"Heebo","Assistant",-apple-system,"Segoe UI",Roboto,Arial,sans-serif;color:var(--ink);background:linear-gradient(165deg,var(--bg1),var(--bg2));display:flex;align-items:center;justify-content:center;min-height:100vh;padding:22px;-webkit-font-smoothing:antialiased;}
    .card{background:var(--card);border-radius:24px;padding:30px 24px 24px;max-width:420px;width:100%;box-shadow:0 20px 50px rgba(23,58,103,.18);animation:rise .35s cubic-bezier(.2,.9,.3,1.2);}
    @keyframes rise{from{opacity:0;transform:translateY(22px) scale(.97);}to{opacity:1;transform:none;}}
    .emblem{width:78px;height:78px;border-radius:24px;margin:0 auto 20px;display:flex;align-items:center;justify-content:center;background:linear-gradient(150deg,var(--navy),var(--brand));box-shadow:0 12px 26px rgba(47,108,223,.32);}
    .emblem svg{width:40px;height:40px;fill:#fff;}
    h1{font-size:23px;font-weight:800;text-align:center;letter-spacing:-.01em;}
    .badge{display:flex;align-items:center;justify-content:center;gap:7px;width:fit-content;margin:12px auto 0;background:var(--amber-bg);color:var(--amber);font-size:12.5px;font-weight:700;padding:6px 14px;border-radius:20px;}
    .badge svg{width:15px;height:15px;fill:currentColor;}
    .domain{margin:16px 0 4px;background:#f4f7fc;border:1px solid var(--line);border-radius:12px;padding:13px 14px;text-align:center;font-size:15px;font-weight:700;color:var(--navy);word-break:break-all;font-family:ui-monospace,Consolas,monospace;}
    p.desc{font-size:14px;color:var(--muted);text-align:center;line-height:1.6;margin:16px 4px 22px;}
    .btn{display:flex;align-items:center;justify-content:center;gap:9px;width:100%;padding:15px;border:none;border-radius:14px;font-size:15.5px;font-weight:800;font-family:inherit;cursor:pointer;transition:transform .1s;margin-bottom:11px;}
    .btn:active{transform:scale(.98);}
    .btn svg{width:19px;height:19px;fill:currentColor;}
    .btn-primary{background:linear-gradient(150deg,var(--brand),var(--brand-ink));color:#fff;box-shadow:0 8px 20px rgba(47,108,223,.3);}
    .btn-soft{background:#eef2f9;color:var(--navy);}
    .form{display:none;margin-top:6px;}
    .form.show{display:block;}
    label{font-size:13px;color:var(--muted);font-weight:600;display:block;margin:6px 0 8px;text-align:right;}
    textarea{width:100%;background:#f9fbfe;border:1.5px solid var(--line);color:var(--ink);padding:13px;border-radius:12px;font-size:14px;font-family:inherit;resize:vertical;min-height:78px;direction:rtl;outline:none;}
    textarea:focus{border-color:var(--brand);}
    .ok{display:none;text-align:center;color:var(--green);font-size:14px;font-weight:700;padding:13px;background:var(--green-bg);border-radius:12px;margin-top:10px;}
    .ok.show{display:block;}
    .foot{text-align:center;font-size:11.5px;color:var(--faint);margin-top:18px;}
  </style>
</head>
<body>
  <div class="card">
    <div class="emblem"><svg viewBox="0 0 24 24"><path d="M12 1 3 5v6c0 5.5 3.8 10.7 9 12 5.2-1.3 9-6.5 9-12V5l-9-4Zm0 3.18 6 2.67V11c0 4.52-2.98 8.69-6 9.93V4.18Z"/></svg></div>
    <h1>גישה חסומה</h1>
    <div class="badge"><svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm6.9 6h-2.95a15.7 15.7 0 0 0-1.38-3.56A8.03 8.03 0 0 1 18.9 8ZM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96ZM4.26 14a7.96 7.96 0 0 1 0-4h3.38a16.5 16.5 0 0 0 0 4H4.26Zm.84 2h2.95c.35 1.26.82 2.47 1.38 3.56A8 8 0 0 1 5.1 16Zm2.95-8H5.1a8 8 0 0 1 4.33-3.56C8.87 5.53 8.4 6.74 8.05 8ZM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82A15.7 15.7 0 0 1 12 19.96ZM14.34 14H9.66a14.7 14.7 0 0 1 0-4h4.68a14.7 14.7 0 0 1 0 4Zm.25 5.56c.56-1.09 1.03-2.3 1.38-3.56h2.95a8.03 8.03 0 0 1-4.33 3.56ZM16.36 14a16.5 16.5 0 0 0 0-4h3.38a7.96 7.96 0 0 1 0 4h-3.38Z"/></svg><span>אתר חסום</span></div>
    <div class="domain">{domain}</div>
    <p class="desc">הגישה לאתר זה חסומה על‑ידי מנהל המערכת.<br>ניתן לשלוח בקשה למנהל לפתיחת הגישה.</p>
    <button class="btn btn-primary" onclick="showForm()" id="btnReq">
      <svg viewBox="0 0 24 24"><path d="M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2Zm0 4-8 5-8-5V6l8 5 8-5v2Z"/></svg>
      בקשת פתיחה
    </button>
    <button class="btn btn-soft" onclick="history.back()">
      <svg viewBox="0 0 24 24"><path d="M15.4 7.4 14 6l-6 6 6 6 1.4-1.4L10.8 12Z"/></svg>
      חזרה
    </button>
    <div class="form" id="formSec">
      <label>סיבת הבקשה (אופציונלי):</label>
      <textarea id="reason" placeholder="למה צריך גישה לאתר הזה?"></textarea>
      <button class="btn btn-primary" style="margin-top:11px" onclick="sendReq()">
        <svg viewBox="0 0 24 24"><path d="M2 21 23 12 2 3v7l15 2-15 2Z"/></svg>
        שליחה למנהל
      </button>
      <div class="ok" id="okMsg">✓ הבקשה נשלחה! המנהל יחזור אליכם בהקדם.</div>
    </div>
    <div class="foot">Sinun · הגנה על תוכן ופרטיות</div>
  </div>
  <script>
    var domain = "{domain}", deviceId = "{device_id}";
    function showForm(){ document.getElementById("formSec").classList.add("show"); document.getElementById("btnReq").style.display="none"; }
    function sendReq(){
      var reason = document.getElementById("reason").value.trim() || "ללא סיבה";
      fetch("/api/requests",{method:"POST",headers:{"Content-Type":"application/json"},
        body:JSON.stringify({device_id:deviceId,request_type:"domain",target:domain,reason:reason})
      }).then(function(){
        document.getElementById("okMsg").classList.add("show");
        document.querySelectorAll(".form .btn-primary")[0].disabled=true;
      }).catch(function(){ alert("שגיאה בשליחת הבקשה — נסה שוב."); });
    }
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

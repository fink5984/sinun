# Sinun — Android Filtering System

מערכת סינון לאנדרואיד: agent במכשיר (VPN + App Control), שרת ניהול, ופאנל אדמין.
עיקרון מנחה: **Default Deny** — מה שלא אושר במפורש, חסום.

התוכנית המלאה: [PLAN.md](PLAN.md)

## מבנה הפרויקט

```
agent/    — אפליקציית Android (Kotlin, VpnService, Foreground Service)
backend/  — שרת ניהול (Python FastAPI + PostgreSQL/SQLite + Docker)
panel/    — פאנל אדמין (Next.js) — שבוע 4
docs/     — PRD, threat model, החלטות
```

## הרצת ה-backend (פיתוח)

```bash
cd backend
python -m venv .venv
.venv/Scripts/activate      # Windows
pip install -r requirements.txt
uvicorn app.main:app --reload
```

ברירת מחדל: SQLite מקומי (`sinun.db`). ל-PostgreSQL: להגדיר `DATABASE_URL` (ראה `.env.example`) או `docker compose up`.

API docs: http://localhost:8000/docs

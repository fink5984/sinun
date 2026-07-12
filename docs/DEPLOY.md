# פריסת ה-Backend (Railway / Render)

⚠️ זה **monorepo** — השרת נמצא בתיקייה `backend/`, לא בשורש. הפריסה נכשלה כי הפלטפורמה
סרקה את שורש ה-repo ולא מצאה אפליקציה יחידה. הפתרון: להפנות אותה ל-`backend/`.

## Railway

1. New Project → Deploy from GitHub → `fink5984/sinun`.
2. בהגדרות ה-service → **Root Directory** = `backend`.
   מרגע זה Railway יזהה את ה-`Dockerfile` שבתיקייה ויבנה ממנו.
3. הוסף PostgreSQL ו-Redis (Add Plugin). Railway יזריק `DATABASE_URL`.
   ודא שהפורמט הוא `postgresql+psycopg2://...` (או השאר SQLite לבדיקה ראשונית).
4. Deploy. הבריאות: `https://<app>.up.railway.app/health` → `{"status":"ok"}`.

## Render

1. New → Web Service → הרפו.
2. **Root Directory** = `backend`, Environment = Docker (מזהה את ה-Dockerfile),
   או Python עם Start Command: `uvicorn app.main:app --host 0.0.0.0 --port $PORT`
   (יש `Procfile` תואם).
3. הוסף PostgreSQL managed, הגדר `DATABASE_URL`.

## משתני סביבה

| משתנה | ערך |
|---|---|
| `DATABASE_URL` | `postgresql+psycopg2://user:pass@host:5432/db` (או `sqlite:///./sinun.db` לבדיקה) |
| `PORT` | מוזרק ע"י הפלטפורמה; מקומית 8000 |

## אחרי הפריסה

עדכן ב-agent את `API_BASE_URL` (ב-[app/build.gradle.kts](../agent/app/build.gradle.kts))
לכתובת הציבורית של השרת, במקום `10.0.2.2:8000` (שהוא localhost של האמולטור).

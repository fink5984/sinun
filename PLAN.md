# תוכנית עבודה — מערכת סינון אנדרואיד ("סינון")

> מבוסס על "Professional Android Filtering System Plan". תאריך התחלה: 12/07/2026.
> עיקרון מנחה: **Default Deny** — מה שלא אושר במפורש, חסום. לא רודפים אחרי עקיפות נקודתיות — בונים שכבות.

---

## 1. החלטות טכניות (סגורות — לא פותחים מחדש בלי סיבה)

| תחום | החלטה | נימוק |
|---|---|---|
| Android | Kotlin Native (לא React Native) | גישה עמוקה ל-VpnService / DevicePolicyManager / UsageStats |
| Backend | Python FastAPI + PostgreSQL + Redis + Docker | מהיר לפיתוח, קל לבנות policy engine, קל לחבר AI בהמשך |
| סינון | DNS + SNI + per-app. **בלי TLS MITM** בגרסה ראשונה | פחות שבירות (בנקים, Google Pay, pinning), פחות בעיות פרטיות |
| ניהול מכשיר | Android Management API (לא DPC עצמאי) | מהיר לפיילוט; DPC עצמאי רק אם המוצר מוכיח את עצמו |
| פאנל | Next.js + TypeScript + Tailwind + shadcn/ui + Auth עם 2FA | סטנדרטי, מהיר |
| Hosting | פיילוט: Railway/Render/VPS. מוצר: Hetzner/DO/AWS + Postgres מנוהל | להתחיל זול |
| פרטיות | לוגים של מטא-דאטה בלבד (דומיין, אפליקציה, סיבה). **אין** תוכן הודעות/תמונות/סיסמאות | "סינון", לא "ריגול" |

---

## 2. שלב 0 — הגדרת מודל הגנה (שבוע 1)

לפני קוד, לענות בכתב (במסמך `THREAT_MODEL.md`) על:

1. המכשיר בבעלות המשתמש או נמכר כמנוהל?
2. המשתמש רגיל או טכני שמנסה לעקוף?
3. מותר להתקין אפליקציות לבד? להיכנס להגדרות? לכבות VPN?
4. מותר APK מחוץ ל-Google Play?
5. חוסמים רק דפדפן או גם WebView באפליקציות?
6. סינון לפי אתר, לפי אפליקציה, או שניהם?
7. מה קורה בלי אינטרנט / כשהשרת לא זמין?

**מדיניות ברירת מחדל:**
- מאושר → פתוח. לא מאושר → חסום. ספק → חסום.
- שרת לא זמין → עובדים לפי policy cache מקומי.
- אין policy מקומי → חסימת גלישה + ערוץ תמיכה מינימלי פתוח.

**תוצרי שבוע 1:** מסמך PRD + threat model, repo פתוח (monorepo: `agent/`, `backend/`, `panel/`), skeleton של Android + FastAPI, סכמת DB ראשונית, policy JSON ידני, 3 מכשירי בדיקה נבחרו.

---

## 3. יעד ראשון — 30 יום: מכשיר אחד מסונן + פאנל בסיסי (גרסה 0.1)

### שבוע 1 — תשתית
- [ ] GitHub repo + מבנה monorepo
- [ ] אפליקציית Android ריקה (Kotlin, Foreground Service)
- [ ] שרת FastAPI + PostgreSQL schema (טבלאות: users, devices, policies, policy_rules, apps, requests, events)
- [ ] Endpoints: `POST /api/devices/register`, `GET /api/devices/{id}/policy`
- [ ] policy JSON ידני; האפליקציה מציגה סטטוס policy

### שבוע 2 — VPN + סינון DNS
- [ ] הפעלת VpnService ותפיסת DNS
- [ ] חסימת דומיין / אישור דומיין (allowlist + blacklist)
- [ ] מסך חסימה (block page)
- [ ] `POST /api/devices/heartbeat` + שליחת לוג לשרת
- [ ] שמירת policy מקומי ב-Room DB (cache — עובד גם בלי שרת)

### שבוע 3 — App Control בסיסי
- [ ] הרשאת Usage Access + זיהוי foreground app
- [ ] חסימת אפליקציה לפי package (Overlay למסך חסימה)
- [ ] `POST /api/devices/{id}/events` + הצגת לוגים בפאנל

### שבוע 4 — פאנל ניהול + בקשות פתיחה
- [ ] פאנל: login למנהל, רשימת מכשירים, עריכת allowlist/blacklist
- [ ] זרימת בקשה: משתמש נחסם → כפתור "בקש פתיחה" + סיבה → `POST /api/requests` → אישור/דחייה בפאנל (`/approve`, `/reject`) → policy מתעדכן במכשיר → הודעה למשתמש
- [ ] סוגי אישור: 15 דק' / שעה / יום / קבוע (בהמשך: לפי אפליקציה/מכשיר/שעות)

### הגדרת הצלחה לגרסה 0.1 (כולן חייבות לעבור)
1. המכשיר עובד יום שלם בלי שהסינון נופל
2. אתרים חסומים באמת נחסמים; מאושרים נפתחים בלי בעיות
3. אפליקציות לא מאושרות נחסמות
4. הפאנל רואה את המכשיר ואפשר לעדכן policy מרחוק
5. יש לוג ברור למה משהו נחסם + המשתמש יכול לשלוח בקשת פתיחה
6. אחרי reboot המערכת חוזרת לעבוד
7. אין פגיעה מורגשת בביצועים/סוללה

**אם 0.1 לא יציבה — לא מוסיפים פיצ'רים. מייצבים קודם.**

---

## 4. ימים 36–65: App Verification + Hardening

### ימים 36–50 — זיהוי אפליקציות נכון (שלב 4 במסמך)
אסור לסמוך על package name בלבד. אפליקציה מותרת רק אם **כל** אלה תקינים:
1. package name תואם
2. signing certificate SHA-256 תואם
3. installer source תקין (Play Store / Managed Play)
4. version code לא חשוד
5. לא clone / dual app / פרופיל אחר / APK חיצוני

אחד לא תקין → חסום.

### ימים 51–65 — הגנות נגד כיבוי ושיבוש (שלב 5)
**לזהות:** VPN כבוי, ביטול הרשאות (Usage/Overlay/Accessibility), הסרת/עצירת האפליקציה, מצב טיסה, Private DNS, VPN אחר, APK חדש, Safe Mode, factory reset, clone app, Secure Folder.

**תגובה — לא אגרסיבית:**
- לוג אירוע + alert לשרת
- חזרה אוטומטית למצב מוגן כשאפשר
- מסך "ההגנה לא פעילה"
- **fail closed**: חסימת גלישה אם אין VPN
- קוד מנהל לשינוי מצב

**תוצר יום 65:** מערכת שאפשר לתת לפיילוט קטן.

---

## 5. ימים 66–90: Device Owner + פיילוט

### ימים 66–80 — Android Enterprise POC (שלב 6)
דרך **Android Management API**:
- מכשיר fully managed
- Always-on VPN + lockdown (אין תעבורה כשה-VPN נופל)
- מניעת הסרת agent, חסימת התקנות לא מאושרות ומקורות לא ידועים
- app allowlist, Lock Task Mode, הגבלת הגדרות

**תוצר:** מכשיר אחד מנוהל ברמת Device Owner.

### ימים 81–90 — פיילוט סגור
- 5–10 מכשירים, משתמשים אמיתיים
- מעקב חסימות, תיקון false positives, שיפור זרימת בקשות
- בניית רשימת פיצ'רים ל-1.0
- **החלטה:** האם זה הופך למוצר מסחרי

---

## 6. שלבים לאחר 90 יום (לפי סדר)

1. **Launcher סגור** (שלב 7): רק אפליקציות מאושרות, כפתור בקשה/תמיכה, מצבי "עסק/נסיעה/בית", קוד מנהל. המשתמש רואה סביבה נקייה — לא מרגיש "נחסם".
2. פאנל מלא (שלב 8): Dashboard, Devices, Policies, Requests, Logs — עם 2FA ו-role-based access.
3. WhatsApp bot לבקשות פתיחה.
4. per-app VPN מתקדם, AI לסיווג בקשות, zero-touch provisioning.
5. TLS inspection — רק בגרסה מתקדמת, ורק למסלולים מסוימים.

## 7. לא בונים בהתחלה (רשימה מחייבת)
iOS · TLS MITM מלא · סינון תמונות בזמן אמת · ROM מותאם · Marketplace · מערכת קהילות/רבנים · תמיכה בעשרות דגמים · בקרת הורים מורכבת · billing/תשלומים · white-label

---

## 8. QA מתמשך (שלב 10)

**מכשירי בדיקה:** Samsung זול, Samsung בינוני, Xiaomi/Redmi, Pixel, אנדרואיד ישן + חדש, טאבלט.

**מטריצת בדיקות חובה:** הפעלת/נפילת VPN, reboot, כיבוי הרשאות, הסרת אפליקציה, התקנת APK, VPN אחר, שינוי DNS, WebView, דפדפן פנימי, Google Play, Waze, WhatsApp, Gmail, בנקים, Google Pay, אפליקציות תעופה, roaming, Wi-Fi ציבורית, hotspot.

**מדדי הצלחה:** המשתמש מבין מה קרה · יש דרך לבקש פתיחה · המנהל רואה לוג ברור · המכשיר חוזר למצב מוגן · מעט false positives · אפליקציות חשובות לא נשברות.

---

## 9. ארכיטקטורה

```
Android Device                      Backend API              Admin Panel
├── Android Agent                   ├── Device Management    ├── Devices
│   ├── VPN Service                 ├── Policy Engine        ├── Policies
│   ├── App Monitor                 ├── Logs / Events        ├── Requests
│   ├── Local Policy Cache (Room)   ├── Requests             ├── Logs
│   ├── Block Screen                └── Users / Admin        └── Alerts
│   └── Heartbeat Client
└── Local Filtering: DNS rules · Domain rules · App rules
```

**מבנה policy לדוגמה:**
```json
{
  "policy_id": "basic_kosher_001",
  "default_network_action": "block",
  "default_app_action": "block",
  "allowed_domains": ["google.com", "gmail.com", "waze.com"],
  "blocked_domains": ["example-bad-site.com"],
  "allowed_apps": [
    { "package_name": "com.whatsapp", "signature_sha256": "OFFICIAL_SIGNATURE_HERE", "action": "allow" }
  ],
  "blocked_apps": [
    { "package_name": "com.android.chrome", "action": "block" }
  ],
  "support": { "allow_opening_requests": true, "temporary_opening_minutes": 30 }
}
```

---

## 10. הצעד הבא המיידי

1. לענות על 10 שאלות ה-threat model (סעיף 2) — שעה של עבודה, קובע הכול.
2. לפתוח repo עם המבנה: `agent/` (Kotlin), `backend/` (FastAPI), `panel/` (Next.js), `docs/`.
3. להתחיל שבוע 1 מהרשימה בסעיף 3.

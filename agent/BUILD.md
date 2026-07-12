# בניית ה-Agent (Android)

## דרישות
- JDK 17
- Android SDK עם platform android-35 ו-build-tools 35.x
- `agent/local.properties` עם `sdk.dir=<נתיב ל-SDK>` (לא נכנס ל-git)

## בנייה
```bash
cd agent
./gradlew :app:assembleDebug      # פלט: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # בדיקות היחידה של מנוע הסינון
```
או פשוט לפתוח את התיקייה `agent/` ב-Android Studio.

## מכונות עם אנטי-וירוס / פרוקסי שבודק TLS
אם הבנייה נכשלת עם `PKIX path building failed` (Gradle לא מצליח להוריד תלויות),
זה בגלל תוכנת אבטחה שמיירטת TLS ומציגה תעודה ש-Java לא מכיר. הוסף ל-
`~/.gradle/gradle.properties` (רמת משתמש, לא ב-repo):
```
org.gradle.jvmargs=-Xmx2048m -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
```
זה גורם ל-Java להשתמש במאגר התעודות של Windows (שכן סומך על התעודה),
בלי צורך לכבות את האנטי-וירוס.

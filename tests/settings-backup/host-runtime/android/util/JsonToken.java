package android.util;

/** Host-only token names used by the real weather validator. Never packaged in an APK. */
public enum JsonToken {
    BEGIN_ARRAY, END_ARRAY, BEGIN_OBJECT, END_OBJECT, NAME, STRING, NUMBER,
    BOOLEAN, NULL, END_DOCUMENT
}

package fi.jommi.rivi

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** Device-bound recovery data and a TV-local ADB identity. Neither is exported or backed up. */
internal class StockRecoveryStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("stock-recovery", Context.MODE_PRIVATE)

    fun plan(): StockRestorePlan? = prefs.getString("plan", null)?.let { parse(it) }

    fun configure(encoded: String) {
        check(app.packageName == "dev.zane.pretttv") { "Recovery setup is only available in the installed launcher." }
        require(encoded.length <= 32_768) { "Recovery record is too large." }
        val json = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        parse(json)
        check(!StockRecoverySession.busy) { "A recovery check is already running." }
        check(prefs.edit().putString("plan", json).putString("status", "Ready for a connection check.").commit())
    }

    fun status(): String = prefs.getString("status", "Recovery has not been configured for this TV.").orEmpty()
    fun status(message: String) { check(prefs.edit().putString("status", message.take(2000)).commit()) }

    @Synchronized fun keyPair(): KeyPair {
        val file = AtomicFile(File(app.noBackupFilesDir, "stock-recovery-adb-key.json"))
        if (file.baseFile.exists()) {
            val json = JSONObject(file.openRead().use { String(it.readBytes(), Charsets.UTF_8) })
            val factory = KeyFactory.getInstance("RSA")
            return KeyPair(factory.generatePublic(X509EncodedKeySpec(Base64.decode(json.getString("public"), Base64.NO_WRAP))),
                factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(json.getString("private"), Base64.NO_WRAP))))
        }
        val pair = LocalAdbClient.generateKeyPair()
        val json = JSONObject().put("public", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .put("private", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)).toString()
        val stream = file.startWrite()
        try { stream.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        return pair
    }

    companion object {
        fun paused(context: Context) = context.getSharedPreferences("stock-recovery", Context.MODE_PRIVATE).getBoolean("paused", false)
        fun pause(context: Context) {
            check(context.getSharedPreferences("stock-recovery", Context.MODE_PRIVATE).edit().putBoolean("paused", true).commit())
        }
        fun resume(context: Context) {
            check(context.getSharedPreferences("stock-recovery", Context.MODE_PRIVATE).edit().putBoolean("paused", false).commit())
        }
        internal fun parse(text: String): StockRestorePlan {
            require(text.length <= 24_576) { "Recovery record is too large." }
            val json = JSONObject(text)
            require(json.getInt("version") == 1) { "Unsupported recovery record." }
            val array = json.getJSONArray("packages")
            require(array.length() in 1..10) { "Invalid package record." }
            fun nullable(key: String): String? {
                require(json.has(key)) { "Incomplete DNS record." }
                return if (json.isNull(key)) null else json.getString(key)
            }
            return StockRestorePlan(json.getString("deviceSerial"), json.getString("fingerprint"), json.getString("stockHome"),
                (0 until array.length()).map { i -> val p = array.getJSONObject(i)
                    RestorePackage(p.getString("name"), p.getInt("previousState"), p.getInt("managedState")) },
                nullable("dnsMode"), nullable("dnsSpecifier"), nullable("managedDnsMode"), nullable("managedDnsSpecifier")
            ).also { it.validate() }
        }
    }
}

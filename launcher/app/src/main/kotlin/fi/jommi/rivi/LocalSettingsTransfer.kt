package fi.jommi.rivi

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Explicit local ADB backup/restore through Activity.dump, protected by Android's DUMP permission. */
object LocalSettingsTransfer {
    private const val MAX_ENCODED = 16 * 1024 * 1024
    private const val MAX_DECODED = 12 * 1024 * 1024
    private val allowedStores=setOf("home","apps","playback",PersonalizationStore.PREFS_NAME)
    fun export(stores:Map<String,SharedPreferences>):String {
        require(stores.keys.all { it in allowedStores }) { "Unsupported preference store" }
        val root=JSONObject().put("version",1)
        stores.forEach { (name,prefs) ->
            val values=JSONObject()
            prefs.all.forEach { (key,value) ->
                val type=when(value) { is String->"string"; is Boolean->"boolean"; is Int->"int"; is Long->"long"; is Float->"float"; is Set<*>->"set"; else->error("Unsupported preference value") }
                values.put(key,JSONObject().put("type",type).put("value",if(value is Set<*>)JSONArray(value.toList()) else value))
            }
            root.put(name,values)
        }
        val bytes=root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_DECODED) { "Settings export exceeds 12 MB" }
        return Base64.encodeToString(bytes,Base64.NO_WRAP).also { require(it.length<=MAX_ENCODED) }
    }
    fun restore(data:String,stores:Map<String,SharedPreferences>) {
        require(stores.keys.all { it in allowedStores }) { "Unsupported preference store" }
        require(data.length<=MAX_ENCODED) { "Settings file exceeds 16 MB" }
        val decoded=Base64.decode(data,Base64.DEFAULT)
        require(decoded.size<=MAX_DECODED) { "Decoded settings exceed 12 MB" }
        val root=JSONObject(String(decoded,Charsets.UTF_8))
        require(root.opt("version")==1) { "Unsupported settings version" }
        require(root.keys().asSequence().all { it=="version" || it in allowedStores }) { "Unsupported preference store" }
        // Parse and validate every store/value before making the first editor.
        val prepared=stores.keys.associateWith { name ->
            // Exports predating personalization deliberately restore its defaults.
            val values=if(!root.has(name) && name==PersonalizationStore.PREFS_NAME)JSONObject()
                else root.optJSONObject(name) ?: error("Missing or invalid preference store")
            val parsed=values.keys().asSequence().associateWith { key ->
                val item=values.getJSONObject(key)
                val value=item.get("value")
                when(item.getString("type")) {
                    "string"->value.also { require(it is String) }
                    "boolean"->value.also { require(it is Boolean) }
                    "int"->{
                        require(value is Int || value is Long)
                        val number=(value as Number).toLong()
                        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); number.toInt()
                    }
                    "long"->{ require(value is Int || value is Long); (value as Number).toLong() }
                    "float"->{
                        require(value is Number)
                        value.toFloat().also { require(it.isFinite()) }
                    }
                    "set"->{
                        require(value is JSONArray)
                        (0 until value.length()).map { value.get(it).also { entry->require(entry is String) } as String }.toSet()
                    }
                    else->error("Unsupported preference type")
                }
            }
            if(name==PersonalizationStore.PREFS_NAME) {
                require(parsed.keys.all { it=="state" }) { "Unsupported personalization field" }
                parsed["state"]?.let { require(it is String); PersonalizationStore.validateStateImport(it) }
            }
            validateKnownKeys(name,parsed)
            parsed
        }
        val previous=stores.mapValues { (_,prefs)->prefs.all.mapValues { (_,value)->if(value is Set<*>)value.toSet() else value } }
        try {
            prepared.forEach { (name,values)->check(write(stores.getValue(name),values)) { "Unable to save restored preferences" } }
        } catch(failure:Exception) {
            // SharedPreferences changes memory even when its disk commit fails. Restore the
            // original memory state and attempt disk rollback across all allowlisted stores.
            previous.forEach { (name,values)->runCatching { write(stores.getValue(name),values) } }
            throw failure
        }
    }
    private fun validateKnownKeys(name:String,values:Map<String,Any>) {
        values.forEach { (key,value)->
            val expected=when(name) {
                "home"->when {
                    key in setOf("layout","focus","weatherLocation","weatherUnits") || key.startsWith("hiddenTitleLabel:")->"string"
                    key in setOf("discovery","tvPermissionAsked","weatherEnabled")->"boolean"
                    key in setOf("sportsPackages","preferredServices","hiddenHeroTitles","hiddenWatchingTitles")->"set"
                    else->null
                }
                "apps"->when(key) {
                    "fav","focus","theme","motion","recent"->"string"
                    "fav_init"->"boolean"
                    "hidden"->"set"
                    else->null
                }
                "playback"->when {
                    key=="known"->"string"
                    key=="baselineCutoff" || key=="providerLastObserved" ||
                        listOf("baseline:","played:","position:","duration:","event:","providerObserved:").any(key::startsWith)->"long"
                    else->null
                }
                else->null
            }
            require(when(expected) {
                "string"->value is String
                "boolean"->value is Boolean
                "set"->value is Set<*>
                "long"->value is Long && value>=0
                else->true
            }) { "Invalid type for $name/$key" }
            if(name=="home" && key=="weatherLocation")require(WeatherRepository.validateLocationJson(value as String)) { "Invalid weather location" }
            if(name=="home" && key=="weatherUnits")require(value=="F" || value=="C") { "Invalid weather units" }
            if(name=="playback" && key=="known") {
                val rows=JSONArray(value as String)
                repeat(rows.length()) { index->
                    val row=rows.optJSONObject(index) ?: error("Invalid playback title")
                    listOf("id","title","detail","art","pkg","app","uri","type","imdbId").forEach {
                        if(row.has(it))require(row.opt(it) is String) { "Invalid playback title field" }
                    }
                    listOf("pos","dur","releaseYear").forEach {
                        if(row.has(it))require(row.opt(it) is Int || row.opt(it) is Long) { "Invalid playback number" }
                    }
                }
            }
        }
    }
    private fun write(prefs:SharedPreferences,values:Map<String,Any?>):Boolean {
        val edit=prefs.edit().clear()
        values.forEach { (key,value)->when(value) {
            is String->edit.putString(key,value)
            is Boolean->edit.putBoolean(key,value)
            is Int->edit.putInt(key,value)
            is Long->edit.putLong(key,value)
            is Float->edit.putFloat(key,value)
            is Set<*>->edit.putStringSet(key,value.map { it as String }.toSet())
            else->error("Unsupported preference value")
        } }
        return edit.commit()
    }
}

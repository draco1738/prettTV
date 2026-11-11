package fi.jommi.rivi

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Bounded app-owned settings transfer; callers own explicit consent and writer exclusion. */
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
        return Base64.getEncoder().encodeToString(bytes).also { require(it.length<=MAX_ENCODED) }
    }

    /** Opaque validated values. Creating this plan never opens a preferences editor. */
    class Prepared internal constructor(internal val values:Map<String,Map<String,Any>>)
    class RestoreFailure(val rollbackSucceeded:Boolean,cause:Exception):
        IllegalStateException(if(rollbackSucceeded) "Restore failed; previous settings recovered" else
            "Restore failed; disk rollback could not be verified",cause)

    fun restore(data:String,stores:Map<String,SharedPreferences>) = restore(prepare(data,stores.keys),stores)

    /** TV snapshots require all four stores; older diagnostic exports keep legacy defaults. */
    fun prepare(data:String,storeNames:Set<String>,requireAllStores:Boolean=false):Prepared {
        require(storeNames.isNotEmpty() && storeNames.all { it in allowedStores }) { "Unsupported preference store" }
        require(data.length<=MAX_ENCODED) { "Settings file exceeds 16 MB" }
        val decoded=Base64.getDecoder().decode(data.filterNot { it.code==9 || it.code==10 || it.code==13 || it.code==32 })
        require(decoded.size<=MAX_DECODED) { "Decoded settings exceed 12 MB" }
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString()
        val tokens=JSONTokener(text)
        val root=tokens.nextValue() as? JSONObject ?: error("Invalid settings document")
        require(tokens.nextClean()=='\u0000') { "Trailing settings data" }
        require(root.opt("version")==1) { "Unsupported settings version" }
        val present=root.keys().asSequence().filter { it!="version" }.toSet()
        require(present.all { it in allowedStores }) { "Unsupported preference store" }
        if(requireAllStores)require(storeNames==allowedStores && present==allowedStores) { "Incomplete TV snapshot" }
        // Validate even non-target stores: a confirmation must not approve partly parsed data.
        val prepared=(storeNames+present).associateWith { name ->
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
        return Prepared(prepared.filterKeys { it in storeNames })
    }

    /** Call off main, excluding all other writers until commits or rollback have finished. */
    fun restore(prepared:Prepared,stores:Map<String,SharedPreferences>,checkActive:()->Unit={}) {
        require(stores.keys==prepared.values.keys) { "Preference stores changed after validation" }
        checkActive()
        val previous=stores.mapValues { (_,prefs)->prefs.all.mapValues { (_,value)->if(value is Set<*>)value.toSet() else value } }
        val touched=mutableListOf<String>()
        try {
            prepared.values.forEach { (name,values)->
                checkActive()
                touched+=name // commit() can change memory even when it fails or throws.
                check(write(stores.getValue(name),values)) { "Unable to save restored preferences" }
                check(stores.getValue(name).all==values) { "Restored preferences did not verify" }
            }
            checkActive()
        } catch(failure:Exception) {
            if(touched.isEmpty())throw failure
            var recovered=true
            // Cancellation revokes forward writes, not safety rollback. A commit already in
            // the filesystem cannot be interrupted. Keep writer exclusion until cleanup ends.
            touched.asReversed().forEach { name->
                val ok=runCatching {
                    write(stores.getValue(name),previous.getValue(name)) && stores.getValue(name).all==previous.getValue(name)
                }.getOrDefault(false)
                recovered=ok && recovered
            }
            throw RestoreFailure(recovered,failure)
        }
    }
    private fun validateKnownKeys(name:String,values:Map<String,Any>) {
        values.forEach { (key,value)->
            val expected=when(name) {
                "home"->when {
                    key in setOf("layout","focus","weatherLocation","weatherUnits","interfaceSize") || key.startsWith("hiddenTitleLabel:")->"string"
                    key in setOf("discovery","tvPermissionAsked","weatherEnabled","quietMotion")->"boolean"
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
            if(name=="home" && key=="interfaceSize")require(HomeInterfaceSize.entries.any { it.name==value }) { "Invalid Home interface size" }
            if(name=="playback" && key=="known") {
                val rows=JSONArray(value as String)
                repeat(rows.length()) { index->
                    val row=rows.optJSONObject(index) ?: error("Invalid playback title")
                    listOf("id","title","detail","art","pkg","app","uri","type","imdbId","posterArt").forEach {
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

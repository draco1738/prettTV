package fi.jommi.rivi

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Explicit local ADB backup/restore through Activity.dump, protected by Android's DUMP permission. */
object LocalSettingsTransfer {
    fun export(stores:Map<String,SharedPreferences>):String {
        val root=JSONObject().put("version",1)
        stores.forEach { (name,prefs) ->
            val values=JSONObject()
            prefs.all.forEach { (key,value) ->
                val type=when(value) { is String->"string"; is Boolean->"boolean"; is Int->"int"; is Long->"long"; is Float->"float"; is Set<*>->"set"; else->return@forEach }
                values.put(key,JSONObject().put("type",type).put("value",if(value is Set<*>)JSONArray(value.toList()) else value))
            }
            root.put(name,values)
        }
        return Base64.encodeToString(root.toString().toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
    }
    fun restore(data:String,stores:Map<String,SharedPreferences>) {
        require(data.length<=262_144)
        val root=JSONObject(String(Base64.decode(data,Base64.DEFAULT),Charsets.UTF_8))
        require(root.getInt("version")==1)
        val editors=stores.map { (name,prefs) ->
            val values=root.getJSONObject(name); val edit=prefs.edit().clear()
            values.keys().forEach { key ->
                val item=values.getJSONObject(key)
                when(item.getString("type")) {
                    "string"->edit.putString(key,item.getString("value"))
                    "boolean"->edit.putBoolean(key,item.getBoolean("value"))
                    "int"->edit.putInt(key,item.getInt("value"))
                    "long"->edit.putLong(key,item.getLong("value"))
                    "float"->edit.putFloat(key,item.getDouble("value").toFloat())
                    "set"->{ val a=item.getJSONArray("value"); edit.putStringSet(key,(0 until a.length()).map { a.getString(it) }.toSet()) }
                    else->error("Unsupported preference type")
                }
            }; edit
        }
        editors.forEach { check(it.commit()) }
    }
}

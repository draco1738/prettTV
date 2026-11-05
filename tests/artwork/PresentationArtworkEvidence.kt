package fi.jommi.rivi

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Replays independently fetched public responses through the production parser; no network here. */
fun main(args:Array<String>) {
    val directory=File(args.single())
    val records=JSONArray(File(directory,"public-artwork-evidence.json").readText())
    val matches=JSONArray()
    for(i in 0 until records.length()) {
        val record=records.getJSONObject(i)
        val exact=record.getJSONArray("exact")
        if(exact.length()!=1) {
            println("Ambiguous public title; not accepted without year or ID: ${record.getString("title")}")
            continue
        }
        val id=exact.getJSONObject(0).getInt("id")
        val p=Program("evidence:$id",record.getString("title"),"","","public-evidence","Public API probe",mediaType="series")
        val show=checkNotNull(TvmazeArtworkParser.match(p,File(directory,"search-$id.json").readText()))
        val artwork=TvmazeArtworkParser.images(show.id,show.sourceUrl,File(directory,"images-$id.json").readText())
        check(artwork.poster.isNotBlank())
        val images=JSONArray(File(directory,"images-$id.json").readText())
        val dimensions=(0 until images.length()).map { images.getJSONObject(it).getJSONObject("resolutions").getJSONObject("original") }
            .single { it.getString("url")==artwork.poster }
        matches.put(JSONObject().put("title",p.title).put("showId",show.id).put("year",show.year).put("source",show.sourceUrl)
            .put("poster",artwork.poster).put("width",dimensions.getInt("width")).put("height",dimensions.getInt("height")).put("backdrop",artwork.backdrop))
    }
    check(matches.length()>=3)
    File(directory,"production-parser-evidence.json").writeText(matches.toString(2))
    println(matches.toString(2))
    println("Production parser verified ${matches.length()} public portrait matches; not a device feed audit.")
}

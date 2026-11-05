package fi.jommi.rivi

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal data class ArtworkShow(val id:Int,val name:String,val year:Int,val type:String,val imdb:String,val sourceUrl:String)

/** TVmaze search is fuzzy; artwork matching is not. Image URLs are taken only from actual responses. */
internal object TvmazeArtworkParser {
    private val supportedTypes=setOf("Scripted","Animation","Reality","Documentary","Talk Show","Variety","News","Panel Show","Game Show","Award Show")
    private fun text(j:JSONObject,key:String)=(j.opt(key) as? String).orEmpty()
    private fun integer(j:JSONObject,key:String):Int? = (j.opt(key) as? Number)?.let {
        it.toInt().takeIf { value -> value.toDouble()==it.toDouble() }
    }
    private fun show(j:JSONObject):ArtworkShow? {
        val id=integer(j,"id")?.takeIf { it>0 } ?: return null
        val name=text(j,"name").takeIf { it.length in 1..300 } ?: return null
        val type=text(j,"type").takeIf { it in supportedTypes } ?: return null
        val source=text(j,"url").takeIf { BalancedArtworkPolicy.sourceAddress(it,id) } ?: return null
        val year=text(j,"premiered").take(4).toIntOrNull()?.takeIf { it in 1900..2200 } ?: 0
        val imdb=j.optJSONObject("externals")?.let { text(it,"imdb") }.orEmpty()
        return ArtworkShow(id,name,year,type,imdb,source)
    }
    private fun sameTitle(p:Program,name:String)=p.titleKey==name.trim().replace(Regex("\\s+")," ").lowercase(java.util.Locale.ROOT)
    private fun agrees(p:Program,show:ArtworkShow)=sameTitle(p,show.name) && (p.releaseYear==0 || p.releaseYear==show.year) &&
        (BalancedArtworkPolicy.tvmazeId(p)?.let { it==show.id } ?: true) &&
        (BalancedArtworkPolicy.imdbId(p)?.let { it==show.imdb } ?: true)

    fun match(p:Program,json:String):ArtworkShow? {
        if(BalancedArtworkPolicy.cacheKey(p)==null)return null
        val rows=JSONArray(json)
        if(rows.length()>100)return null // Never establish uniqueness from a truncated search.
        val exact=ArrayList<JSONObject>()
        for(i in 0 until rows.length()) {
            val row=rows.optJSONObject(i)?.optJSONObject("show") ?: return null
            // Include unsupported/malformed same-name editions in the ambiguity check, not just successes.
            if(sameTitle(p,text(row,"name")) && (p.releaseYear==0 || text(row,"premiered").take(4).toIntOrNull()==p.releaseYear))exact.add(row)
        }
        val grouped=exact.map { show(it) ?: return null }.groupBy { it.id }
        if(grouped.values.any { it.distinct().size!=1 })return null
        val candidates=grouped.values.map { it.first() }
        return candidates.singleOrNull()?.takeIf { agrees(p,it) }
    }

    fun direct(p:Program,json:String):ArtworkShow? {
        if(BalancedArtworkPolicy.cacheKey(p)==null || (BalancedArtworkPolicy.tvmazeId(p)==null && BalancedArtworkPolicy.imdbId(p)==null))return null
        return show(JSONObject(json))?.takeIf { agrees(p,it) }
    }

    fun images(showId:Int,sourceUrl:String,json:String):PresentationArtwork {
        if(!BalancedArtworkPolicy.sourceAddress(sourceUrl,showId))return PresentationArtwork()
        val rows=JSONArray(json)
        if(rows.length()>500)return PresentationArtwork()
        data class Image(val url:String,val ratio:Double,val main:Boolean,val pixels:Long)
        val posters=ArrayList<Image>(); val backgrounds=ArrayList<Image>()
        for(i in 0 until rows.length()) {
            val row=rows.optJSONObject(i) ?: continue
            val original=row.optJSONObject("resolutions")?.optJSONObject("original") ?: continue
            val width=integer(original,"width") ?: continue; val height=integer(original,"height") ?: continue
            val url=text(original,"url")
            if(width !in 200..12000 || height !in 200..16000 || !BalancedArtworkPolicy.imageAddress(url))continue
            val ratio=width.toDouble()/height
            val image=Image(url,ratio,row.opt("main")==true,width.toLong()*height)
            when(text(row,"type")) {
                "poster" -> if(ratio in .60.. .75)posters.add(image)
                "background" -> if(width>=960 && ratio in 1.6..2.1)backgrounds.add(image)
            }
        }
        // An actual 2:3 variant beats a main 3:4 image; remaining differences need only a small crop.
        val poster=posters.sortedWith(compareBy<Image> { abs(it.ratio-2.0/3.0)>.025 }
            .thenByDescending { it.main }.thenBy { abs(it.ratio-2.0/3.0) }.thenBy { it.pixels }).firstOrNull()?.url.orEmpty()
        val backdrop=backgrounds.sortedWith(compareBy<Image> { abs(it.ratio-16.0/9.0) }.thenBy { it.pixels }).firstOrNull()?.url.orEmpty()
        return if(poster.isBlank() && backdrop.isBlank())PresentationArtwork() else PresentationArtwork(poster,backdrop,sourceUrl)
    }
}

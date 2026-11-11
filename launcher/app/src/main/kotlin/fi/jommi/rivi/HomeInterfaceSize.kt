package fi.jommi.rivi

/** App-local Home geometry; never changes Android density or system font settings. */
internal enum class HomeInterfaceSize(val label:String,val factor:Float) {
    STANDARD("Standard · 100%",1f), LARGER("Larger · 104%",1.04f);
    companion object {
        fun parse(value:String?)=entries.firstOrNull { it.name==value } ?: STANDARD
        fun inset(screenWidth:Int,contentWidth:Int)=((screenWidth-contentWidth)/2).coerceAtLeast(0)
    }
}

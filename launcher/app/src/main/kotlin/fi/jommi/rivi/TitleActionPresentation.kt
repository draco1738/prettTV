package fi.jommi.rivi

/** Quick actions use the same honest resume policy as the card they belong to. */
internal data class TitleActionPresentation(val openLabel:String,val watchlistLabel:String,val subtitle:String) {
    companion object {
        fun from(program:Program,saved:Boolean):TitleActionPresentation {
            val value=ProgramPresentation.from(program)
            return TitleActionPresentation(
                if(value.canResume)"Resume in ${value.source}" else "Open ${value.source}",
                if(saved)"Remove from Watchlist" else "Add to Watchlist",
                listOf(value.source,value.compactSelectedDetail).filter(String::isNotBlank).joinToString(" · "),
            )
        }
    }
}

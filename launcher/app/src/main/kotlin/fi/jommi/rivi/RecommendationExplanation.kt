package fi.jommi.rivi

/** Explain available evidence, not a guessed account profile or causal viewing history. */
internal object RecommendationExplanation {
    fun describe(program:Program,explicitServices:Set<String>?,inferredServices:Set<String>):String {
        val source=if(program.catalog) {
            val name=program.sourceName.ifBlank { "a public catalog" }
            "This title comes from $name. Catalog inclusion does not confirm streaming availability."
        } else "${program.app.ifBlank { "The source app" }} shared this title with Android TV. The app supplies the title and its destination."
        val preference=when {
            program.catalog -> "Public catalog titles are separate from your preferred-service app feeds."
            explicitServices!=null && program.packageName in explicitServices -> "This service is selected in your recommendation preferences."
            explicitServices!=null -> "This service is not selected as a preferred service. Other eligible services can still supply recommendations."
            program.packageName in inferredServices -> "This service has published playback progress on this TV, so it is included in the automatic service priorities. Shared progress does not identify who watched."
            else -> "This is an eligible source, not evidence that you watched a similar title."
        }
        val fallback=if(!program.catalog && program.packageName=="tv.pluto.android")
            "\n\nPluto remains a fallback in Suggestions and Discover even when selected. Other shelves use their own ordering." else ""
        return "$source\n\n$preference$fallback\n\nprettTV cannot verify your sign-in, subscription or why the provider chose this title. Not interested changes local recommendations; it does not alter your streaming account or Watchlist."
    }
}

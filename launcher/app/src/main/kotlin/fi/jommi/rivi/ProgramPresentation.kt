package fi.jommi.rivi

/** Formats only supplied metadata; never guesses an episode or a service's resume behavior. */
internal data class ProgramPresentation(val source:String,val metadata:String,val remaining:String,val canResume:Boolean) {
    val selectedDetail get()=listOf(metadata,remaining).filter(String::isNotBlank).joinToString(" · ")
    /**
     * C continuation only: keep the first copy of each exact, trimmed field in source order.
     * Do not infer episode equivalence, split prose, or discard a unique synopsis. The original
     * metadata/selectedDetail and Program detail/summary remain available to the other surfaces.
     */
    val compactMetadata get()=metadata.split(" · ").map(String::trim)
        .filter { it.isNotBlank() && it!=remaining }.distinct().joinToString(" · ")
    /** Bind alongside source, never append to raw Program.detail, which can contain the same fields. */
    val compactSelectedDetail get()=listOf(compactMetadata,remaining).filter(String::isNotBlank).joinToString(" · ")
    companion object {
        fun from(program:Program):ProgramPresentation {
            val remaining=if(program.position>0 && program.duration>program.position) {
                val minutes=(program.duration-program.position)/60_000
                if(minutes==0L)"Less than a minute left" else "$minutes min left"
            } else ""
            val providerRemaining=if(program.position>0 && program.duration>program.position)
                "${(program.duration-program.position)/60_000} min left" else ""
            val metadata=program.detail.split(" · ").map(String::trim)
                .filter { it.isNotBlank() && it!=program.app && (providerRemaining.isEmpty() || it!=providerRemaining) }
                .joinToString(" · ")
            return ProgramPresentation(program.app,metadata,remaining,program.resume && program.intentUri.isNotBlank())
        }
    }
}

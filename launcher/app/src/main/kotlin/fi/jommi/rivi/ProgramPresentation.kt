package fi.jommi.rivi

/** Formats only supplied metadata; never guesses an episode or a service's resume behavior. */
internal data class ProgramPresentation(val source:String,val metadata:String,val remaining:String,val canResume:Boolean) {
    val selectedDetail get()=listOf(metadata,remaining).filter(String::isNotBlank).joinToString(" · ")
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

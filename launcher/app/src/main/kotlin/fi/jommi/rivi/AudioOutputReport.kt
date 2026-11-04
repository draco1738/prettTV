package fi.jommi.rivi

/** Selected media route, never inferred from paired devices or Bluetooth power. */
internal data class AudioOutputReport(val output:String="Couldn’t verify output",val muted:Boolean?=null) {
    val description:String get()=output+when(muted) { true->" · muted";false->" · not muted";null->"" }
    companion object {
        fun parse(dump:String):AudioOutputReport {
            fun block(header:String):String? {
                val matches=Regex("(?m)^${Regex.escape(header)}\\r?$").findAll(dump).toList()
                if(matches.size!=1)return null
                return dump.substring(matches.single().range.last+1).trimStart('\r','\n')
                    .lineSequence().takeWhile { it.isBlank() || it.first().isWhitespace() }.joinToString("\n")
            }
            val music=block("- STREAM_MUSIC:") ?: return AudioOutputReport()
            fun values(key:String)=Regex("(?m)^[ \\t]+${Regex.escape(key)}:[ \\t]*([^\\r\\n]+)").findAll(music).map { it.groupValues[1].trim() }.toList()
            val routes=values("Devices").singleOrNull() ?: return AudioOutputReport()
            val tokens=routes.split(',').map(String::trim)
            val names=tokens.map { token ->
                when(token.substringBefore('(')) {
                    "speaker"->"TV speakers"
                    "bt_a2dp","bt_a2dp_headphones","bt_a2dp_speaker","ble_headset","ble_speaker"->"Bluetooth audio"
                    "wired_headset","headset","wired_headphone","headphone"->"Wired headphones"
                    "usb_device","usb_headset","usb_accessory"->"USB audio"
                    "hdmi_arc"->"HDMI ARC"
                    "hdmi_earc"->"HDMI eARC"
                    "hdmi","aux_digital"->"HDMI audio"
                    "spdif"->"Optical audio"
                    else->return AudioOutputReport()
                }.also { if(!Regex("[a-z0-9_]+\\([0-9a-fA-F]+\\)").matches(token))return AudioOutputReport() }
            }.distinct()
            if(names.isEmpty())return AudioOutputReport()
            // On TCL, reject a route while its vendor selector and Android disagree.
            if(dump.contains("TclDevModeManager Info:") && block("TclDevModeManager Info:")==null)return AudioOutputReport()
            block("TclDevModeManager Info:")?.let { vendor ->
                val modes=Regex("(?m)^[ \\t]+mCurrentDevMode:[ \\t]*(\\d+)[ \\t]*\\r?$").findAll(vendor).toList()
                val mode=modes.singleOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: return AudioOutputReport()
                if((mode==2 && tokens!=listOf("speaker(2)")) || (mode==4 && tokens!=listOf("bt_a2dp(80)")))return AudioOutputReport()
                if((tokens==listOf("speaker(2)") && mode!=2) || (tokens==listOf("bt_a2dp(80)") && mode!=4))return AudioOutputReport()
            }
            val mute=values("Muted").singleOrNull()?.toBooleanStrictOrNull()
            val internal=values("Muted Internally").singleOrNull()?.toBooleanStrictOrNull()
            return AudioOutputReport(names.joinToString(" + "),if(mute==true || internal==true)true else if(mute==false && internal==false)false else null)
        }
    }
}

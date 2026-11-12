package fi.jommi.rivi

/** UI labels never infer an active route from Bluetooth power or a focused row. */
internal data class AudioOutputChoice(val selected:Boolean,val detail:String) {
    companion object {
        fun describe(target:TvAudioOutput,audio:AudioOutputReport?,radio:Boolean?,working:Boolean):AudioOutputChoice {
            if(working)return AudioOutputChoice(false,"Checking output…")
            val selected=audio?.output==target.label
            return AudioOutputChoice(selected,when {
                selected && audio?.muted==true->"Currently selected · muted"
                selected->"Currently selected"
                target==TvAudioOutput.SPEAKERS->"Built-in"
                target==TvAudioOutput.HDMI_ARC->"HDMI audio system"
                radio==false->"Turn Bluetooth on first"
                radio==null->"Check Bluetooth or manage devices"
                else->"Use a connected audio device"
            })
        }
    }
}

package fi.jommi.rivi

/** One failed app must not abort the remaining icons or kill the process's worker thread. */
internal object AppRefreshWork {
    fun <T> attempt(work:()->T,onFailure:(Exception)->Unit):T? = try { work() } catch(e:Exception) { onFailure(e); null }
    fun <T,R> each(items:List<T>,current:()->Boolean,load:(T)->R,deliver:(T,R)->Unit,onFailure:(T,Exception)->Unit) {
        for(item in items) {
            if(!current())break
            try {
                val result=load(item)
                if(current())deliver(item,result)
            } catch(e:Exception) { if(current())onFailure(item,e) }
        }
    }
}

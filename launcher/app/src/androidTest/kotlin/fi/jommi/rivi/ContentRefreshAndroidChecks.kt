package fi.jommi.rivi

import android.content.Context
import java.io.File

/** JSON faults use isolated cache files and injected responses, never the owner's feeds or network. */
object ContentRefreshAndroidChecks {
    fun run(context:Context,test:(String,()->Unit)->Unit) {
        val dir=File(context.cacheDir,"refresh-status-checks").apply { mkdirs() }
        fun clean(folder:File) { folder.listFiles()?.forEach { check(it.isFile); it.delete() }; folder.delete() }
        test("malformed sports service response reports failure without replacing valid cache") {
            val folder=File(dir,"sports-fault").apply { mkdirs() }
            var failures=0
            val original="{\"events\":[]}"
            for((path,_) in SportsCatalog.LEAGUES)File(folder,"sports-${path.replace('/','-')}.json").apply {
                writeText(original); check(setLastModified(System.currentTimeMillis()-180_000))
            }
            try {
                val catalog=SportsCatalog(folder,{ _,_ -> "{\"events\":\"invalid\"}" },{ failures++ })
                check(catalog.load { true }.isEmpty())
                check(failures==SportsCatalog.LEAGUES.size)
                check(folder.listFiles().orEmpty().all { it.readText()==original })
            } finally { clean(folder) }
        }
        test("a legitimately empty sports feed is successful and not a refresh error") {
            val folder=File(dir,"sports-empty").apply { mkdirs() }
            var failures=0
            try {
                val catalog=SportsCatalog(folder,{ _,_ -> "{\"events\":[]}" },{ failures++ })
                check(catalog.load { true }.isEmpty())
                check(failures==0)
            } finally { clean(folder) }
        }
        test("cancellation during a source request does not publish a failure") {
            val folder=File(dir,"sports-cancel").apply { mkdirs() }
            var failures=0; var active=true
            try {
                val catalog=SportsCatalog(folder,{ _,_ -> active=false; throw java.io.IOException("Cancelled fixture") },{ failures++ })
                check(catalog.load { active }.isEmpty())
                check(failures==0 && folder.listFiles().orEmpty().isEmpty())
            } finally { clean(folder) }
        }
        test("malformed episode response reports failure and preserves the retry policy") {
            val file=File(dir,"episode-fault.bin").apply { delete() }
            var failures=0; var requests=0
            val now=System.currentTimeMillis()
            val follow=FollowedShow("fixture",Program("tvmaze:12","Fixture","","","fixture.app","Fixture",mediaType="series"),now)
            try {
                val catalog=EpisodeCatalog(file,{ _,_ -> requests++; "malformed" },{ failures++ })
                val result=catalog.load(listOf(follow),now) { true }
                check(failures==1 && requests==1 && result.alerts.isEmpty() && result.status.contains("retry tomorrow"))
                catalog.load(listOf(follow),now+1) { true }
                check(failures==1 && requests==1)
            } finally { file.delete() }
        }
        dir.delete()
    }
}

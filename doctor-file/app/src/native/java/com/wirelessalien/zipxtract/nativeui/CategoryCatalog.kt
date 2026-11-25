package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Index-first, cancellable progressive discovery. A slow unindexed directory never gates the first
 * indexed page. No row cap, depth cap, synchronous metadata retrieval or UI-thread directory walk.
 */
class CategoryCatalog(context:Context):AutoCloseable {
    data class Entry(val file:File,val modified:Long,val bytes:Long,val duration:Long=0L)
    data class Snapshot(val entries:List<Entry>,val complete:Boolean,val cached:Boolean=false,
                        val deniedDirectories:Int=0,val indexAvailable:Boolean=true){
        val files:List<File> get()=entries.map{it.file}
        val durations:Map<String,Long> get()=entries.filter{it.duration>0}.associate{it.file.path to it.duration}
    }
    private data class Key(val type:String,val search:String,val roots:List<String>,val hidden:Boolean)
    private data class Cached(val time:Long,val value:Snapshot)
    private val resolver=context.applicationContext.contentResolver
    private val cache=object:LinkedHashMap<Key,Cached>(8,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Key,Cached>?)=size>8}
    private var generation=0L
    private val observer=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){invalidate()}}
    private var registered=false
    // Observe the authority, not only /external/file: image/video/audio and removable-volume
    // notifications have sibling paths and must invalidate the same category cache as file rows.
    init{runCatching{resolver.registerContentObserver(Uri.parse("content://media"),true,observer);registered=true}}
    private fun key(type:String,search:String,roots:List<File>,includeHidden:Boolean)=Key(type,search,roots.map{it.absolutePath},includeHidden)
    @Synchronized fun invalidate(){generation++;cache.clear()}
    @Synchronized fun cached(type:String,search:String="",roots:List<File> = listOf(Environment.getExternalStorageDirectory()),includeHidden:Boolean=false):Snapshot? =
        cache[key(type,search,roots,includeHidden)]?.takeIf{SystemClock.elapsedRealtime()-it.time<CACHE_MILLIS}?.value?.copy(cached=true)
    @Synchronized private fun remember(key:Key,snapshot:Snapshot,expectedGeneration:Long){
        if(generation!=expectedGeneration)return
        // Cache a bounded recent prefix, not millions of file metadata records. The live flow is
        // never truncated. A cached prefix remains incomplete so a revisit refreshes discovery.
        val value=if(snapshot.entries.size>CACHE_ENTRIES)snapshot.copy(entries=snapshot.entries.take(CACHE_ENTRIES),complete=false)else snapshot
        cache[key]=Cached(SystemClock.elapsedRealtime(),value)
    }
    fun snapshots(type:String,search:String="",recent:Collection<String> = emptyList(),
                  roots:List<File> = listOf(Environment.getExternalStorageDirectory()),includeHidden:Boolean=false):Flow<Snapshot> = channelFlow {
        val recentOnly=type=="最近"
        val request=key(type,search,roots,includeHidden)
        val expectedGeneration=synchronized(this@CategoryCatalog){generation}
        val saved=cached(type,search,roots,includeHidden)
        if(saved!=null)send(saved)
        if(saved?.complete==true){close();return@channelFlow}
        val signal=CancellationSignal()
        val worker=launch(Dispatchers.IO){
            try{
                val task=currentCoroutineContext()
                val found=LinkedHashMap<String,Entry>()
                var indexAvailable=true;var denied=0;var lastEmission=0L;var sentCount=-1
                suspend fun publish(complete:Boolean=false,force:Boolean=false){
                    task.ensureActive()
                    val now=SystemClock.elapsedRealtime()
                    if(!complete&&!force&&(found.size==sentCount||now-lastEmission<250))return
                    val ordered=found.values.sortedWith(compareByDescending<Entry>{it.modified}.thenBy{it.file.path})
                    val snapshot=Snapshot(if(recentOnly)ordered.take(RECENT_LIMIT)else ordered,complete,deniedDirectories=denied,indexAvailable=indexAvailable)
                    remember(request,snapshot,expectedGeneration);send(snapshot)
                    sentCount=found.size;lastEmission=now
                }
                fun accepts(file:File):Boolean{
                    if(!CatalogRules.matches(type,file.name)||(search.isNotBlank()&&!file.name.contains(search,true)))return false
                    if(!includeHidden){
                        if(file.name.startsWith('.'))return false
                        for(root in roots){val prefix=root.absolutePath+File.separator;if(file.path.startsWith(prefix)&&file.path.removePrefix(prefix).split(File.separatorChar).any{it.startsWith('.')})return false}
                    }
                    return true
                }
                for(path in recent.asSequence().take(RECENT_HISTORY_LIMIT)){task.ensureActive();val f=File(path);if(accepts(f)&&f.isFile)found[f.path]=Entry(f,f.lastModified(),f.length())}
                if(found.isNotEmpty())publish(force=true)
                val uri:Uri=when(type){"图片"->MediaStore.Images.Media.EXTERNAL_CONTENT_URI;"视频"->MediaStore.Video.Media.EXTERNAL_CONTENT_URI;"音乐"->MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;else->MediaStore.Files.getContentUri("external")}
                val duration=type=="视频"||type=="音乐"
                val projection=mutableListOf(MediaStore.MediaColumns.DATA,MediaStore.MediaColumns.DATE_MODIFIED,MediaStore.MediaColumns.SIZE)
                if(duration)projection+=MediaStore.Video.VideoColumns.DURATION
                val selection=CatalogRules.selection(type,search)
                try{
                    resolver.query(uri,projection.toTypedArray(),selection.clause,selection.args,MediaStore.MediaColumns.DATE_MODIFIED+" DESC",signal)?.use{cursor->
                        var accepted=0
                        while((!recentOnly||accepted<RECENT_INDEX_CANDIDATES)&&cursor.moveToNext()){
                            task.ensureActive()
                            val path=cursor.getString(0)?:continue;val f=File(path)
                            if(!accepts(f)||!f.isFile)continue
                            val modified=cursor.getLong(1)*1000L;val bytes=cursor.getLong(2)
                            found[path]=Entry(f,if(modified>0)modified else f.lastModified(),if(bytes>0)bytes else f.length(),if(duration)cursor.getLong(3).coerceAtLeast(0L)else 0L)
                            if(++accepted==64)publish(force=true)else if(accepted%64==0){publish();yield()}
                        }
                    }
                }catch(e:CancellationException){throw e}catch(_:Exception){task.ensureActive();indexAvailable=false}
                // Recent is an explicitly bounded recency view, not a seventh full-disk category.
                // Up to 300 history paths + 400 newest indexed candidates feed a stable Top 200;
                // six actual categories still continue to complete unindexed filesystem discovery.
                if(recentOnly){publish(complete=true,force=true);close();return@launch}
                publish(force=true)
                val indexed=found.keys.toHashSet()
                val walker=CatalogFileWalker(roots,includeHidden)
                var checked=0
                walker.forEachFile({task.ensureActive()}){file->
                    if(accepts(file)&&file.path !in indexed&&file.isFile){found[file.path]=Entry(file,file.lastModified(),file.length());publish()}
                    if(++checked%64==0)yield()
                }
                denied=walker.deniedDirectories;publish(complete=true,force=true)
                close()
            }catch(e:CancellationException){throw e}catch(e:Exception){close(e)}
        }
        // CancellationSignal stops an in-flight provider query; coroutine checks stop cursor reads
        // and filesystem traversal. Cooperative cancellation does not rely on thread interruption.
        awaitClose{runCatching{signal.cancel()};worker.cancel()}
    }.conflate()
    override fun close(){if(registered){resolver.unregisterContentObserver(observer);registered=false};invalidate()}
    companion object{
        private const val CACHE_MILLIS=15_000L
        private const val CACHE_ENTRIES=2048
        private const val RECENT_LIMIT=200
        private const val RECENT_HISTORY_LIMIT=300
        private const val RECENT_INDEX_CANDIDATES=400
        fun matches(type:String,name:String)=CatalogRules.matches(type,name)
    }
}

package com.wirelessalien.zipxtract.nativeui

import java.io.File
import java.util.Locale

/** One file-name policy for SQL candidates, filesystem discovery and UI classification. */
object CatalogRules {
    val extensions=mapOf(
        "图片" to listOf("jpg","jpeg","png","webp","gif","heic","avif","bmp"),
        "视频" to listOf("mp4","mkv","webm","mov","avi","m4v"),
        "音乐" to listOf("mp3","flac","m4a","ogg","wav","aac"),
        "安装包" to listOf("apk","apks","xapk"),
        "文档" to listOf("txt","pdf","doc","docx","xls","xlsx","ppt","pptx","md","csv","epub"),
        "压缩包" to listOf("zip","7z","rar","tar","gz","xz","bz2","zst","lzma","iso","tgz","tbz","tbz2","txz","zstd","lz4","lz","001")
    )
    private val splitArchive=Regex(".*\\.((zip|7z)\\.[0-9]+|[zr][0-9]+)$")
    fun matches(type:String,name:String):Boolean {
        if(type !in extensions)return true
        val normalized=name.lowercase(Locale.ROOT)
        return normalized.substringAfterLast('.',"") in extensions.getValue(type)||type=="压缩包"&&splitArchive.matches(normalized)
    }
    data class Selection(val clause:String?,val args:Array<String>?)
    fun selection(type:String,search:String):Selection {
        val clauses=mutableListOf<String>();val args=mutableListOf<String>()
        // Media collections are already type-filtered; Files needs a SQL-side extension predicate.
        if(type in listOf("文档","压缩包","安装包")){
            // MEDIA_TYPE_DOCUMENT is 6 on API 30+; older providers store documents as NONE (0).
            // Exclude huge image/audio/video populations before applying suffix predicates.
            clauses+=if(type=="文档")"media_type IN (0,6)"else"media_type = 0"
            val suffixes=extensions.getValue(type)
            val choices=suffixes.map{"LOWER(_display_name) LIKE ?"}.toMutableList()
            args.addAll(suffixes.map{"%.$it"})
            if(type=="压缩包")choices+=listOf("LOWER(_display_name) GLOB '*.z[0-9]*'", "LOWER(_display_name) GLOB '*.r[0-9]*'", "LOWER(_display_name) GLOB '*.zip.[0-9]*'", "LOWER(_display_name) GLOB '*.7z.[0-9]*'")
            clauses+="("+choices.joinToString(" OR ")+")"
        }
        if(search.isNotBlank()){
            clauses+="LOWER(_display_name) LIKE ? ESCAPE '\\'"
            args+="%"+search.lowercase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
        }
        return Selection(clauses.takeIf{it.isNotEmpty()}?.joinToString(" AND "),args.takeIf{it.isNotEmpty()}?.toTypedArray())
    }
}

/**
 * A cooperative producer, with cancellation checkpoints, not a truncated catalog.
 * Retains only pending/visited directories, not a cached copy of every encountered filesystem file.
 */
class CatalogFileWalker(roots:List<File>,private val includeHidden:Boolean=false) {
    private val trusted=roots.map{it.absoluteFile}.distinctBy{it.path}
    private val restricted=trusted.flatMap{listOf(File(it,"Android/data").path,File(it,"Android/obb").path)}
    private val queue=ArrayDeque<File>().apply{
        for(root in trusted){for(name in listOf("Download","Documents","DCIM","Pictures","Movies","Music"))add(File(root,name));add(root)}
    }
    private val visited=HashSet<String>()
    var deniedDirectories=0;private set
    suspend fun forEachFile(check:()->Unit, onDirectory:(suspend(File)->Unit)?=null, found:suspend(File)->Unit) {
        while(queue.isNotEmpty()){
            check();val directory=queue.removeFirst()
            if(restricted.any{directory.path==it||directory.path.startsWith(it+File.separator)})continue
            if(!includeHidden&&directory.name.startsWith('.'))continue
            val paths=try{directory.canonicalFile to File(directory.parentFile?.canonicalFile,directory.name)}catch(_:Exception){deniedDirectories++;continue}
            val canonical=paths.first
            // Permit an explicitly supplied storage-root alias, but never follow child symlinks.
            if(trusted.none{it.path==directory.path}&&canonical!=paths.second)continue
            if(!visited.add(canonical.path))continue
            val children=try{directory.listFiles()}catch(_:SecurityException){null}
            if(children==null){if(runCatching{directory.exists()}.getOrDefault(false))deniedDirectories++;continue}
            if (onDirectory != null && trusted.none { it.path == directory.path }) onDirectory(directory)
            for(file in children){
                check()
                if(!includeHidden&&file.name.startsWith('.'))continue
                if(file.isDirectory)queue.addLast(file)else found(file)
            }
        }
    }
}

/* Modified 2026-11-03. GPL-3.0-or-later. */
package com.wirelessalien.zipxtract.nativeui

import com.wirelessalien.zipxtract.helper.ArchiveSafety
import java.io.File
import java.io.IOException

object FileManagerKernel {
    fun validName(value:String):Boolean=value.isNotBlank() && value!="." && value!=".." && value.none{it=='/'||it=='\\'||it=='\u0000'}
    fun rename(file:File,name:String):File {
        require(validName(name)){"名称不能包含路径分隔符"}
        val target=File(file.parentFile,name)
        if(target.exists())throw IOException("同名文件已存在")
        if(ArchiveSafety.isLink(file)||!file.renameTo(target))throw IOException("无法重命名，请检查权限")
        return target
    }
    fun createFolder(parent:File,name:String):File {
        require(validName(name)){"文件夹名称无效"}
        return File(parent,name).apply{if(!mkdir())throw IOException("无法创建文件夹，可能同名目录已存在")}
    }
    fun copyOrMove(paths:List<String>,destination:File,move:Boolean,check:()->Unit={},progress:(Int)->Unit={}):List<String> {
        val plan=ArchiveSafety.capture(paths,check);plan.assertOutputOutsideSources(destination)
        if(!destination.isDirectory)throw IOException("目标目录不可用")
        if(plan.roots.map{it.name}.distinct().size!=plan.roots.size)throw IOException("选择项存在同名，请分开复制")
        val targets=plan.roots.associateWith{File(destination,it.name)}
        if(targets.values.any{it.exists()})throw IOException("目标存在同名项目，未覆盖任何文件")
        for((index,entry) in plan.entries.withIndex()){
            check()
            val root=plan.roots.first{entry.file==it||entry.file.path.startsWith(it.path+File.separator)}
            val targetRoot=targets.getValue(root)
            val target=if(entry.file==root)targetRoot else File(targetRoot,entry.file.relativeTo(root).path)
            if(entry.directory){if(!target.mkdir())throw IOException("无法创建目标目录")}
            else {
                if(!target.createNewFile())throw IOException("目标文件冲突")
                entry.file.inputStream().use{input->target.outputStream().use{output->
                    val buffer=ByteArray(65536)
                    while(true){check();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n)}
                }}
                if(target.inputStream().use{ArchiveSafety.digest(it,check)}!=entry.hash)throw IOException("复制校验失败，源文件已保留")
                target.setLastModified(entry.modified)
            }
            progress((index+1)*100/plan.entries.size)
        }
        plan.assertUnchanged(check)
        return if(move)plan.deleteVerifiedSources(check) else emptyList()
    }
    fun delete(paths:List<String>,check:()->Unit={}):List<String> = ArchiveSafety.capture(paths,check).deleteVerifiedSources(check)
}

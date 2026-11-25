package com.wirelessalien.zipxtract.nativeui
import android.content.Context
import java.io.File
import java.security.KeyStore

data class CreationOptions(val format:String="7Z",val passwordEnabled:Boolean=false,
    val password:String="",val split:Boolean=false,val splitMb:Long=64,val delete:Boolean=false){
    companion object {
        fun sourceDirectory(paths:List<String>):File=File(paths.first()).absoluteFile.parentFile
            ?:throw IllegalArgumentException("没有源目录")
        fun retireStoredOptions(context:Context){
            context.getSharedPreferences("native_creation_secure",0).edit().clear().apply()
            runCatching{KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry("sevenzip.creation.password.v1")}
        }
    }
}

package com.wirelessalien.zipxtract

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.helper.SafeArchiveEngine
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Independent entry/content oracle: close-looking names must coexist, never URL-decode together. */
@RunWith(AndroidJUnit4::class)
class FilenameArchive107Test {
    private lateinit var root:File
    private lateinit var source:File
    private lateinit var expected:Map<String,ByteArray>
    @Before fun setup(){
        val cache=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        root=File(cache,"filename-qa-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile==cache&&root.mkdirs())
        source=File(root,"中文 +%20%25 😀 #?目录").apply{mkdir()}
        val names=listOf("中文.txt","a b.txt","a+b.txt","a%20b.txt","a%25b.txt","a%b.txt","a%2Gb.txt","😀🧪.txt","a#b?.txt","é.txt","e\u0301.txt","%2e%2e%2f.txt")
        expected=names.mapIndexed{index,name->
            val file=File(source,"二级 +%25/$name");check(file.parentFile!!.mkdirs()||file.parentFile!!.isDirectory)
            val bytes="entry=$index; name=$name; 😀 %20 +".toByteArray(Charsets.UTF_8)
            file.writeBytes(bytes)
            "${source.name}/二级 +%25/$name" to bytes
        }.toMap()
    }
    @After fun cleanup(){if(::root.isInitialized){check(root.name.startsWith("filename-qa-"));root.deleteRecursively()}}
    private fun create(format:String)=SafeArchiveEngine().create(listOf(source.path),SafeArchiveEngine.Options(format,"压缩 +%20%25 😀 #?",File(root,"输出 +%25"))).archive
    private fun verify(actual:Map<String,ByteArray>){
        assertEquals(expected.keys,actual.keys)
        for((name,bytes)in expected)assertArrayEquals(name,bytes,actual[name])
        for((name,bytes)in expected)assertArrayEquals("source preserved",bytes,File(root,name).readBytes())
    }
    @Test fun zipNamesAndPayloadsRemainDistinctForJavaZipReader(){
        val actual=linkedMapOf<String,ByteArray>()
        ZipFile(create("ZIP")).use{zip->
            val entries=zip.entries()
            while(entries.hasMoreElements()){
                val entry=entries.nextElement();if(entry.isDirectory)continue
                check(!actual.containsKey(entry.name)){"duplicate identity"}
                actual[entry.name]=zip.getInputStream(entry).use{it.readBytes()}
            }
        }
        verify(actual)
    }
    @Test fun utf8TarNamesAndPayloadsRemainDistinct(){
        val actual=linkedMapOf<String,ByteArray>()
        TarArchiveInputStream(create("TAR_ONLY").inputStream(),"UTF-8").use{tar->
            while(true){
                val entry=tar.nextTarEntry?:break;if(entry.isDirectory)continue
                check(!actual.containsKey(entry.name)){"duplicate identity"}
                actual[entry.name]=tar.readBytes()
            }
        }
        verify(actual)
    }
}

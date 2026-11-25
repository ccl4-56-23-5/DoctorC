package com.wirelessalien.zipxtract.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.helper.*
import com.wirelessalien.zipxtract.nativeui.FileManagerKernel
import com.wirelessalien.zipxtract.nativeui.PhotoTimeSync
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class NativeFileService:Service(){
    private val scope=CoroutineScope(Dispatchers.IO+SupervisorJob())
    private val cancelled=AtomicBoolean(false)
    private var running=false
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){cancelled.set(true)}}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){super.onCreate()
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("native_files","文件操作",NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this,receiver,IntentFilter(BroadcastConstants.ACTION_CANCEL_OPERATION),ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(running)return START_NOT_STICKY
        val notification=NotificationCompat.Builder(this,"native_files").setSmallIcon(R.drawable.art_notification)
            .setContentTitle("文件操作").setProgress(100,0,true).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(81,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else startForeground(81,notification)
        val job=intent?.getStringExtra("job")?:run{stopSelf(startId);return START_NOT_STICKY}
        running=true
        scope.launch {
            val dao=FileOperationsDao(this@NativeFileService)
            val lock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SevenZip:Files")
            lock.acquire(60*60*1000L)
            try{
                val files=dao.getFilesForJob(job)
                val action=intent.getStringExtra("operation")?:"copy"
                if (action == PhotoTimeSync.ACTION) {
                    val result = PhotoTimeSync.run(files.map(::File), cancelled::get) { percent ->
                        EventBus.emit(AppEvent.ArchiveProgress(percent, "${PhotoTimeSync.TITLE} $percent%"))
                    }
                    if (result.updatedPaths.isNotEmpty()) {
                        FileUtils.scanFiles(this@NativeFileService, result.updatedPaths)
                    }
                    EventBus.emit(AppEvent.PhotoTimeSyncComplete(result.message()))
                    return@launch
                }
                val destination=File(intent.getStringExtra("destination")?:File(files.first()).parent!!)
                val check={ArchiveSafety.checkCancelled{cancelled.get()}}
                val retained=if(action=="delete")FileManagerKernel.delete(files,check)else
                    FileManagerKernel.copyOrMove(files,destination,action=="move",check){EventBus.emit(AppEvent.ArchiveProgress(it))}
                EventBus.emit(AppEvent.ArchiveComplete(destination.path,if(retained.isEmpty())"操作完成" else "操作完成，${retained.size} 个项目因变化或权限问题保留"))
                FileUtils.scanFiles(this@NativeFileService,files+destination.path)
            }catch(e:Exception){EventBus.emit(AppEvent.ArchiveError(e.message?:"操作未完成"))}
            finally{dao.deleteFilesForJob(job);if(lock.isHeld)lock.release();stopForeground(STOP_FOREGROUND_REMOVE);running=false;stopSelf(startId)}
        }
        return START_NOT_STICKY
    }
    override fun onDestroy(){cancelled.set(true);scope.cancel();unregisterReceiver(receiver);super.onDestroy()}
}

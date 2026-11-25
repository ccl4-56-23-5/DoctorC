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
import com.wirelessalien.zipxtract.constant.ServiceConstants as C
import com.wirelessalien.zipxtract.helper.*
import com.wirelessalien.zipxtract.nativeui.ActiveOperation
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Every UI extraction route shares this service; cancellation waits for decoder/file closure. */
class SafeExtractionService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cancelled = AtomicBoolean()
    private var running = false
    private var percent = 0
    private var lastUpdate = 0L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BroadcastConstants.ACTION_CANCEL_OPERATION) {
                cancelled.set(true)
                getSystemService(NotificationManager::class.java).notify(ID, notification("正在取消并关闭文件…"))
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "解压文件", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, receiver, IntentFilter(BroadcastConstants.ACTION_CANCEL_OPERATION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun notification(text: String = "正在解压 $percent%") = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.art_notification).setContentTitle("解压文件").setContentText(text)
        .setProgress(100, percent, percent == 0).setOngoing(true).setOnlyAlertOnce(true)
        .addAction(R.drawable.art_close, "取消", PendingIntent.getBroadcast(this, ID,
            Intent(BroadcastConstants.ACTION_CANCEL_OPERATION).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val job = intent?.getStringExtra(C.EXTRA_JOB_ID)
        if (running) {
            if (job != null) FileOperationsDao(this).deleteFilesForJob(job)
            return START_NOT_STICKY // Do not emit another task's terminal event or reset its cancel flag.
        }
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification())
        if (job == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY }
        val destination = intent.getStringExtra(C.EXTRA_DESTINATION_PATH)
        val password = intent.getStringExtra(C.EXTRA_PASSWORD)
        val selected = intent.getStringArrayListExtra(C.EXTRA_ITEMS_TO_EXTRACT)?.toList()
        running = true; cancelled.set(false); percent = 0
        if (!ActiveOperation.busy()) ActiveOperation.start("解压文件", "")
        scope.launch {
            val dao = FileOperationsDao(this@SafeExtractionService)
            val lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZipDoctorC:Extract")
            var output: File? = null
            var message: String? = null
            try {
                lock.acquire(60 * 60 * 1000L)
                val files = dao.getFilesForJob(job)
                if (files.size != 1) throw IOException("每次解压需要一个压缩包入口")
                val source = File(files.single())
                val parent = destination?.let(::File) ?: if (FileUtils.isInternalPath(this@SafeExtractionService, source.path))
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) else source.parentFile!!
                output = SafeExtractionEngine({ cancelled.get() }) { value ->
                    percent = value
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 200) {
                        lastUpdate = now
                        getSystemService(NotificationManager::class.java).notify(ID, notification())
                        EventBus.emit(AppEvent.ExtractionProgress(value))
                    }
                }.extract(source, parent, password, selected)
                runCatching { FileUtils.scanFiles(this@SafeExtractionService, listOf(output.path)) }
            } catch (e: Exception) {
                message = if (cancelled.get() || e is java.util.concurrent.CancellationException) "解压已取消，原压缩包保留"
                    else e.message ?: "解压失败，请检查压缩包、密码和可用空间"
            } finally {
                dao.deleteFilesForJob(job)
                if (lock.isHeld) lock.release()
                running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            }
            val result = output
            if (result != null && message == null) EventBus.emit(AppEvent.ExtractionComplete(result.path))
            else EventBus.emit(AppEvent.ExtractionError(message ?: "解压未完成"))
            val open = Intent(this@SafeExtractionService, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_DIRECTORY; putExtra(MainActivity.EXTRA_DIRECTORY_PATH, result?.path)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            getSystemService(NotificationManager::class.java).notify(ID + 1, NotificationCompat.Builder(this@SafeExtractionService, CHANNEL)
                .setSmallIcon(R.drawable.art_notification).setContentTitle(if (message == null) "解压完成" else "解压未完成")
                .setContentText(message ?: result?.name).setStyle(NotificationCompat.BigTextStyle().bigText(message ?: result?.path))
                .setContentIntent(PendingIntent.getActivity(this@SafeExtractionService, ID + 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build())
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { cancelled.set(true); scope.cancel(); unregisterReceiver(receiver); super.onDestroy() }
    companion object { private const val CHANNEL = "safe_extract"; private const val ID = 181 }
}

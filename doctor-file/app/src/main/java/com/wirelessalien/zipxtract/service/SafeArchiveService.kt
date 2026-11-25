/* Copyright (C) 2026. GPL-3.0-or-later; see License. */
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
import kotlinx.coroutines.*
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SafeArchiveService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cancelled = AtomicBoolean(false)
    private var running = false
    private var phase = "正在准备压缩…"
    private var percent = 0
    private var lastUpdate = 0L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BroadcastConstants.ACTION_CANCEL_OPERATION) cancelled.set(true)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.compress_archive_notification_name), NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, receiver, IntentFilter(BroadcastConstants.ACTION_CANCEL_OPERATION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun notification(): Notification {
        val intent = Intent(BroadcastConstants.ACTION_CANCEL_OPERATION).setPackage(packageName)
        val cancel = PendingIntent.getBroadcast(this, 71, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(getString(R.string.archive_ongoing)).setContentText(phase)
            .setProgress(100, percent, !phase.startsWith("正在压缩"))
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancel).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) {
            EventBus.emit(AppEvent.ArchiveError(getString(R.string.archive_busy)))
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification())
        val jobId = intent?.getStringExtra(C.EXTRA_JOB_ID)
        if (jobId == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY }
        running = true; cancelled.set(false)
        scope.launch {
            val dao = FileOperationsDao(this@SafeArchiveService)
            val lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SevenZipAndroid:Archive")
            lock.acquire(60 * 60 * 1000L)
            try {
                val paths = dao.getFilesForJob(jobId)
                val password = intent.getStringExtra(C.EXTRA_PASSWORD)?.takeIf { it.isNotEmpty() }
                val format = intent.getStringExtra(EXTRA_FORMAT) ?: "7Z"
                val params = ZipParameters().apply {
                    compressionMethod = (intent.getSerializableExtra(C.EXTRA_COMPRESSION_METHOD) as? CompressionMethod) ?: CompressionMethod.DEFLATE
                    compressionLevel = (intent.getSerializableExtra(C.EXTRA_COMPRESSION_LEVEL) as? CompressionLevel) ?: CompressionLevel.NORMAL
                    isEncryptFiles = intent.getBooleanExtra(C.EXTRA_IS_ENCRYPTED, false) && password != null
                    if (isEncryptFiles) {
                        encryptionMethod = (intent.getSerializableExtra(C.EXTRA_ENCRYPTION_METHOD) as? EncryptionMethod) ?: EncryptionMethod.AES
                        aesKeyStrength = (intent.getSerializableExtra(C.EXTRA_AES_STRENGTH) as? AesKeyStrength) ?: AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                val output = intent.getStringExtra(C.EXTRA_DESTINATION_PATH)?.takeIf { it.isNotBlank() }
                    ?: File(paths.first()).parent ?: throw java.io.IOException("没有输出目录")
                val options = SafeArchiveEngine.Options(format, intent.getStringExtra(C.EXTRA_ARCHIVE_NAME) ?: "Archive", File(output),
                    password, if (format == "ZIP") 5 else intent.getIntExtra(C.EXTRA_COMPRESSION_LEVEL, 5),
                    intent.getBooleanExtra(C.EXTRA_SOLID, false), intent.getIntExtra(C.EXTRA_THREAD_COUNT, 2), params,
                    intent.getLongExtra(C.EXTRA_SPLIT_SIZE, 0), intent.getBooleanExtra(EXTRA_DELETE, false))
                val engine = SafeArchiveEngine({ cancelled.get() }, { p ->
                    percent = p
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 250) {
                        lastUpdate = now
                        getSystemService(NotificationManager::class.java).notify(ID, notification())
                        EventBus.emit(AppEvent.ArchiveProgress(p))
                    }
                }, { value ->
                    phase = value
                    getSystemService(NotificationManager::class.java).notify(ID, notification())
                    EventBus.emit(AppEvent.ArchiveProgress(100, value))
                })
                val result = engine.create(paths, options)
                val message = when {
                    result.retainedSources.isNotEmpty() -> getString(R.string.archive_some_retained, result.retainedSources.size)
                    result.deleteRequested -> getString(R.string.archive_deleted_sources)
                    else -> getString(R.string.archive_verified)
                }
                FileUtils.scanFiles(this@SafeArchiveService, paths + result.archive.path)
                EventBus.emit(AppEvent.ArchiveComplete(result.archive.parent, message))
                showResult(result.archive.name, message, result.archive.parent)
            } catch (e: Exception) {
                val message = if (cancelled.get() || e is java.util.concurrent.CancellationException)
                    getString(R.string.archive_cancelled_retained) else "${e.message ?: getString(R.string.general_error_msg)}\n${getString(R.string.archive_failure_retained)}"
                EventBus.emit(AppEvent.ArchiveError(message))
                showResult(getString(R.string.error), message, null)
            } finally {
                dao.deleteFilesForJob(jobId)
                if (lock.isHeld) lock.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                running = false
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    private fun showResult(title: String, message: String, path: String?) {
        val open = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_DIRECTORY
            putExtra(MainActivity.EXTRA_DIRECTORY_PATH, path)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(this, 72, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(ID + 1,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pending).setAutoCancel(true).build())
    }
    override fun onDestroy() { cancelled.set(true); scope.cancel(); unregisterReceiver(receiver); super.onDestroy() }
    companion object {
        const val EXTRA_FORMAT = "safe_archive_format"
        const val EXTRA_DELETE = "safe_delete_sources"
        private const val CHANNEL = "safe_archive"
        private const val ID = 71
    }
}

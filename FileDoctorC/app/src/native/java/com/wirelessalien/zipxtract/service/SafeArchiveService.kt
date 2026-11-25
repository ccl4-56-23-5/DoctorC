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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SafeArchiveService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cancelled = AtomicBoolean(false)
    @Volatile private var running = false
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
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.art_notification)
            .setContentTitle(getString(R.string.archive_ongoing)).setContentText(phase)
            .setProgress(100, percent, !phase.startsWith("正在压缩"))
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.art_close, getString(R.string.cancel), cancel).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) {
            intent?.getStringExtra(C.EXTRA_JOB_ID)?.let { FileOperationsDao(this).deleteFilesForJob(it) }
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification())
        val jobId = intent?.getStringExtra(C.EXTRA_JOB_ID)
        if (jobId == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY }
        running = true; cancelled.set(false); percent = 0; lastUpdate = 0L; phase = "正在准备压缩…"
        if (!com.wirelessalien.zipxtract.nativeui.ActiveOperation.busy())
            com.wirelessalien.zipxtract.nativeui.ActiveOperation.start("创建压缩包", intent.getStringExtra(C.EXTRA_ARCHIVE_NAME) ?: "Archive")
        scope.launch {
            val dao = FileOperationsDao(this@SafeArchiveService)
            val lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SevenZipAndroid:Archive")
            var result: SafeArchiveEngine.Result? = null
            var message = ""
            try {
                lock.acquire(60 * 60 * 1000L)
                val paths = dao.getFilesForJob(jobId)
                if (paths.isEmpty()) throw java.io.IOException("没有待压缩的源文件")
                val password = intent.getStringExtra(C.EXTRA_PASSWORD)?.takeIf { it.isNotEmpty() }
                val format = intent.getStringExtra(EXTRA_FORMAT) ?: "7Z"
                val output = intent.getStringExtra(C.EXTRA_DESTINATION_PATH)?.takeIf { it.isNotBlank() }
                    ?: File(paths.first()).parent ?: throw java.io.IOException("没有输出目录")
                val options = SafeArchiveEngine.Options(format,
                    intent.getStringExtra(C.EXTRA_ARCHIVE_NAME) ?: "Archive", File(output), password,
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
                    EventBus.emit(AppEvent.ArchiveProgress(percent, value))
                })
                val created = engine.create(paths, options)
                result = created
                message = when {
                    created.retainedSources.isNotEmpty() -> getString(R.string.archive_some_retained, created.retainedSources.size)
                    created.deleteRequested -> getString(R.string.archive_deleted_sources)
                    else -> getString(R.string.archive_verified)
                }
                runCatching { FileUtils.scanFiles(this@SafeArchiveService, paths + created.archive.path) }
            } catch (e: Exception) {
                result = null
                message = if (cancelled.get() || e is java.util.concurrent.CancellationException)
                    getString(R.string.archive_cancelled_retained) else "${e.message ?: getString(R.string.general_error_msg)}\n${getString(R.string.archive_failure_retained)}"
            } finally {
                runCatching { dao.deleteFilesForJob(jobId) }.onFailure {
                    android.util.Log.e("SafeArchiveService", "Task record cleanup failed", it)
                }
                if (lock.isHeld) lock.release()
            }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                running = false
                // Main-thread handoff prevents a new start from being stopped by this job.
                val created = result
                if (created != null) EventBus.emit(AppEvent.ArchiveComplete(created.archive.parent, message))
                else EventBus.emit(AppEvent.ArchiveError(message))
                runCatching { showResult(created?.archive?.name ?: getString(R.string.error), message, created?.archive?.parent) }
                stopSelf() // Includes rejected duplicate start IDs; there is no other accepted job.
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
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.art_notification)
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

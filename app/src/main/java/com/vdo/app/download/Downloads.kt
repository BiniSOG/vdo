package com.vdo.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vdo.app.MainActivity
import com.vdo.app.R
import com.vdo.app.data.DlKind
import com.vdo.app.data.DlStatus
import com.vdo.app.data.DownloadTask
import com.vdo.app.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The download queue: owns every task, its state and its lifecycle. */
object Downloads {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private var lastPersist = 0L

    val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        tasks.value = runCatching {
            val arr = JSONArray(Store.readJson("downloads") ?: "[]")
            (0 until arr.length()).map { DownloadTask.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList()).map {
            // Anything that was mid-flight when the app was killed can be resumed from its cache.
            if (it.active || it.status == DlStatus.QUEUED) it.copy(status = DlStatus.PAUSED, speed = 0) else it
        }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = pump()
            })
        }
    }

    fun enqueue(
        url: String, title: String, kind: DlKind, headers: Map<String, String>,
        maxHeight: Int = 0, private: Boolean = false, isAudio: Boolean = false,
    ): DownloadTask {
        val task = DownloadTask(
            id = UUID.randomUUID().toString().take(12), url = url, title = Exporter.sanitize(title),
            kind = kind, headers = headers, maxHeight = maxHeight, isPrivate = private, isAudio = isAudio,
        )
        tasks.update { listOf(task) + it }
        persist(true)
        pump()
        return task
    }

    fun pause(id: String) {
        mutate(id) { if (it.finished) it else it.copy(status = DlStatus.PAUSED, speed = 0, error = null) }
        jobs[id]?.cancel()
        persist(true)
    }

    fun resume(id: String) {
        mutate(id) { if (it.finished || it.active) it else it.copy(status = DlStatus.QUEUED, error = null) }
        pump()
    }

    fun pauseAll() = tasks.value.filter { it.active || it.status == DlStatus.QUEUED }.forEach { pause(it.id) }

    fun resumeAll() = tasks.value
        .filter { it.status == DlStatus.PAUSED || it.status == DlStatus.FAILED }.forEach { resume(it.id) }

    fun remove(id: String, deleteFile: Boolean) {
        val task = tasks.value.firstOrNull { it.id == id } ?: return
        val job = jobs[id]
        tasks.update { list -> list.filter { it.id != id } }
        job?.cancel()
        scope.launch {
            job?.join()
            DownloadEngine.workDir(app, id).deleteRecursively()
            if (deleteFile) Exporter.delete(app, task.output)
        }
        persist(true)
    }

    fun rename(id: String, title: String) {
        mutate(id) { it.copy(title = Exporter.sanitize(title)) }
        persist(true)
    }

    fun setPrivate(id: String, private: Boolean, onDone: (Throwable?) -> Unit = {}) {
        val task = tasks.value.firstOrNull { it.id == id && it.finished } ?: return
        scope.launch {
            val r = runCatching { Exporter.relocate(app, task, private) }
            r.onSuccess { out -> mutate(id) { it.copy(output = out, isPrivate = private) }; persist(true) }
            launch(Dispatchers.Main) { onDone(r.exceptionOrNull()) }
        }
    }

    private fun mutate(id: String, f: (DownloadTask) -> DownloadTask) {
        tasks.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private fun persist(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersist < 3000) return
        lastPersist = now
        Store.writeJson("downloads", JSONArray(tasks.value.map { it.toJson() }).toString())
    }

    private fun networkOk(): Boolean {
        if (!Store.wifiOnly) return true
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Starts queued tasks while there are free slots. */
    @Synchronized
    fun pump() {
        if (!::app.isInitialized) return
        val ok = networkOk()
        if (!ok) {
            // Wi-Fi only: park running downloads until an unmetered network comes back.
            tasks.value.filter { it.active }.forEach { t ->
                mutate(t.id) { it.copy(status = DlStatus.QUEUED, speed = 0, error = "Waiting for Wi-Fi") }
                jobs[t.id]?.cancel()
            }
            tasks.value.filter { it.status == DlStatus.QUEUED && it.error == null }
                .forEach { t -> mutate(t.id) { it.copy(error = "Waiting for Wi-Fi") } }
            return
        }
        val limit = Store.maxConcurrent.coerceIn(1, 8)
        val queued = tasks.value.filter { it.status == DlStatus.QUEUED && !jobs.containsKey(it.id) }.reversed()
        for (t in queued) {
            if (jobs.size >= limit) break
            start(t)
        }
    }

    private fun start(task: DownloadTask) {
        mutate(task.id) { it.copy(status = DlStatus.RUNNING, error = null) }
        if (!DownloadService.running) {
            runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
        }
        val current = tasks.value.first { it.id == task.id }
        jobs[task.id] = scope.launch {
            try {
                DownloadEngine(app, current) { id, f -> mutate(id, f); persist() }.run()
                tasks.value.firstOrNull { it.id == task.id }?.let { DownloadService.notifyDone(app, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                mutate(task.id) {
                    it.copy(status = DlStatus.FAILED, speed = 0, error = e.message ?: e.javaClass.simpleName)
                }
            } finally {
                jobs.remove(task.id)
                persist(true)
                pump()
            }
        }
    }
}

/** Keeps the process alive while downloads run and shows their progress. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        channels(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, ID, build(emptyList()), type) }
        scope.launch {
            var idle = 0
            while (true) {
                val active = Downloads.tasks.value.filter { it.active }
                if (active.isEmpty()) {
                    if (++idle >= 3) break // small grace period between queued items
                } else {
                    idle = 0
                    getSystemService(NotificationManager::class.java).notify(ID, build(active))
                }
                delay(1000)
            }
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) Downloads.pauseAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun build(active: List<DownloadTask>): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).putExtra("tab", "downloads"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this, 2, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_vdo)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setColor(0xFF8B6CFF.toInt())
            .addAction(0, "Pause all", pause)
        if (active.isEmpty()) return b.setContentTitle("Preparing download…").setProgress(0, 0, true).build()
        val first = active.first()
        val speed = active.sumOf { it.speed }
        val fractions = active.map { it.fraction }
        val known = fractions.all { it >= 0 }
        val pct = if (known) (fractions.average() * 100).toInt() else 0
        b.setContentTitle(if (active.size == 1) first.title else "Downloading ${active.size} files")
        b.setContentText(
            if (active.all { it.status == DlStatus.MERGING }) "Merging…"
            else buildString {
                if (known) append("$pct% · ")
                append(Formatter.formatFileSize(this@DownloadService, speed)).append("/s")
            },
        )
        b.setProgress(100, pct, !known || active.all { it.status == DlStatus.MERGING })
        return b.build()
    }

    companion object {
        @Volatile var running = false
        private const val ID = 41
        private const val CH_PROGRESS = "downloads"
        private const val CH_DONE = "downloads_done"
        private const val ACTION_PAUSE_ALL = "com.vdo.app.PAUSE_ALL"

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        fun notifyDone(ctx: Context, task: DownloadTask) {
            if (!Store.notifyDone || task.status != DlStatus.COMPLETED || task.isPrivate) return
            channels(ctx)
            val open = PendingIntent.getActivity(
                ctx, 3, Intent(ctx, MainActivity::class.java).putExtra("tab", "downloads"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_vdo)
                .setContentTitle("Download complete")
                .setContentText(task.title)
                .setAutoCancel(true)
                .setColor(0xFF8B6CFF.toInt())
                .setContentIntent(open)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(task.id.hashCode(), n) }
        }
    }
}

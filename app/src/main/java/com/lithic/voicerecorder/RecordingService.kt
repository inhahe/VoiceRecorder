package com.lithic.voicerecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.io.File

/**
 * Foreground service that owns the active [Recorder]. Runs as a `microphone`-typed foreground
 * service so capture continues if the user leaves the app or the screen turns off.
 *
 * The UI binds to this service to start/stop recording and to poll elapsed time.
 */
class RecordingService : Service() {

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()

    private var recorder: Recorder? = null

    /** Elapsed-time accounting that excludes paused spans. */
    private var segmentStartElapsed = 0L
    private var accumulatedMillis = 0L

    /** Live spectrum source, fed by the capture loop; the UI polls [spectrum] while recording. */
    private val analyzer = SpectrumAnalyzer()

    /** Latest 0..1 frequency-band magnitudes for the recording meter (empty when idle). */
    fun spectrum(): FloatArray = if (isRecording) analyzer.bands else EMPTY_SPECTRUM

    /**
     * Digital input gain in dB, applied to everything captured from here on. Setting it while a
     * recording is in progress takes effect immediately; otherwise it seeds the next recording.
     */
    var gainDb: Int = 0
        set(value) {
            field = value.coerceIn(Gain.MIN_DB, Gain.MAX_DB)
            recorder?.gainDb = field
        }

    /**
     * Which capture device to record from, as a persisted [InputDevices.InputOption.key]
     * ([InputDevices.AUTOMATIC] = let Android choose). Setting it during a recording re-routes the
     * live stream; otherwise it seeds the next one.
     */
    var inputDeviceKey: String = InputDevices.AUTOMATIC
        set(value) {
            field = value
            recorder?.preferredInput = InputDevices.resolve(this, value)
        }

    /**
     * Whether platform voice processing is applied. Unlike [gainDb] and [inputDeviceKey] this is
     * baked into `AudioRecord` at construction, so changing it only affects the *next* recording.
     */
    var captureSource: CaptureSource = CaptureSource.PROCESSED

    /** Label of the device capture actually landed on, or null when idle. */
    fun routedInputLabel(): String? = if (isRecording) recorder?.routedInputLabel else null

    /** True while audio has clipped within the last [CLIP_HOLD_MS] — drives the CLIP indicator. */
    fun isClipping(): Boolean {
        val last = recorder?.lastClipElapsedMs ?: return false
        return last > 0L && SystemClock.elapsedRealtime() - last < CLIP_HOLD_MS
    }

    /** Samples that clipped during the current (or just-finished) recording. */
    var clippedSamples: Long = 0L
        private set

    /** Absolute path of the file currently being written, or null when idle. */
    var currentFile: File? = null
        private set

    var isRecording: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    /** Set when recording ends abnormally; the UI reads and clears it. */
    var lastError: String? = null

    /** Optional callback invoked (on the main thread) when recording stops for any reason. */
    var onStopped: ((file: File?, error: String?) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started so it can outlive UI unbinding; stopped explicitly in stopRecording().
        return START_NOT_STICKY
    }

    /** Milliseconds of actual recording (excluding paused spans); 0 when idle. */
    fun elapsedMillis(): Long = when {
        !isRecording -> 0L
        isPaused -> accumulatedMillis
        else -> accumulatedMillis + (SystemClock.elapsedRealtime() - segmentStartElapsed)
    }

    fun startRecording(spec: AudioFormatSpec, outputFile: File) {
        check(!isRecording) { "Already recording" }
        lastError = null

        outputFile.parentFile?.mkdirs()
        clippedSamples = 0L
        val r = RecorderFactory.create(
            spec, outputFile, analyzer, gainDb,
            InputDevices.resolve(this, inputDeviceKey),
            CaptureSource.androidSource(captureSource, CaptureSource.unprocessedSupported(this))
        )

        startForegroundWithNotification(outputFile.name)
        try {
            r.start()
        } catch (t: Throwable) {
            r.cancel()
            stopForegroundCompat()
            throw t
        }

        recorder = r
        currentFile = outputFile
        segmentStartElapsed = SystemClock.elapsedRealtime()
        accumulatedMillis = 0L
        isRecording = true
        isPaused = false
    }

    /** Pauses capture without finalising. No-op if not recording or already paused. */
    fun pauseRecording() {
        if (!isRecording || isPaused) return
        recorder?.pause()
        accumulatedMillis += SystemClock.elapsedRealtime() - segmentStartElapsed
        isPaused = true
        updateNotification()
    }

    /** Resumes capture after [pauseRecording]. No-op if not paused. */
    fun resumeRecording() {
        if (!isRecording || !isPaused) return
        recorder?.resume()
        segmentStartElapsed = SystemClock.elapsedRealtime()
        isPaused = false
        updateNotification()
    }

    /** Stops and finalises the file. Returns the completed file, or null on failure. */
    fun stopRecording(): File? {
        if (!isRecording) return null
        val file = currentFile
        var error: String? = null
        // Read the clip tally before the recorder is dropped; the UI reports it after saving.
        clippedSamples = recorder?.clippedSamples ?: 0L
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        } finally {
            recorder = null
            isRecording = false
            isPaused = false
            currentFile = null
            stopForegroundCompat()
            stopSelf()
        }
        lastError = error
        onStopped?.invoke(if (error == null) file else null, error)
        return if (error == null) file else null
    }

    /** Aborts recording and deletes the partial file. */
    fun cancelRecording() {
        if (!isRecording) return
        try {
            recorder?.cancel()
        } catch (_: Throwable) {
        } finally {
            recorder = null
            isRecording = false
            isPaused = false
            currentFile = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun buildNotification(fileName: String, paused: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (paused) R.string.notification_paused_title else R.string.notification_recording_title
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(title))
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun startForegroundWithNotification(fileName: String) {
        createChannel()
        val notification = buildNotification(fileName, paused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Re-posts the ongoing notification to reflect the current paused state. */
    private fun updateNotification() {
        val name = currentFile?.name ?: return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(name, isPaused))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private val EMPTY_SPECTRUM = FloatArray(0)

        /** How long the CLIP indicator stays lit after the last clipped sample. */
        private const val CLIP_HOLD_MS = 1200L
    }
}

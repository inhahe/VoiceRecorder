package com.lithic.voicerecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
     * live stream (and resumes a recording that was paused waiting for an input — see
     * [pausedForInput]); otherwise it seeds the next one.
     */
    var inputDeviceKey: String = InputDevices.AUTOMATIC
        set(value) {
            field = value
            syncInput()
        }

    /**
     * True while the recording is paused because its chosen input isn't attached, as opposed to
     * paused by the user. When a preferred device disappears, Android re-routes the stream to the
     * phone's own mic by itself, so the service pauses instead. It resumes once that input is back
     * or another one is chosen. The UI shows its "input disconnected" prompt while this is set.
     */
    var pausedForInput: Boolean = false
        private set

    /**
     * Called on the main thread when the service pauses a recording because its input went
     * (`true`), or resumes one it had paused for that reason (`false`).
     */
    var onInputPauseChanged: ((paused: Boolean) -> Unit)? = null

    /** Device callbacks land here, on the main thread, like every other call into the service. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Registered for the life of each recording: an input coming or going is re-checked. */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = syncInput()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = syncInput()
    }

    /**
     * Whether platform voice processing is applied. Unlike [gainDb] and [inputDeviceKey] this is
     * baked into `AudioRecord` at construction, so changing it only affects the *next* recording.
     */
    var captureSource: CaptureSource = CaptureSource.PROCESSED

    /** Label of the device capture actually landed on, or null when nothing is being captured. */
    fun routedInputLabel(): String? =
        if (isRecording && !isPaused) recorder?.routedInputLabel else null

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

    /**
     * Called on the main thread when a recording stops, for any reason: with the file (null if there
     * is none) and an error message (null on a clean stop). Both can be set together, because after
     * a capture error the part recorded before it is kept.
     */
    var onStopped: ((file: File?, error: String?) -> Unit)? = null

    /** How a recording ended in an error, for a screen that wasn't there to be told. */
    class StopReport(val file: File?, val error: String)

    /**
     * Set when a recording ends in an error while nothing is listening on [onStopped]. A capture
     * failure stops the recording by itself, possibly with the app in the background, and this is
     * how the screen finds out once the user comes back.
     */
    private var unreportedStop: StopReport? = null

    /** The error stop no screen has shown yet, if any. Taking it clears it. */
    fun takeUnreportedStop(): StopReport? = unreportedStop.also { unreportedStop = null }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started so it can outlive UI unbinding; stopped explicitly in stopRecording().
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        audioManager().unregisterAudioDeviceCallback(deviceCallback)
        super.onDestroy()
    }

    /** Milliseconds of actual recording (excluding paused spans); 0 when idle. */
    fun elapsedMillis(): Long = when {
        !isRecording -> 0L
        isPaused -> accumulatedMillis
        else -> accumulatedMillis + (SystemClock.elapsedRealtime() - segmentStartElapsed)
    }

    /**
     * Starts a recording from the chosen input. Throws [InputUnavailableException] when that input
     * isn't attached. With no preference to set, Android would record from the phone's mic
     * without anyone choosing it.
     */
    fun startRecording(spec: AudioFormatSpec, outputFile: File) {
        check(!isRecording) { "Already recording" }
        unreportedStop = null

        val input = when (val resolved = InputDevices.resolve(this, inputDeviceKey)) {
            InputDevices.Resolved.Unavailable -> throw InputUnavailableException(inputDeviceKey)
            InputDevices.Resolved.Automatic -> null
            is InputDevices.Resolved.Device -> resolved.info
        }

        outputFile.parentFile?.mkdirs()
        clippedSamples = 0L
        val r = RecorderFactory.create(
            spec, outputFile, analyzer, gainDb, input,
            CaptureSource.androidSource(captureSource, CaptureSource.unprocessedSupported(this))
        )
        // Capture that fails partway stops the recording here and now, keeping what it got. The
        // check skips a failure that a stop already in progress has dealt with.
        r.onCaptureFailed = { mainHandler.post { if (recorder === r) stopRecording() } }

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
        pausedForInput = false
        audioManager().registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    /** Pauses capture without finalising. No-op if not recording or already paused. */
    fun pauseRecording() {
        if (!isRecording || isPaused) return
        pause(forInput = false)
    }

    /**
     * Resumes capture after a pause. Returns false, and stays paused, when the chosen input isn't
     * attached, since resuming would record from the phone's mic instead. The pause then becomes
     * one that waits for the input ([pausedForInput]): the UI asks for another input, and plugging
     * the missing one back in resumes by itself.
     */
    fun resumeRecording(): Boolean {
        if (!isRecording || !isPaused) return true
        if (!routeToInput()) {
            if (!pausedForInput) {
                pausedForInput = true
                updateNotification()
            }
            return false
        }
        resume()
        return true
    }

    /**
     * Points the recorder at the chosen input as it is attached right now, looked up afresh
     * because ids change on reconnect. Returns false, leaving the recorder alone, when that input
     * isn't attached.
     */
    private fun routeToInput(): Boolean {
        val input = when (val resolved = InputDevices.resolve(this, inputDeviceKey)) {
            InputDevices.Resolved.Unavailable -> return false
            InputDevices.Resolved.Automatic -> null
            is InputDevices.Resolved.Device -> resolved.info
        }
        recorder?.preferredInput = input
        return true
    }

    /**
     * Brings a running recording into line with the chosen input after the selection or the
     * attached devices change: re-route to it, pause when it has gone, and resume a pause that was
     * waiting on it. The rule itself is [InputDevices.stepFor].
     */
    private fun syncInput() {
        if (!isRecording) return
        val available = routeToInput()
        when (InputDevices.stepFor(available, isPaused, pausedForInput)) {
            InputDevices.Step.PAUSE -> {
                pause(forInput = true)
                onInputPauseChanged?.invoke(true)
            }
            InputDevices.Step.RESUME -> {
                resume()
                onInputPauseChanged?.invoke(false)
            }
            InputDevices.Step.KEEP -> Unit
        }
    }

    private fun pause(forInput: Boolean) {
        recorder?.pause()
        accumulatedMillis += SystemClock.elapsedRealtime() - segmentStartElapsed
        isPaused = true
        pausedForInput = forInput
        updateNotification()
    }

    private fun resume() {
        recorder?.resume()
        segmentStartElapsed = SystemClock.elapsedRealtime()
        isPaused = false
        pausedForInput = false
        updateNotification()
    }

    /**
     * Stops and finalises the file. Returns the file left on disk, or null if there is none. After
     * a capture error, that is the part recorded before it.
     */
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
            endRecording()
        }
        // After an error the recorder keeps what it captured. The file is only gone when there
        // was nothing to keep.
        val saved = file?.takeIf { error == null || it.exists() }
        val listener = onStopped
        if (listener != null) {
            listener(saved, error)
        } else if (error != null) {
            unreportedStop = StopReport(saved, error)
        }
        return saved
    }

    /** Aborts recording and deletes the partial file. */
    fun cancelRecording() {
        if (!isRecording) return
        try {
            recorder?.cancel()
        } catch (_: Throwable) {
        } finally {
            endRecording()
        }
    }

    /** What [stopRecording] and [cancelRecording] both do once the recorder is finished with. */
    private fun endRecording() {
        audioManager().unregisterAudioDeviceCallback(deviceCallback)
        recorder = null
        isRecording = false
        isPaused = false
        pausedForInput = false
        currentFile = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun audioManager(): AudioManager =
        getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The ongoing notification for the current state: recording, paused, or waiting on an input. */
    private fun buildNotification(fileName: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // While waiting on a lost input, what to do about it matters more than the file name.
        val (title, text) = when {
            pausedForInput -> getString(R.string.notification_input_lost_title) to getString(
                R.string.notification_input_lost_text, InputDevices.labelFromKey(inputDeviceKey)
            )
            isPaused -> getString(R.string.notification_paused_title) to fileName
            else -> getString(R.string.notification_recording_title) to fileName
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun startForegroundWithNotification(fileName: String) {
        createChannel()
        val notification = buildNotification(fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Re-posts the ongoing notification to reflect the current paused / waiting state. */
    private fun updateNotification() {
        val name = currentFile?.name ?: return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(name))
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

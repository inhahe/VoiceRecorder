package com.lithic.voicerecorder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.lithic.voicerecorder.databinding.ActivityMainBinding
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings

    private var service: RecordingService? = null
    private var bound = false

    private val handler = Handler(Looper.getMainLooper())
    private var tickCount = 0
    private val timerTick = object : Runnable {
        override fun run() {
            updateTimer()
            updateSpectrum()
            updateClipIndicator()
            // Routing rarely changes and each read is a native call, so poll it once a second
            // rather than on every 50 ms frame.
            if (tickCount++ % 20 == 0) updateRoutedInput()
            handler.postDelayed(this, 50)
        }
    }

    /** Redraws the input row when a mic is plugged in or pulled out while the app is on screen. */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refreshInputRow()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refreshInputRow()
    }

    // --- permission launchers -------------------------------------------------

    private val requestRuntimePerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { afterRuntimePermissionResult() }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshUi() }

    // --- service binding ------------------------------------------------------

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as RecordingService.LocalBinder
            service = local.service
            bound = true
            service?.onStopped = { file, error -> runOnUiThread { onRecordingStopped(file, error) } }
            // The service may have been recording since before this Activity existed.
            service?.gainDb = settings.gainDb
            service?.inputDeviceKey = settings.inputDeviceKey
            service?.captureSource = settings.captureSource
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padBelowActionBar()
        settings = Settings(this)

        binding.recordButton.setOnClickListener { onRecordPressed() }
        binding.pauseButton.setOnClickListener { onPausePressed() }
        binding.stopButton.setOnClickListener { onStopPressed() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.changeFolderButton.setOnClickListener { openDirectoryPicker() }
        binding.openAppInfoButton.setOnClickListener {
            startActivity(PermissionUtil.appDetailsIntent(this))
        }
        binding.openStorageAccessButton.setOnClickListener {
            allFilesAccessLauncher.launch(PermissionUtil.allFilesAccessIntent(this))
        }
        binding.inputRow.setOnClickListener { showInputPicker() }
        setupGainControl()
    }

    // --- input device ---------------------------------------------------------

    /**
     * Single-choice picker over the currently attached capture devices, plus "Automatic". Like the
     * gain slider this works during recording too: the service re-routes the live stream.
     */
    private fun showInputPicker() {
        val options = InputDevices.options(this)
        val labels = (listOf(getString(R.string.input_automatic)) + options.map { it.label })
        val keys = listOf(InputDevices.AUTOMATIC) + options.map { it.key }
        val current = settings.inputDeviceKey
        // A remembered-but-absent device has no row of its own; fall back to highlighting nothing.
        val checked = keys.indexOfFirst { it == current }
            .let { if (it >= 0) it else InputDevices.match(options, current)?.let { m -> keys.indexOf(m.key) } ?: -1 }

        AlertDialog.Builder(this)
            .setTitle(R.string.input_picker_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                applyInput(keys[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyInput(key: String) {
        settings.inputDeviceKey = key
        service?.inputDeviceKey = key
        refreshInputRow()
    }

    private fun refreshInputRow() {
        binding.inputValue.text = InputDevices.summary(InputDevices.options(this), settings.inputDeviceKey)
        updateRoutedInput()
    }

    /**
     * `setPreferredDevice` is a hint, not a guarantee, so while recording we show what capture is
     * actually routed to — and say so plainly when it isn't what was asked for.
     */
    private fun updateRoutedInput() {
        val routed = service?.routedInputLabel()
        if (routed == null) {
            binding.inputRouted.visibility = android.view.View.GONE
            return
        }
        val wanted = settings.inputDeviceKey
        val matched = wanted.isEmpty() ||
            InputDevices.match(InputDevices.options(this), wanted)?.label == routed
        binding.inputRouted.text = getString(
            if (matched) R.string.input_routed else R.string.input_routed_mismatch, routed
        )
        binding.inputRouted.visibility = android.view.View.VISIBLE
    }

    // --- mic gain -------------------------------------------------------------

    /**
     * The gain slider works before *and* during recording: the service pushes changes straight into
     * the running capture loop. Tapping the dB readout snaps back to 0 dB.
     */
    private fun setupGainControl() {
        binding.gainSlider.max = Gain.MAX_DB - Gain.MIN_DB
        binding.gainSlider.progress = settings.gainDb - Gain.MIN_DB
        binding.gainValue.text = Gain.label(settings.gainDb)

        binding.gainSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                applyGain(progress + Gain.MIN_DB)
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
        })

        binding.gainValue.setOnClickListener {
            if (settings.gainDb == 0) return@setOnClickListener
            binding.gainSlider.progress = -Gain.MIN_DB // fires onProgressChanged -> applyGain(0)
            Toast.makeText(this, R.string.gain_reset, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyGain(db: Int) {
        settings.gainDb = db
        service?.gainDb = db
        binding.gainValue.text = Gain.label(db)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
        audioManager().registerAudioDeviceCallback(deviceCallback, handler)
        handler.post(timerTick)
    }

    override fun onResume() {
        super.onResume()
        if (!settings.firstRunComplete) {
            showWelcome()
        }
        refreshUi()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(timerTick)
        audioManager().unregisterAudioDeviceCallback(deviceCallback)
        if (bound) {
            service?.onStopped = null
            unbindService(connection)
            bound = false
        }
    }

    // --- first-run / permission flow -----------------------------------------

    private fun showWelcome() {
        AlertDialog.Builder(this)
            .setTitle(R.string.welcome_title)
            .setMessage(R.string.welcome_message)
            .setCancelable(false)
            .setPositiveButton(R.string.grant_permissions) { _, _ -> requestNeededPermissions() }
            .show()
    }

    private fun requestNeededPermissions() {
        val runtime = PermissionUtil.runtimePermissionsToRequest(this)
        if (runtime.isNotEmpty()) {
            requestRuntimePerms.launch(runtime)
        } else {
            afterRuntimePermissionResult()
        }
    }

    private fun afterRuntimePermissionResult() {
        // On API 30+, storage is "All files access", granted via a Settings screen.
        if (PermissionUtil.needsAllFilesAccessScreen()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.all_files_title)
                .setMessage(R.string.all_files_message)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    allFilesAccessLauncher.launch(PermissionUtil.allFilesAccessIntent(this))
                }
                .setNegativeButton(R.string.later, null)
                .show()
            return
        }
        maybeAskForDirectory()
    }

    private fun maybeAskForDirectory() {
        if (settings.saveDirectory == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_save_folder_title)
                .setMessage(R.string.choose_save_folder_message)
                .setPositiveButton(R.string.choose_folder) { _, _ -> openDirectoryPicker() }
                .setNegativeButton(R.string.use_default) { _, _ ->
                    val dir = Settings.defaultSaveDirectory()
                    dir.mkdirs()
                    settings.saveDirectory = dir.absolutePath
                    settings.firstRunComplete = true
                    refreshUi()
                }
                .setCancelable(false)
                .show()
        } else {
            settings.firstRunComplete = true
            refreshUi()
        }
    }

    // --- directory picker -----------------------------------------------------

    private fun openDirectoryPicker() {
        val dialog = DirectoryPickerDialog.new(
            settings.saveDirectory ?: Settings.defaultSaveDirectory().absolutePath
        )
        dialog.onDirectoryChosen = { path ->
            settings.saveDirectory = path
            settings.firstRunComplete = true
            refreshUi()
        }
        dialog.show(supportFragmentManager, "dir_picker")
    }

    // --- recording ------------------------------------------------------------

    private fun onRecordPressed() {
        if (!PermissionUtil.hasMicPermission(this)) {
            requestNeededPermissions()
            return
        }
        if (!PermissionUtil.hasStoragePermission(this)) {
            afterRuntimePermissionResult()
            return
        }
        if (settings.saveDirectory == null) {
            maybeAskForDirectory()
            return
        }
        if (service?.isRecording == true) return

        val dialog = PreRecordDialog.new(settings.startingBaseName())
        dialog.onConfirmed = { baseName, spec -> beginRecording(baseName, spec) }
        dialog.show(supportFragmentManager, "pre_record")
    }

    private fun beginRecording(baseName: String, spec: AudioFormatSpec) {
        val dir = settings.saveDirectoryFile()
        if (!dir.exists()) dir.mkdirs()
        if (!dir.canWrite()) {
            Toast.makeText(this, R.string.folder_not_writable, Toast.LENGTH_LONG).show()
            return
        }

        val outFile = StorageUtil.uniqueFile(dir, baseName, spec.format.extension)

        // Ensure the service is started (so it survives unbinding) before issuing the command.
        startService(Intent(this, RecordingService::class.java))
        val svc = service
        if (svc == null) {
            Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            svc.startRecording(spec, outFile)
            // Remember what the user typed (not the `_2`-suffixed result) so the next take of the
            // same thing starts from the same name.
            settings.rememberBaseName(baseName)
        } catch (t: Throwable) {
            AlertDialog.Builder(this)
                .setTitle(R.string.recording_failed)
                .setMessage(t.message ?: t.javaClass.simpleName)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        refreshUi()
    }

    private fun onPausePressed() {
        val svc = service ?: return
        if (!svc.isRecording) return
        if (svc.isPaused) svc.resumeRecording() else svc.pauseRecording()
        refreshUi()
    }

    private fun onStopPressed() {
        service?.stopRecording()
        // onRecordingStopped() will fire via the callback.
        refreshUi()
    }

    private fun onRecordingStopped(file: File?, error: String?) {
        refreshUi()
        if (error != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.recording_failed)
                .setMessage(error)
                .setPositiveButton(R.string.ok, null)
                .show()
        } else if (file != null) {
            val clipped = (service?.clippedSamples ?: 0L) > 0L
            val message = if (clipped) {
                getString(R.string.saved_as_clipped, file.name)
            } else {
                getString(R.string.saved_as, file.name)
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    // --- UI state -------------------------------------------------------------

    private fun refreshUi() {
        val recording = service?.isRecording == true
        val paused = service?.isPaused == true
        val visible = android.view.View.VISIBLE
        val gone = android.view.View.GONE

        binding.recordButton.isEnabled = !recording
        binding.recordButton.visibility = if (recording) gone else visible
        binding.pauseButton.visibility = if (recording) visible else gone
        binding.pauseButton.setText(if (paused) R.string.resume else R.string.pause)
        binding.stopButton.isEnabled = recording
        binding.stopButton.visibility = if (recording) visible else gone
        // Keep the meter on screen while recording; it flattens on its own while paused.
        binding.spectrumView.setActive(recording)

        // Show the sideload "All files access" how-to only while that permission is still missing.
        binding.permissionNote.visibility =
            if (PermissionUtil.needsAllFilesAccessScreen()) android.view.View.VISIBLE
            else android.view.View.GONE

        binding.folderPath.text = settings.saveDirectory ?: getString(R.string.no_folder_chosen)

        // Picked up here (not just on bind) because Settings can change it while we're stopped.
        service?.captureSource = settings.captureSource
        refreshInputRow()

        val spec = settings.defaultSpec()
        binding.defaultFormatText.text = getString(
            R.string.default_summary,
            spec.format.displayName,
            AudioFormatSpec.hzLabel(spec.sampleRate),
            if (spec.format.compressed) "${spec.bitrateKbps} kbps" else getString(R.string.lossless)
        )

        val dir = settings.saveDirectoryFile()
        binding.storageText.text = getString(
            R.string.storage_estimate,
            StorageUtil.availableTimeText(dir, spec),
            StorageUtil.formatBytes(StorageUtil.freeBytes(dir))
        )

        binding.statusText.text = when {
            recording && paused -> getString(R.string.status_paused)
            recording -> getString(R.string.status_recording)
            else -> getString(R.string.status_idle)
        }
        updateTimer()
    }

    private fun updateClipIndicator() {
        binding.clipText.visibility =
            if (service?.isClipping() == true) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun audioManager(): AudioManager =
        getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun updateSpectrum() {
        if (service?.isRecording == true) {
            binding.spectrumView.submitBands(service?.spectrum() ?: FloatArray(0))
        }
    }

    private fun updateTimer() {
        val ms = service?.elapsedMillis() ?: 0L
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        binding.timerText.text = if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

}

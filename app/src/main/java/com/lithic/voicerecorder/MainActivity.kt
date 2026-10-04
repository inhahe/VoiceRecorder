package com.lithic.voicerecorder

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.widget.TextView
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

    /** The input row's normal text colours, restored once a missing input is back. */
    private lateinit var inputValueColors: ColorStateList

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

    /**
     * Redraws the input row, and the input prompt if it's up, when a mic is plugged in or pulled
     * out while the app is on screen.
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = onDevicesChanged()
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
            service?.onInputPauseChanged = { paused -> runOnUiThread { onInputPauseChanged(paused) } }
            // The service may have been recording since before this Activity existed.
            service?.gainDb = settings.gainDb
            service?.inputDeviceKey = settings.inputDeviceKey
            service?.captureSource = settings.captureSource
            // Also raises the input prompt if the input went while the app was in the background.
            refreshUi()
            // A recording that stopped on an error while we were away still gets reported.
            service?.takeUnreportedStop()?.let { onRecordingStopped(it.file, it.error) }
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
        inputValueColors = binding.inputValue.textColors

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

        // The dialogs report back as fragment results, registered here on every creation, so a
        // dialog Android restores after recreating this Activity still reaches it. That happens
        // after the process is killed while the phone sleeps. A callback set on the old instance
        // would be lost, and "Start recording" would close the dialog and record nothing.
        supportFragmentManager.setFragmentResultListener(PreRecordDialog.REQUEST_KEY, this) { _, result ->
            beginRecording(PreRecordDialog.baseNameOf(result), PreRecordDialog.specOf(result))
        }
        supportFragmentManager.setFragmentResultListener(DirectoryPickerDialog.REQUEST_KEY, this) { _, result ->
            onFolderChosen(DirectoryPickerDialog.pathOf(result))
        }
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
        val options = InputDevices.options(this)
        val key = settings.inputDeviceKey
        binding.inputValue.text = InputDevices.summary(options, key)
        // Red while the chosen input is unplugged: Record will ask for another one rather than
        // use the phone's mic.
        if (InputDevices.isUnavailable(options, key)) {
            binding.inputValue.setTextColor(getColor(R.color.warn_red))
        } else {
            binding.inputValue.setTextColor(inputValueColors)
        }
        updateRoutedInput()
    }

    private fun onDevicesChanged() {
        refreshInputRow()
        syncInputPrompt()
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

    // --- chosen input not connected -------------------------------------------

    /**
     * Why the "input not connected" prompt is up. In both cases it lists what's attached now (plus
     * Automatic) and nothing records until an input is chosen. It never falls back to the phone's
     * mic by itself.
     *
     * - [BEFORE_RECORDING]: Record was pressed, or the pre-record dialog confirmed, while the
     *   chosen input is unplugged. Choosing an input carries on to the next step. So does plugging
     *   the missing one back in while the prompt is up. Cancel drops it.
     * - [DURING_RECORDING]: mirrors [RecordingService.pausedForInput]. The input went
     *   mid-recording and the service paused. Choosing an input resumes, because the service
     *   resumes by itself once the selection is one it can record from, and so does plugging the
     *   input back in. "Stay paused" leaves it paused.
     */
    private enum class InputPrompt { BEFORE_RECORDING, DURING_RECORDING }

    private var inputPrompt: AlertDialog? = null
    private var inputPromptReason: InputPrompt? = null

    /** The keys behind the rows on show, so the prompt is rebuilt only when they change. */
    private var inputPromptKeys: List<String> = emptyList()

    /** What [InputPrompt.BEFORE_RECORDING] carries on with once there's an input. */
    private var inputPromptThen: (() -> Unit)? = null

    /** The user waved the mid-recording prompt away; don't force it back up for the same loss. */
    private var inputPromptDeclined = false

    private fun inputMissing(): Boolean =
        InputDevices.isUnavailable(InputDevices.options(this), settings.inputDeviceKey)

    /** The chosen input is unplugged, so ask for one before recording. [then] runs once there is one. */
    private fun requireInput(then: () -> Unit) {
        closeInputPrompt()
        inputPromptReason = InputPrompt.BEFORE_RECORDING
        inputPromptThen = then
        syncInputPrompt()
    }

    /**
     * Keeps the prompt in step with the service and the attached devices:
     *
     * - raises the mid-recording prompt when the service has paused for a lost input. That
     *   includes a loss that happened while the app was in the background, since this runs on
     *   every rebind, the first moment the user can be asked;
     * - rebuilds the list as devices come and go;
     * - takes the prompt down once it no longer applies.
     */
    private fun syncInputPrompt() {
        val svc = service
        val waiting = svc?.pausedForInput == true
        if (!waiting) inputPromptDeclined = false
        when (inputPromptReason) {
            null -> if (waiting && !inputPromptDeclined) {
                inputPromptReason = InputPrompt.DURING_RECORDING
                showInputPrompt()
            }
            // Once the input is back the service resumes by itself (or already has), and reports
            // it through onInputPauseChanged.
            InputPrompt.DURING_RECORDING ->
                if (waiting && inputMissing()) showInputPrompt() else closeInputPrompt()
            InputPrompt.BEFORE_RECORDING -> when {
                svc?.isRecording == true -> closeInputPrompt()
                inputMissing() -> showInputPrompt()
                else -> {
                    // The missing input was plugged back in: carry on as if it had been chosen.
                    // Not while the Activity is being torn down, since the next step may show a
                    // dialog fragment.
                    val then = inputPromptThen
                    closeInputPrompt()
                    if (!supportFragmentManager.isStateSaved) then?.invoke()
                }
            }
        }
    }

    /**
     * Shows the prompt for [inputPromptReason], or does nothing if it's already up with the
     * current list. When the list has changed it is rebuilt rather than patched, so it keeps the
     * stock AlertDialog list styling.
     */
    // A custom dialog title has no parent to inflate against: AlertController adds it to the
    // dialog's top panel with its own layout params.
    @SuppressLint("InflateParams")
    private fun showInputPrompt() {
        val reason = inputPromptReason ?: return
        if (isFinishing || isDestroyed) return
        val options = InputDevices.options(this)
        val keys = listOf(InputDevices.AUTOMATIC) + options.map { it.key }
        if (inputPrompt?.isShowing == true && keys == inputPromptKeys) return
        val labels = listOf(getString(R.string.input_automatic)) + options.map { it.label }
        val before = reason == InputPrompt.BEFORE_RECORDING

        // Detach the old dialog first, so its dismiss listener doesn't take the rebuild for a "no".
        inputPrompt?.let { old ->
            inputPrompt = null
            old.dismiss()
        }
        inputPromptKeys = keys

        val builder = AlertDialog.Builder(this)
        val header = LayoutInflater.from(builder.context)
            .inflate(R.layout.dialog_input_prompt_title, null)
        header.findViewById<TextView>(R.id.input_prompt_title).setText(
            if (before) R.string.input_missing_title else R.string.input_lost_title
        )
        header.findViewById<TextView>(R.id.input_prompt_message).text = getString(
            if (before) R.string.input_missing_message else R.string.input_lost_message,
            InputDevices.labelFromKey(settings.inputDeviceKey)
        )
        val dialog = builder
            .setCustomTitle(header)
            .setSingleChoiceItems(labels.toTypedArray(), -1) { _, which ->
                onInputPromptChoice(keys[which])
            }
            .setNegativeButton(if (before) R.string.cancel else R.string.stay_paused, null)
            .create()
        dialog.setOnDismissListener {
            if (inputPrompt !== dialog) return@setOnDismissListener // replaced, or closed by us
            // The user said no: Cancel / Stay paused, back, or a tap outside.
            if (inputPromptReason == InputPrompt.DURING_RECORDING) inputPromptDeclined = true
            inputPrompt = null
            inputPromptReason = null
            inputPromptThen = null
            inputPromptKeys = emptyList()
        }
        inputPrompt = dialog
        dialog.show()
    }

    private fun onInputPromptChoice(key: String) {
        val then = inputPromptThen
        closeInputPrompt()
        // Mid-recording this alone resumes: the service carries on by itself once the selection
        // is one it can record from.
        applyInput(key)
        then?.invoke()
    }

    /** Takes the prompt down without counting it as the user declining. */
    private fun closeInputPrompt() {
        val dialog = inputPrompt
        inputPrompt = null
        inputPromptReason = null
        inputPromptThen = null
        inputPromptKeys = emptyList()
        dialog?.dismiss()
    }

    /** The service paused for a lost input (the prompt goes up), or resumed once it had one again. */
    private fun onInputPauseChanged(paused: Boolean) {
        refreshUi()
        if (!paused) {
            val input = InputDevices.summary(InputDevices.options(this), settings.inputDeviceKey)
            Snackbar.make(binding.root, getString(R.string.input_resumed, input), Snackbar.LENGTH_SHORT)
                .show()
        }
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
            service?.onInputPauseChanged = null
            unbindService(connection)
            bound = false
        }
    }

    override fun onDestroy() {
        // Dismissed here so it isn't leaked across a rotation. If a recording is still waiting
        // on its input, the new instance raises the prompt again once it binds.
        closeInputPrompt()
        super.onDestroy()
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

    /** The choice comes back through [onFolderChosen]; see the result listeners in onCreate. */
    private fun openDirectoryPicker() {
        DirectoryPickerDialog.new(settings.saveDirectory ?: Settings.defaultSaveDirectory().absolutePath)
            .show(supportFragmentManager, "dir_picker")
    }

    private fun onFolderChosen(path: String) {
        settings.saveDirectory = path
        settings.firstRunComplete = true
        refreshUi()
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
        // The chosen input is unplugged: ask for one now rather than record from the phone's mic.
        if (inputMissing()) {
            requireInput { onRecordPressed() }
            return
        }

        // Confirming it calls beginRecording(), through the result listener in onCreate.
        PreRecordDialog.new(settings.startingBaseName()).show(supportFragmentManager, "pre_record")
    }

    private fun beginRecording(baseName: String, spec: AudioFormatSpec) {
        // Checked again here as well as on Record, because the input can be unplugged while the
        // pre-record dialog is up.
        if (inputMissing()) {
            requireInput { beginRecording(baseName, spec) }
            return
        }
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
        } catch (_: InputUnavailableException) {
            // Unplugged in the moment between the check above and the service's own.
            requireInput { beginRecording(baseName, spec) }
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
        if (svc.isPaused) {
            // Refused while the chosen input is missing: the service waits for it instead, and
            // refreshUi() raises the prompt. Pressing Resume is a request, so the prompt shows
            // even if it was waved away earlier.
            if (!svc.resumeRecording()) inputPromptDeclined = false
        } else {
            svc.pauseRecording()
        }
        refreshUi()
    }

    private fun onStopPressed() {
        service?.stopRecording()
        // onRecordingStopped() will fire via the callback.
        refreshUi()
    }

    /**
     * [file] is what's on disk, [error] why it ended early, if it did. Both are set when capture
     * failed partway: the part before the failure was kept, and the user needs to hear both halves.
     */
    private fun onRecordingStopped(file: File?, error: String?) {
        refreshUi()
        if (error != null && file != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.recording_ended_with_error)
                .setMessage(getString(R.string.recording_ended_with_error_message, error, file.name))
                .setPositiveButton(R.string.ok, null)
                .show()
        } else if (error != null) {
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
            recording && service?.pausedForInput == true -> getString(R.string.status_paused_input)
            recording && paused -> getString(R.string.status_paused)
            recording -> getString(R.string.status_recording)
            else -> getString(R.string.status_idle)
        }
        updateTimer()
        syncInputPrompt()
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

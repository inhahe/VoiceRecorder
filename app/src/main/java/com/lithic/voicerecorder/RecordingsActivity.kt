package com.lithic.voicerecorder

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lithic.voicerecorder.databinding.ActivityRecordingsBinding
import com.lithic.voicerecorder.databinding.ItemRecordingBinding
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The recordings in the save folder, newest first, with a player under them. Tap a recording to
 * play it (tap it again to pause), and use its ⋮ menu to share, rename or delete it. The list
 * follows the folder while the screen is open. Playing pauses when the screen goes, when another
 * app takes the sound (a call), and when headphones are unplugged.
 */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var settings: Settings
    private val model: RecordingsModel by viewModels()
    private val player get() = model.player
    private val adapter = RecordingsAdapter()
    private val main = Handler(Looper.getMainLooper())

    /** Lists the folder and reads lengths, one job at a time: a slow card mustn't hold up the screen. */
    private val background: ExecutorService = Executors.newSingleThreadExecutor()

    /** A recording as listed. [lengthMs] is null while unknown, and stays null for one that can't be read. */
    private data class Row(val file: File, val size: Long, val modified: Long, val lengthMs: Long?, val measured: Boolean)

    private var folder: File? = null
    private var observer: FileObserver? = null

    /** Bumped per listing, so an older one finishing late is dropped, and its measuring skipped. Read on the background thread. */
    @Volatile
    private var listing = 0

    private var scrubbing = false

    /** Asked for by the intent ("Play" on "Saved"); played once listed with the screen started. Kept through recreation. */
    private var toPlay: String? = null

    /** Headphones unplugged (or a Bluetooth headset gone): stop before the speaker takes over. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            showPosition()
            if (player.isPlaying) main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padBelowActionBar()
        colourStatusBar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.recordings)
        settings = Settings(this)
        model.onChange = ::onPlayerChanged
        binding.playerSpectrum.source = { out -> player.takeSpectrum(out) }

        binding.recordingsList.layoutManager = LinearLayoutManager(this)
        binding.recordingsList.adapter = adapter
        binding.playButton.setOnClickListener {
            if (player.isPlayingOrStarting) player.pause() else checkingRecording(player.file) { player.resume() }
        }
        binding.rewindButton.setOnClickListener { player.stop() }
        binding.positionBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                binding.positionText.setTextIfChanged(positionText(progress.toLong()))
                // Keys and TalkBack's adjust actions move the bar without a drag: they seek at once.
                if (!scrubbing) player.seekTo(progress.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                scrubbing = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                scrubbing = false
                player.seekTo(binding.positionBar.progress.toLong())
                showPosition()
            }
        })
        // Recreated (turned, say): asked for and not yet played, it is still to play.
        toPlay = if (savedInstanceState == null) intent.getStringExtra(EXTRA_PLAY) else savedInstanceState.getString(STATE_TO_PLAY)
        onPlayerChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TO_PLAY, toPlay)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        watch(settings.saveDirectoryFile())
        refresh()
    }

    override fun onStop() {
        super.onStop()
        // Playing on with the screen gone would leave no way to stop it but coming back. Turning
        // the phone only recreates the screen, and the player, kept by the model, plays on.
        if (!isChangingConfigurations) player.pause()
        unregisterReceiver(noisy)
        observer?.stopWatching()
        observer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        model.onChange = null
        background.shutdownNow()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // --- the list -------------------------------------------------------------

    /** Follows [dir] while the screen is open: a file added, removed or renamed lists it again. */
    private fun watch(dir: File) {
        folder = dir
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
        val onEvent: () -> Unit = {
            main.removeCallbacks(refreshSoon)
            main.postDelayed(refreshSoon, REFRESH_DEBOUNCE_MS)
        }
        observer = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent()
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent()
            }
        }).also { it.startWatching() }
    }

    private val refreshSoon = Runnable { refresh() }

    /**
     * Runs [job] on the background thread, unless the screen is gone: a folder change already on
     * its way from the observer's thread can still post a refresh after [onDestroy], and the
     * executor is shut down by then.
     */
    private fun inBackground(job: () -> Unit) {
        if (isDestroyed || background.isShutdown) return
        try {
            background.execute(job)
        } catch (e: RejectedExecutionException) {
            // shut down between the check and the call: the screen is going anyway
        }
    }

    /** Lists the folder off the main thread, then reads the lengths not yet known, one by one. */
    private fun refresh() {
        val dir = folder ?: settings.saveDirectoryFile()
        val id = ++listing
        inBackground {
            RecordingFiles.finishRenames(dir)
            val files = RecordingFiles.list(dir)
            main.post {
                if (id != listing || isDestroyed) return@post
                val rows = files.map { (file, size, modified) ->
                    val known = RecordingLengths.get(file, size, modified)
                    Row(file, size, modified, known?.ms, known != null)
                }
                show(dir, rows)
                for (row in rows) if (!row.measured && row.file != RecordingService.activeFile) measure(row, id)
                // Only with the screen started: from the background it would play with nothing
                // on screen to stop it. The next listing, at onStart, plays it then.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    toPlay?.let { path ->
                        toPlay = null
                        rows.firstOrNull { it.file.path == path }?.let { play(it.file) }
                    }
                }
            }
        }
    }

    private fun measure(row: Row, id: Int) {
        inBackground {
            // A newer listing is waiting behind this: it measures what is still there itself.
            if (id != listing) return@inBackground
            val ms = lengthOf(row.file)
            main.post {
                RecordingLengths.put(row.file, row.size, row.modified, ms)
                if (id == listing) adapter.update(row.file) { it.copy(lengthMs = ms, measured = true) }
            }
        }
    }

    /** How long [file] plays, in ms; null when it can't be read. A WAV is read from its header, which a cut-off one still has. */
    private fun lengthOf(file: File): Long? {
        if (file.extension.equals("wav", ignoreCase = true)) RecordingFiles.wavDurationMs(file)?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: RuntimeException) {
            null // any file can sit in the folder, damaged or not
        } finally {
            retriever.release()
        }
    }

    private fun show(dir: File, rows: List<Row>) {
        adapter.submit(rows)
        val bytes = rows.sumOf { it.size }
        binding.summaryText.text = when {
            rows.isEmpty() -> dir.absolutePath
            else -> resources.getQuantityString(R.plurals.recordings_summary, rows.size, rows.size, StorageUtil.formatBytes(bytes)) +
                " · " + dir.absolutePath
        }
        binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.setText(if (dir.isDirectory) R.string.recordings_empty else R.string.recordings_no_folder)
        // The recording in the player may have been deleted or renamed by another app.
        player.file?.let { loaded -> if (!loaded.exists()) player.release(loaded) }
    }

    // --- actions --------------------------------------------------------------

    private fun onRowClicked(row: Row) {
        when {
            row.file == RecordingService.activeFile -> Toast.makeText(this, R.string.recording_in_progress, Toast.LENGTH_SHORT).show()
            row.file == player.file && player.isPlayingOrStarting -> player.pause()
            row.file == player.file -> checkingRecording(row.file) { player.resume() }
            else -> play(row.file)
        }
    }

    private fun play(file: File) {
        if (file == RecordingService.activeFile) return
        checkingRecording(file) { player.play(file) }
    }

    /**
     * Runs [start], which makes [file] play aloud, after asking first if a take is being recorded
     * and nothing but the speaker would play it: the microphone would record it into the take.
     */
    private fun checkingRecording(file: File?, start: () -> Unit) {
        if (RecordingService.activeFile == null || file == null || headphonesAttached()) {
            start()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.recording_while_recording_title)
            .setMessage(getString(R.string.recording_while_recording_message, file.name))
            .setPositiveButton(R.string.recording_play_anyway) { _, _ -> start() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Whether the player's sound would go somewhere only the listener hears: where Android routes it, where it can say. */
    private fun headphonesAttached(): Boolean {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audio.getAudioDevicesForAttributes(DecodingOutput.ATTRIBUTES).map { it.type }
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }
        return types.any { it in PRIVATE_OUTPUTS }
    }

    private fun onRowMenu(row: Row, anchor: View) {
        val recording = row.file == RecordingService.activeFile
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_SHARE, 0, R.string.recording_share).isEnabled = !recording
            menu.add(0, MENU_RENAME, 1, R.string.recording_rename).isEnabled = !recording
            menu.add(0, MENU_DELETE, 2, R.string.recording_delete).isEnabled = !recording
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_SHARE -> share(row.file)
                    MENU_RENAME -> askRename(row.file)
                    MENU_DELETE -> askDelete(row.file)
                }
                true
            }
            show()
        }
    }

    private fun share(file: File) {
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        } catch (e: IllegalArgumentException) {
            // A folder the provider doesn't cover (see file_paths.xml).
            Toast.makeText(this, getString(R.string.recording_cant_share, e.message ?: ""), Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = RecordingFiles.mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri) // so the grant reaches the app chosen
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.recording_share_title, file.name)))
    }

    private fun askRename(file: File) {
        val field = EditText(this).apply {
            setText(file.nameWithoutExtension)
            setSingleLine()
            selectAll()
        }
        val frame = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.recording_rename_title, file.name))
            .setView(frame)
            .setPositiveButton(R.string.recording_rename, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Set here rather than through the builder, so a refused name keeps the dialog open.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (val check = RecordingFiles.renameTarget(file, field.text.toString())) {
                    is RecordingFiles.Rename.Refused -> field.error = getString(
                        when (check.why) {
                            RecordingFiles.Problem.EMPTY -> R.string.filename_required
                            RecordingFiles.Problem.ILLEGAL -> R.string.filename_illegal
                            RecordingFiles.Problem.HIDDEN -> R.string.recording_rename_hidden
                            RecordingFiles.Problem.EXISTS -> R.string.recording_rename_exists
                        }
                    )
                    RecordingFiles.Rename.Unchanged -> dialog.dismiss()
                    is RecordingFiles.Rename.To -> {
                        if (file == RecordingService.activeFile) {
                            dialog.dismiss() // the take started meanwhile
                        } else if (RecordingFiles.rename(file, check.target)) {
                            player.renamed(file, check.target)
                            RecordingLengths.renamed(file, check.target)
                            dialog.dismiss()
                            refresh()
                        } else {
                            field.error = getString(R.string.recording_rename_failed)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun askDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.recording_delete_title, file.name))
            .setMessage(R.string.recording_delete_message)
            .setPositiveButton(R.string.recording_delete) { _, _ ->
                if (file == RecordingService.activeFile) return@setPositiveButton
                player.release(file) // nothing plays on from a file that is gone
                if (!file.delete() && file.exists()) {
                    Toast.makeText(this, getString(R.string.recording_delete_failed, file.name), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- the player bar -------------------------------------------------------

    private fun onPlayerChanged() {
        val file = player.file
        binding.playerTitle.text = file?.name ?: getString(R.string.player_nothing_loaded)
        val problem = player.error ?: player.notice
        binding.playerError.text = problem
        binding.playerError.visibility = if (problem != null) View.VISIBLE else View.GONE
        val playing = player.isPlayingOrStarting
        binding.playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playButton.contentDescription = getString(if (playing) R.string.player_pause else R.string.player_play)
        binding.playButton.isEnabled = file != null
        binding.rewindButton.isEnabled = file != null && !player.isOpening
        val seekable = file != null && !player.isOpening && player.error == null
        // Disabled mid-drag, the bar never reports the drag's end: without this the position
        // would stay frozen for every recording played after.
        if (!seekable) scrubbing = false
        binding.positionBar.isEnabled = seekable
        binding.playerSpectrum.setActive(file != null && player.error == null)
        binding.positionBar.max = player.durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        showPosition()
        main.removeCallbacks(tick)
        if (player.isPlaying) main.postDelayed(tick, TICK_MS)
        // One that couldn't be opened or played stays named in the player, so Play tries again,
        // but isn't marked in the list: the bar says why instead.
        adapter.markPlayer(if (player.error == null) file else null, player.isPlayingOrStarting)
    }

    private fun showPosition() {
        if (scrubbing) return
        val at = player.positionMs
        binding.positionBar.progress = at.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        binding.positionText.setTextIfChanged(if (player.file == null) "" else positionText(at))
    }

    private fun positionText(at: Long): String {
        val length = player.durationMs
        return if (length > 0) "${RecordingFiles.clock(at)} / ${RecordingFiles.clock(length)}" else RecordingFiles.clock(at)
    }

    // --- adapter --------------------------------------------------------------

    private inner class RecordingsAdapter : RecyclerView.Adapter<RecordingsAdapter.Holder>() {
        private val rows = ArrayList<Row>()
        private var loaded: File? = null
        private var playing = false
        private val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

        inner class Holder(val item: ItemRecordingBinding) : RecyclerView.ViewHolder(item.root)

        fun submit(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            @Suppress("NotifyDataSetChanged") // a folder listing, a handful of rows: no diffing needed
            notifyDataSetChanged()
        }

        fun update(file: File, change: (Row) -> Row) {
            val i = rows.indexOfFirst { it.file == file }
            if (i < 0) return
            rows[i] = change(rows[i])
            notifyItemChanged(i)
        }

        fun markPlayer(file: File?, isPlaying: Boolean) {
            if (file == loaded && isPlaying == playing) return
            val before = loaded
            loaded = file
            playing = isPlaying
            rows.forEachIndexed { i, row -> if (row.file == before || row.file == file) notifyItemChanged(i) }
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val inPlayer = row.file == loaded
            with(holder.item) {
                recordingName.text = row.file.name
                recordingName.setTypeface(null, if (inPlayer) Typeface.BOLD else Typeface.NORMAL)
                val length = when {
                    row.file == RecordingService.activeFile -> getString(R.string.recording_in_progress_short)
                    !row.measured -> "…"
                    row.lengthMs == null -> "?"
                    else -> RecordingFiles.clock(row.lengthMs)
                }
                val date = dates.format(Date(row.modified))
                recordingDetails.text = getString(R.string.recording_details, length, StorageUtil.formatBytes(row.size), date)
                playerMark.visibility = if (inPlayer) View.VISIBLE else View.GONE
                playerMark.setImageResource(if (inPlayer && playing) R.drawable.ic_volume_up else R.drawable.ic_pause)
                // Said by TalkBack with the row, which the mark itself isn't.
                ViewCompat.setStateDescription(root, if (inPlayer) getString(if (playing) R.string.player_playing else R.string.player_paused) else null)
                root.setOnClickListener { onRowClicked(row) }
                recordingMenu.setOnClickListener { onRowMenu(row, it) }
            }
        }
    }

    companion object {
        /** Path of a recording to play once the list is shown: "Play" on the "Saved" message. */
        const val EXTRA_PLAY = "play"

        private const val STATE_TO_PLAY = "to_play"

        /** Outputs only the listener hears, so a take being recorded doesn't pick the sound up. */
        private val PRIVATE_OUTPUTS = setOf(
            // Not Bluetooth SCO: that is for calls, and media doesn't go to it.
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        private const val TICK_MS = 200L
        private const val REFRESH_DEBOUNCE_MS = 300L
        private const val MENU_SHARE = 1
        private const val MENU_RENAME = 2
        private const val MENU_DELETE = 3
    }
}

/**
 * The lengths of recordings read so far, by path, while the file's size and time stay the same:
 * kept for as long as the app runs, so opening Recordings again doesn't measure every file again.
 * The main thread's only.
 */
object RecordingLengths {
    data class Measured(val size: Long, val modified: Long, val ms: Long?)

    private val known = HashMap<String, Measured>()

    fun get(file: File, size: Long, modified: Long): Measured? =
        known[file.path]?.takeIf { it.size == size && it.modified == modified }

    fun put(file: File, size: Long, modified: Long, ms: Long?) {
        known[file.path] = Measured(size, modified, ms)
    }

    /** A recording renamed: its length goes with it (a rename keeps its size and time). */
    fun renamed(from: File, to: File) {
        known.remove(from.path)?.let { known[to.path] = it }
    }
}

/**
 * What the recordings screen keeps while Android recreates it (the phone turned, the window
 * resized): the player, so a recording plays on. Its audio focus request lives here too, since a
 * loss of focus pauses this player whichever screen is showing.
 */
class RecordingsModel(app: Application) : AndroidViewModel(app) {

    /** The screen showing the player now, told of every change; null between two screens. */
    var onChange: (() -> Unit)? = null

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(DecodingOutput.ATTRIBUTES)
        .setOnAudioFocusChangeListener { change ->
            // Another app's sound (a call, navigation): this is a recording being checked, not
            // music to duck under it, so it pauses, and the user resumes it.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                player.pause()
            }
        }
        .build()

    val player: PlayerController = PlayerController(
        newOutput = { DecodingOutput(app) },
        requestFocus = { audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED },
        focusRefused = app.getString(R.string.player_focus_refused),
        abandonFocus = { audioManager.abandonAudioFocusRequest(focusRequest) },
        onChange = { onChange?.invoke() },
    )

    override fun onCleared() = player.releaseAll()
}

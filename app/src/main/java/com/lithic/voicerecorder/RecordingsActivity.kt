package com.lithic.voicerecorder

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.media.AudioAttributes
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
import androidx.lifecycle.AndroidViewModel
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
    private var listing = 0 // bumped per listing, so an older one finishing late is dropped
    private var scrubbing = false
    private var toPlay: String? = null // asked for by the intent; played once listed

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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.recordings_title)
        settings = Settings(this)
        model.onChange = ::onPlayerChanged

        binding.recordingsList.layoutManager = LinearLayoutManager(this)
        binding.recordingsList.adapter = adapter
        binding.playButton.setOnClickListener { player.toggle() }
        binding.rewindButton.setOnClickListener { player.stop() }
        binding.positionBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.positionText.text = positionText(progress.toLong())
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
        // Recreated (turned, say), the recording asked for is already in the player.
        toPlay = if (savedInstanceState == null) intent.getStringExtra(EXTRA_PLAY) else null
        onPlayerChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PLAY)?.let {
            toPlay = it
            refresh()
        }
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
     * its way from the observer's thread can still post a refresh after [onDestroy] (seen when
     * the phone is turned), and the executor is shut down by then.
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
            val files = RecordingFiles.list(dir).map { Triple(it, it.length(), it.lastModified()) }
            main.post {
                if (id != listing || isDestroyed) return@post
                val rows = files.map { (file, size, modified) ->
                    val known = model.lengths[file.path]?.takeIf { it.size == size && it.modified == modified }
                    Row(file, size, modified, known?.ms, known != null)
                }
                show(dir, rows)
                for (row in rows) if (!row.measured && row.file != RecordingService.activeFile) measure(row, id)
                toPlay?.let { path ->
                    toPlay = null
                    rows.firstOrNull { it.file.path == path }?.let { play(it.file) }
                }
            }
        }
    }

    private fun measure(row: Row, id: Int) {
        inBackground {
            val ms = lengthOf(row.file)
            main.post {
                model.lengths[row.file.path] = RecordingsModel.Measured(row.size, row.modified, ms)
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
            row.file == player.file -> player.toggle()
            else -> play(row.file)
        }
    }

    private fun play(file: File) {
        if (file == RecordingService.activeFile) return
        player.play(file)
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
                            RecordingFiles.Problem.EXISTS -> R.string.recording_rename_exists
                        }
                    )
                    RecordingFiles.Rename.Unchanged -> dialog.dismiss()
                    is RecordingFiles.Rename.To -> {
                        if (file == RecordingService.activeFile) {
                            dialog.dismiss() // the take started meanwhile
                        } else if (file.renameTo(check.target)) {
                            player.renamed(file, check.target)
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
        binding.playerError.text = player.error
        binding.playerError.visibility = if (player.error != null) View.VISIBLE else View.GONE
        binding.playButton.setImageResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playButton.contentDescription = getString(if (player.isPlaying) R.string.player_pause else R.string.player_play)
        binding.playButton.isEnabled = file != null && !player.isOpening
        binding.rewindButton.isEnabled = file != null && !player.isOpening
        binding.positionBar.isEnabled = file != null && !player.isOpening && player.error == null
        binding.positionBar.max = player.durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        showPosition()
        main.removeCallbacks(tick)
        if (player.isPlaying) main.postDelayed(tick, TICK_MS)
        adapter.markPlayer(file, player.isPlaying)
    }

    private fun showPosition() {
        if (scrubbing) return
        val at = player.positionMs
        binding.positionBar.progress = at.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        binding.positionText.text = if (player.file == null) "" else positionText(at)
    }

    private fun positionText(at: Long): String {
        val length = player.durationMs
        return if (length > 0) "${RecordingFiles.clock(at)} / ${RecordingFiles.clock(length)}" else RecordingFiles.clock(at)
    }

    // --- adapter --------------------------------------------------------------

    private inner class RecordingsAdapter : RecyclerView.Adapter<RecordingsAdapter.Holder>() {
        private var rows: List<Row> = emptyList()
        private var loaded: File? = null
        private var playing = false

        inner class Holder(val item: ItemRecordingBinding) : RecyclerView.ViewHolder(item.root)

        fun submit(newRows: List<Row>) {
            rows = newRows
            @Suppress("NotifyDataSetChanged") // a folder listing, a handful of rows: no diffing needed
            notifyDataSetChanged()
        }

        fun update(file: File, change: (Row) -> Row) {
            val i = rows.indexOfFirst { it.file == file }
            if (i < 0) return
            rows = rows.toMutableList().also { it[i] = change(it[i]) }
            notifyItemChanged(i)
        }

        fun markPlayer(file: File?, isPlaying: Boolean) {
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
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(row.modified))
                recordingDetails.text = getString(R.string.recording_details, length, StorageUtil.formatBytes(row.size), date)
                playerMark.visibility = if (inPlayer) View.VISIBLE else View.GONE
                playerMark.setImageResource(if (inPlayer && playing) R.drawable.ic_volume_up else R.drawable.ic_pause)
                playerMark.contentDescription = if (inPlayer) getString(if (playing) R.string.player_playing else R.string.player_paused) else null
                root.setOnClickListener { onRowClicked(row) }
                recordingMenu.setOnClickListener { onRowMenu(row, it) }
            }
        }
    }

    companion object {
        /** Path of a recording to play once the list is shown: "Play" on the "Saved" message. */
        const val EXTRA_PLAY = "play"

        private const val TICK_MS = 200L
        private const val REFRESH_DEBOUNCE_MS = 300L
        private const val MENU_SHARE = 1
        private const val MENU_RENAME = 2
        private const val MENU_DELETE = 3
    }
}

/**
 * What the recordings screen keeps while Android recreates it (the phone turned, the window
 * resized): the player, so a recording plays on, and the lengths read so far. Its audio focus
 * request lives here too, since a loss of focus pauses this player whichever screen is showing.
 */
class RecordingsModel(app: Application) : AndroidViewModel(app) {

    data class Measured(val size: Long, val modified: Long, val ms: Long?)

    /** Lengths read so far, by path, kept while the file's size and time stay the same. */
    val lengths = HashMap<String, Measured>()

    /** The screen showing the player now, told of every change; null between two screens. */
    var onChange: (() -> Unit)? = null

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
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
        newOutput = { MediaPlayerOutput(app) },
        requestFocus = { audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED },
        abandonFocus = { audioManager.abandonAudioFocusRequest(focusRequest) },
        onChange = { onChange?.invoke() },
    )

    override fun onCleared() = player.releaseAll()
}

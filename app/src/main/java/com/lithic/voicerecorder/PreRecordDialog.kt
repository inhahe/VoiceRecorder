package com.lithic.voicerecorder

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import java.io.File

/**
 * Shown just before recording starts. Lets the user:
 *  - edit the suggested filename, or pick one of the recently-used names from the ▾ dropdown
 *    (handy for doing several takes of the same recording),
 *  - override the format, sample rate and bitrate for this recording (defaults come from Settings),
 *  - see how many hours of audio will fit in the target folder at the current settings.
 *
 * The line under the name field previews the file that will actually be written, including the
 * `_2`, `_3`, … suffix added when the name is already taken.
 *
 * On confirm it hands the chosen filename + [AudioFormatSpec] to the host as a fragment result
 * under [REQUEST_KEY] (decode it with [baseNameOf] / [specOf]). It's a result rather than a
 * callback because Android can recreate the dialog without the Activity that opened it, after the
 * process is killed in the background or on a configuration change. A callback set by the old
 * Activity is gone by then, so "Start recording" used to close the dialog and record nothing.
 */
class PreRecordDialog : DialogFragment() {

    private lateinit var settings: Settings
    private lateinit var saveDir: File

    private lateinit var nameField: AutoCompleteTextView
    private lateinit var recentButton: ImageButton
    private lateinit var resolvedNameText: TextView
    private lateinit var formatSpinner: Spinner
    private lateinit var sampleRateButton: Button
    private lateinit var bitrateButton: Button
    private lateinit var storageText: TextView
    private lateinit var extensionLabel: TextView
    private lateinit var rateWarningText: TextView
    private lateinit var rateNoteText: TextView

    private var recentNames: List<String> = emptyList()

    /** The input the recording will use, for the "this device records at 48 kHz" advisory. */
    private var selectedInput: InputDevices.InputOption? = null

    private lateinit var supportedFormats: List<AudioFormat>
    private var format: AudioFormat = AudioFormat.M4A
    private var sampleRate: Int = 44100
    private var bitrateKbps: Int = 128
    private var channels: Int = 1

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        settings = Settings(requireContext())
        saveDir = settings.saveDirectoryFile()

        // Seed from stored defaults, or from this dialog's own earlier choices when Android is
        // recreating it (see onSaveInstanceState).
        val defaults = settings.defaultSpec()
        val saved = savedInstanceState
        format = saved?.getString(STATE_FORMAT)?.let { AudioFormat.fromId(it) } ?: defaults.format
        sampleRate = saved?.getInt(STATE_SAMPLE_RATE, defaults.sampleRate) ?: defaults.sampleRate
        bitrateKbps = saved?.getInt(STATE_BITRATE, defaults.bitrateKbps) ?: defaults.bitrateKbps
        channels = defaults.channels

        val root = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_pre_record, null) as ViewGroup

        nameField = root.findViewById(R.id.pr_filename)
        recentButton = root.findViewById(R.id.pr_recent_names)
        resolvedNameText = root.findViewById(R.id.pr_resolved_name)
        formatSpinner = root.findViewById(R.id.pr_format)
        sampleRateButton = root.findViewById(R.id.pr_sample_rate)
        bitrateButton = root.findViewById(R.id.pr_bitrate)
        storageText = root.findViewById(R.id.pr_storage)
        extensionLabel = root.findViewById(R.id.pr_extension)
        rateWarningText = root.findViewById(R.id.pr_rate_warning)
        rateNoteText = root.findViewById(R.id.pr_rate_note)

        // "effective", not "match": under the default Automatic setting we still want to warn about
        // a plugged-in USB mic's native rate.
        selectedInput = InputDevices.effective(
            InputDevices.options(requireContext()), settings.inputDeviceKey
        )

        setupNameField()

        supportedFormats = AudioFormat.supported()
        val formatAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            supportedFormats.map { it.displayName }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        formatSpinner.adapter = formatAdapter
        formatSpinner.setSelection(supportedFormats.indexOf(format).coerceAtLeast(0))
        formatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                format = supportedFormats[pos]
                refreshControls()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        sampleRateButton.setOnClickListener { pickSampleRate() }
        bitrateButton.setOnClickListener { pickBitrate() }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.new_recording_title)
            .setView(root)
            .setPositiveButton(R.string.start_recording, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener { confirm(dialog) }
        }

        refreshControls()
        return dialog
    }

    private fun currentSpec() = AudioFormatSpec(format, sampleRate, bitrateKbps, channels)

    /** Prefill the name, wire up type-ahead completion and the ▾ list of previously-used names. */
    private fun setupNameField() {
        recentNames = settings.recentBaseNames

        val start = arguments?.getString(ARG_SUGGESTED_NAME) ?: settings.startingBaseName()
        nameField.setText(start)
        nameField.setSelection(start.length)

        nameField.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, recentNames)
        )
        nameField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateResolvedName()
        })

        recentButton.visibility = if (recentNames.isEmpty()) View.GONE else View.VISIBLE
        recentButton.setOnClickListener { pickRecentName() }
    }

    /** Full list of remembered names (plus a fresh timestamp), since type-ahead only prefix-matches. */
    private fun pickRecentName() {
        if (recentNames.isEmpty()) return
        val fresh = settings.suggestedBaseName()
        val values = listOf(fresh) + recentNames
        val labels = (listOf(getString(R.string.new_timestamp_name, fresh)) + recentNames).toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.recent_names_title)
            .setItems(labels) { _, which ->
                // filter = false: don't pop the type-ahead dropdown for a name we just injected.
                nameField.setText(values[which], false)
                nameField.setSelection(values[which].length)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Preview the file that will actually be written (including any `_2`, `_3`, … suffix). */
    private fun updateResolvedName() {
        val base = nameField.text.toString().trim()
        if (base.isEmpty() || base.any { it in ILLEGAL_FILENAME_CHARS }) {
            resolvedNameText.visibility = View.GONE
            return
        }
        val resolved = StorageUtil.nextAvailableName(base, format.extension) {
            File(saveDir, it).exists()
        }
        resolvedNameText.visibility = View.VISIBLE
        resolvedNameText.text = getString(R.string.will_save_as, resolved)
    }

    /**
     * The rates this device can actually record at in the currently-chosen format. Recomputed rather
     * than cached locally because it changes with the format (Opus is 48 kHz-family, WAV can reach
     * 96 kHz); [SampleRateSupport] does the caching.
     */
    private fun supportedRates(): List<Int> = SampleRateSupport.supported(format, channels)

    private fun refreshControls() {
        // A format change can invalidate the chosen rate (e.g. 96 kHz WAV → Opus), so land on the
        // nearest rate that format can actually encode instead of failing at start().
        sampleRate = AudioFormatSpec.snap(sampleRate, supportedRates())
        extensionLabel.text = ".${format.extension}"
        sampleRateButton.text = getString(R.string.sample_rate_value, AudioFormatSpec.hzLabel(sampleRate))
        bitrateButton.text = getString(R.string.bitrate_value, bitrateKbps)
        // Bitrate only matters for compressed formats.
        bitrateButton.isEnabled = format.compressed
        bitrateButton.alpha = if (format.compressed) 1f else 0.4f
        updateResolvedName()
        updateRateWarning()
        updateRateNote()
        updateStorage()
    }

    /**
     * The quiet counterpart to [updateRateWarning]: the chosen rate is one the input supports, but
     * below its ceiling, so the platform will resample down. That is a normal, usually-deliberate
     * trade (smaller files), so it is stated in dim text and cannot be tapped — there is nothing to
     * fix. [InputDevices.downsampleFrom] returns null in exactly the cases the red warning covers,
     * so the two lines are never both visible.
     */
    private fun updateRateNote() {
        val device = selectedInput
        val from = InputDevices.downsampleFrom(device, sampleRate)
        if (from == null || device == null) {
            rateNoteText.visibility = View.GONE
            return
        }
        rateNoteText.text = getString(
            R.string.rate_downsample, device.label,
            AudioFormatSpec.hzLabel(from), AudioFormatSpec.hzLabel(sampleRate)
        )
        rateNoteText.visibility = View.VISIBLE
    }

    /**
     * Warn when the chosen input can't run at the chosen sample rate natively — USB interfaces are
     * routinely 48 kHz-only, so the app's 44.1 kHz default costs a silent resample. Tapping the
     * warning adopts the device's own rate, when that's one we can offer.
     */
    private fun updateRateWarning() {
        // `offered` is the per-format list, so the tap-to-fix rate is always one we can select.
        val advice = InputDevices.rateAdvice(selectedInput, sampleRate, supportedRates())
        val device = selectedInput
        if (advice == null || device == null) {
            rateWarningText.visibility = View.GONE
            rateWarningText.setOnClickListener(null)
            return
        }
        val supported = AudioFormatSpec.hzListLabel(advice.supported)
        val current = AudioFormatSpec.hzLabel(sampleRate)
        val suggested = advice.suggested
        if (suggested == null) {
            rateWarningText.text =
                getString(R.string.rate_mismatch, device.label, supported, current)
            rateWarningText.setOnClickListener(null)
            rateWarningText.isClickable = false
        } else {
            rateWarningText.text = getString(
                R.string.rate_mismatch_fix, device.label, supported,
                AudioFormatSpec.hzLabel(suggested)
            )
            rateWarningText.setOnClickListener {
                sampleRate = suggested
                refreshControls()
            }
        }
        rateWarningText.visibility = View.VISIBLE
    }

    private fun updateStorage() {
        val text = StorageUtil.availableTimeText(saveDir, currentSpec())
        val free = StorageUtil.formatBytes(StorageUtil.freeBytes(saveDir))
        storageText.text = getString(R.string.storage_estimate, text, free)
    }

    private fun pickSampleRate() {
        val rates = supportedRates()
        val labels = rates.map { AudioFormatSpec.hzLabel(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sample_rate_title)
            .setItems(labels) { _, which ->
                sampleRate = rates[which]
                refreshControls()
            }
            .show()
    }

    private fun pickBitrate() {
        if (!format.compressed) {
            Toast.makeText(requireContext(), R.string.bitrate_na, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = AudioFormatSpec.BITRATES_KBPS.map { "$it kbps" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.bitrate_title)
            .setItems(labels) { _, which ->
                bitrateKbps = AudioFormatSpec.BITRATES_KBPS[which]
                refreshControls()
            }
            .show()
    }

    private fun confirm(dialog: Dialog) {
        val base = nameField.text.toString().trim()
        if (base.isEmpty()) {
            nameField.error = getString(R.string.filename_required)
            return
        }
        if (base.any { it in ILLEGAL_FILENAME_CHARS }) {
            nameField.error = getString(R.string.filename_illegal)
            return
        }
        val spec = currentSpec()
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(
                RESULT_BASE_NAME to base,
                RESULT_FORMAT to spec.format.id,
                RESULT_SAMPLE_RATE to spec.sampleRate,
                RESULT_BITRATE to spec.bitrateKbps,
                RESULT_CHANNELS to spec.channels,
            )
        )
        dialog.dismiss()
    }

    /**
     * The per-recording overrides live in fields, not in views. The name field and the format
     * spinner restore themselves, but these need saving by hand, or a recreated dialog would
     * quietly go back to the defaults.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::settings.isInitialized) return // never set up, so nothing chosen yet
        outState.putString(STATE_FORMAT, format.id)
        outState.putInt(STATE_SAMPLE_RATE, sampleRate)
        outState.putInt(STATE_BITRATE, bitrateKbps)
    }

    companion object {
        /** The fragment-result key the confirmed name and spec arrive under. */
        const val REQUEST_KEY = "pre_record"

        /** The base filename (no extension) from a [REQUEST_KEY] result. */
        fun baseNameOf(result: Bundle): String = result.getString(RESULT_BASE_NAME).orEmpty()

        /** The recording spec from a [REQUEST_KEY] result. */
        fun specOf(result: Bundle): AudioFormatSpec = AudioFormatSpec(
            format = AudioFormat.fromId(result.getString(RESULT_FORMAT)),
            sampleRate = result.getInt(RESULT_SAMPLE_RATE),
            bitrateKbps = result.getInt(RESULT_BITRATE),
            channels = result.getInt(RESULT_CHANNELS),
        )

        private const val RESULT_BASE_NAME = "base_name"
        private const val RESULT_FORMAT = "format"
        private const val RESULT_SAMPLE_RATE = "sample_rate"
        private const val RESULT_BITRATE = "bitrate_kbps"
        private const val RESULT_CHANNELS = "channels"

        private const val STATE_FORMAT = "state_format"
        private const val STATE_SAMPLE_RATE = "state_sample_rate"
        private const val STATE_BITRATE = "state_bitrate_kbps"

        private const val ARG_SUGGESTED_NAME = "suggested_name"
        private val ILLEGAL_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        fun new(suggestedName: String): PreRecordDialog =
            PreRecordDialog().apply {
                arguments = Bundle().apply { putString(ARG_SUGGESTED_NAME, suggestedName) }
            }
    }
}

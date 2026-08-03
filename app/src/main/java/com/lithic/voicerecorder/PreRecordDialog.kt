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
 * On confirm it hands back the chosen filename + [AudioFormatSpec] to the host.
 */
class PreRecordDialog : DialogFragment() {

    /** Called with the final base filename (no extension) and the resolved spec. */
    var onConfirmed: ((baseName: String, spec: AudioFormatSpec) -> Unit)? = null

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

    private var recentNames: List<String> = emptyList()

    private lateinit var supportedFormats: List<AudioFormat>
    private var format: AudioFormat = AudioFormat.M4A
    private var sampleRate: Int = 44100
    private var bitrateKbps: Int = 128
    private var channels: Int = 1

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        settings = Settings(requireContext())
        saveDir = settings.saveDirectoryFile()

        // Seed from stored defaults.
        val defaults = settings.defaultSpec()
        format = defaults.format
        sampleRate = defaults.sampleRate
        bitrateKbps = defaults.bitrateKbps
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

    private fun refreshControls() {
        extensionLabel.text = ".${format.extension}"
        sampleRateButton.text = getString(R.string.sample_rate_value, formatHz(sampleRate))
        bitrateButton.text = getString(R.string.bitrate_value, bitrateKbps)
        // Bitrate only matters for compressed formats.
        bitrateButton.isEnabled = format.compressed
        bitrateButton.alpha = if (format.compressed) 1f else 0.4f
        updateResolvedName()
        updateStorage()
    }

    private fun updateStorage() {
        val text = StorageUtil.availableTimeText(saveDir, currentSpec())
        val free = StorageUtil.formatBytes(StorageUtil.freeBytes(saveDir))
        storageText.text = getString(R.string.storage_estimate, text, free)
    }

    private fun pickSampleRate() {
        val labels = AudioFormatSpec.SAMPLE_RATES.map { formatHz(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sample_rate_title)
            .setItems(labels) { _, which ->
                sampleRate = AudioFormatSpec.SAMPLE_RATES[which]
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
        onConfirmed?.invoke(base, currentSpec())
        dialog.dismiss()
    }

    private fun formatHz(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format("%.3g kHz", hz / 1000.0)

    companion object {
        private const val ARG_SUGGESTED_NAME = "suggested_name"
        private val ILLEGAL_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        fun new(suggestedName: String): PreRecordDialog =
            PreRecordDialog().apply {
                arguments = Bundle().apply { putString(ARG_SUGGESTED_NAME, suggestedName) }
            }
    }
}

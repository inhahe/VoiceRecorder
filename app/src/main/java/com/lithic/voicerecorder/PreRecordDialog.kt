package com.lithic.voicerecorder

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import java.io.File

/**
 * Shown just before recording starts. Lets the user:
 *  - edit the suggested filename,
 *  - override the format, sample rate and bitrate for this recording (defaults come from Settings),
 *  - see how many hours of audio will fit in the target folder at the current settings.
 *
 * On confirm it hands back the chosen filename + [AudioFormatSpec] to the host.
 */
class PreRecordDialog : DialogFragment() {

    /** Called with the final base filename (no extension) and the resolved spec. */
    var onConfirmed: ((baseName: String, spec: AudioFormatSpec) -> Unit)? = null

    private lateinit var settings: Settings
    private lateinit var saveDir: File

    private lateinit var nameField: EditText
    private lateinit var formatSpinner: Spinner
    private lateinit var sampleRateButton: Button
    private lateinit var bitrateButton: Button
    private lateinit var storageText: TextView
    private lateinit var extensionLabel: TextView

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
        formatSpinner = root.findViewById(R.id.pr_format)
        sampleRateButton = root.findViewById(R.id.pr_sample_rate)
        bitrateButton = root.findViewById(R.id.pr_bitrate)
        storageText = root.findViewById(R.id.pr_storage)
        extensionLabel = root.findViewById(R.id.pr_extension)

        nameField.setText(arguments?.getString(ARG_SUGGESTED_NAME) ?: settings.suggestedBaseName())

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

    private fun refreshControls() {
        extensionLabel.text = ".${format.extension}"
        sampleRateButton.text = getString(R.string.sample_rate_value, formatHz(sampleRate))
        bitrateButton.text = getString(R.string.bitrate_value, bitrateKbps)
        // Bitrate only matters for compressed formats.
        bitrateButton.isEnabled = format.compressed
        bitrateButton.alpha = if (format.compressed) 1f else 0.4f
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

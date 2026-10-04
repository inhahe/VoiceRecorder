package com.lithic.voicerecorder

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lithic.voicerecorder.databinding.ActivitySettingsBinding

/**
 * Lets the user configure the *defaults* that seed each new recording, plus the save folder,
 * channel count and filename pattern. These are what the pre-record dialog starts from.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    private lateinit var supportedFormats: List<AudioFormat>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padBelowActionBar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings_title)

        settings = Settings(this)
        supportedFormats = AudioFormat.supported()

        // A fragment result rather than a callback, so the folder still arrives if Android
        // recreates this screen while the picker is open (see DirectoryPickerDialog).
        supportFragmentManager.setFragmentResultListener(DirectoryPickerDialog.REQUEST_KEY, this) { _, result ->
            settings.saveDirectory = DirectoryPickerDialog.pathOf(result)
            refresh()
        }

        setupFormat()
        setupSampleRate()
        setupBitrate()
        setupChannels()
        setupCaptureSource()
        setupFolder()
        setupFilename()
        setupReuseName()
        setupRecentNames()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupFormat() {
        binding.settingFormat.setOnClickListener {
            val labels = supportedFormats.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.default_format_setting)
                .setItems(labels) { _, which ->
                    settings.defaultFormat = supportedFormats[which]
                    refresh()
                }
                .show()
        }
    }

    /** Rates this device can record in the *default* format — the picker never offers a dead one. */
    private fun supportedRates(): List<Int> =
        SampleRateSupport.supported(settings.defaultFormat, settings.channels)

    private fun setupSampleRate() {
        binding.settingSampleRate.setOnClickListener {
            val rates = supportedRates()
            val labels = rates.map { hzLabel(it) }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.sample_rate_title)
                .setItems(labels) { _, which ->
                    settings.defaultSampleRate = rates[which]
                    refresh()
                }
                .show()
        }
    }

    private fun setupBitrate() {
        binding.settingBitrate.setOnClickListener {
            val labels = AudioFormatSpec.BITRATES_KBPS.map { "$it kbps" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.bitrate_title)
                .setItems(labels) { _, which ->
                    settings.defaultBitrateKbps = AudioFormatSpec.BITRATES_KBPS[which]
                    refresh()
                }
                .show()
        }
    }

    private fun setupChannels() {
        binding.settingChannels.setOnClickListener {
            val labels = arrayOf(getString(R.string.mono), getString(R.string.stereo))
            AlertDialog.Builder(this)
                .setTitle(R.string.channels_title)
                .setItems(labels) { _, which ->
                    settings.channels = if (which == 0) 1 else 2
                    refresh()
                }
                .show()
        }
    }

    /**
     * The audio source is fixed when `AudioRecord` is built, so this is a defaults-screen setting
     * (no per-recording override) and the summary says it takes effect next time.
     */
    private fun setupCaptureSource() {
        binding.settingCaptureSource.setOnClickListener {
            val labels = arrayOf(
                getString(R.string.capture_source_processed),
                getString(R.string.capture_source_raw)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.capture_source_title)
                .setSingleChoiceItems(
                    labels,
                    if (settings.captureSource == CaptureSource.RAW) 1 else 0
                ) { dialog, which ->
                    settings.captureSource =
                        if (which == 1) CaptureSource.RAW else CaptureSource.PROCESSED
                    dialog.dismiss()
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupFolder() {
        binding.settingFolder.setOnClickListener {
            DirectoryPickerDialog.new(settings.saveDirectory ?: Settings.defaultSaveDirectory().absolutePath)
                .show(supportFragmentManager, "dir_picker")
        }
    }

    private fun setupFilename() {
        binding.settingFilename.setOnClickListener {
            val input = android.widget.EditText(this).apply {
                setText(settings.filenamePattern)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.filename_pattern_title)
                .setMessage(R.string.filename_pattern_help)
                .setView(input)
                .setPositiveButton(R.string.save) { _, _ ->
                    val text = input.text.toString().trim()
                    if (text.isNotEmpty()) {
                        settings.filenamePattern = text
                        refresh()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupReuseName() {
        binding.settingReuseName.setOnClickListener {
            settings.reuseLastFilename = !settings.reuseLastFilename
            refresh()
        }
    }

    private fun setupRecentNames() {
        binding.settingRecentNames.setOnClickListener {
            val names = settings.recentBaseNames
            if (names.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(R.string.recent_names_title)
                .setItems(names.toTypedArray(), null)
                .setPositiveButton(R.string.clear_recent_names) { _, _ ->
                    settings.clearRecentBaseNames()
                    Toast.makeText(this, R.string.recent_names_cleared, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refresh() {
        val s = settings
        // Changing the default format (or channels) can strand the stored rate — 48 kHz-only Opus
        // after a 96 kHz WAV default, say. Snap it here so the screen never *shows* a rate that a
        // recording would silently move off, and so settings written by an older build get fixed up.
        val snapped = AudioFormatSpec.snap(s.defaultSampleRate, supportedRates())
        if (snapped != s.defaultSampleRate) s.defaultSampleRate = snapped

        binding.valueFormat.text = s.defaultFormat.displayName
        binding.valueSampleRate.text = hzLabel(s.defaultSampleRate)
        binding.valueBitrate.text = if (s.defaultFormat.compressed) {
            "${s.defaultBitrateKbps} kbps"
        } else {
            getString(R.string.not_applicable_lossless)
        }
        binding.settingBitrate.isEnabled = s.defaultFormat.compressed
        binding.settingBitrate.alpha = if (s.defaultFormat.compressed) 1f else 0.4f

        binding.valueChannels.text =
            if (s.channels >= 2) getString(R.string.stereo) else getString(R.string.mono)

        // Say plainly when "raw" had to fall back, rather than implying the request was honoured.
        binding.valueCaptureSource.text = when {
            s.captureSource == CaptureSource.PROCESSED ->
                getString(R.string.capture_source_processed_summary)
            CaptureSource.unprocessedSupported(this) ->
                getString(R.string.capture_source_raw_summary)
            else -> getString(R.string.capture_source_raw_fallback)
        }
        binding.valueFolder.text = s.saveDirectory ?: getString(R.string.no_folder_chosen)
        binding.valueFilename.text = try {
            "${s.suggestedBaseName()}.${s.defaultFormat.extension}"
        } catch (_: Exception) {
            s.filenamePattern
        }

        binding.switchReuseName.isChecked = s.reuseLastFilename
        val recent = s.recentBaseNames
        binding.valueRecentNames.text = if (recent.isEmpty()) {
            getString(R.string.recent_names_empty)
        } else {
            getString(R.string.recent_names_summary, recent.size)
        }
        binding.settingRecentNames.isEnabled = recent.isNotEmpty()
        binding.settingRecentNames.alpha = if (recent.isEmpty()) 0.4f else 1f
    }

    private fun hzLabel(hz: Int): String = AudioFormatSpec.hzLabel(hz)
}

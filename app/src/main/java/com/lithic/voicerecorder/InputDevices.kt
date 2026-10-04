package com.lithic.voicerecorder

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Enumerating and remembering *which microphone* to record from — the built-in mic, a wired or
 * Bluetooth headset, or a USB audio interface / USB microphone.
 *
 * Android has no "pick an input" API on [android.media.AudioRecord] itself; what it has is
 * [android.media.AudioRecord.setPreferredDevice], a *hint* that routes capture to a given
 * [AudioDeviceInfo] if the platform can honour it. So the model here is:
 *
 * 1. enumerate real capture devices with [AudioManager.getDevices],
 * 2. persist the user's choice as a stable [InputOption.key] — **not** an [AudioDeviceInfo.getId],
 *    which is reassigned every time a device is unplugged and plugged back in,
 * 3. re-resolve that key to a live [AudioDeviceInfo] each time a recording starts (or the user
 *    changes the selection mid-recording),
 * 4. when the chosen device isn't attached, **ask** — never set no preference and let Android
 *    route to the phone's own mic in its place (see [Resolved] and [stepFor]).
 *
 * Everything except [options]/[resolve] is pure so it can be covered by JVM unit tests
 * (`InputDevicesTest`); the device labels live in code rather than `strings.xml` for the same reason
 * (as does [Gain.label]).
 */
object InputDevices {

    /** The stored key meaning "let Android choose" — its normal mic-priority order. */
    const val AUTOMATIC = ""

    /** Shown for [AUTOMATIC]. */
    const val AUTOMATIC_LABEL = "Automatic (system default)"

    /** Appended when a remembered device isn't currently attached. */
    const val DISCONNECTED_SUFFIX = " (not connected)"

    /**
     * Capture device types worth offering. Deliberately a whitelist: [AudioManager.getDevices] also
     * reports things like telephony and FM inputs that a voice recorder can't meaningfully use.
     */
    private val CAPTURE_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    /**
     * One selectable input.
     *
     * [key] is what gets persisted: type + product name, which survives a reconnect (unlike
     * [deviceId], which does not).
     *
     * [sampleRates] and [channelCounts] mirror [AudioDeviceInfo.getSampleRates] /
     * [AudioDeviceInfo.getChannelCounts]. **Empty means "unspecified", not "supports nothing"** —
     * the platform is explicit that a device may decline to enumerate its capabilities, so an empty
     * list must be treated as "anything goes" rather than as a constraint.
     */
    data class InputOption(
        val deviceId: Int,
        val type: Int,
        val productName: String,
        val sampleRates: List<Int> = emptyList(),
        val channelCounts: List<Int> = emptyList(),
    ) {
        val key: String get() = "$type:$productName"
        val label: String get() = label(type, productName)
    }

    /**
     * Why a requested sample rate will be resampled: what the device actually offers, and the
     * nearest rate this app can select that the device supports natively ([suggested] is null when
     * the device supports nothing we offer, leaving only the warning).
     */
    data class RateAdvice(val supported: List<Int>, val suggested: Int?)

    /**
     * Null when [requested] is fine — no device chosen, capabilities unspecified, or the rate is
     * natively supported. Otherwise the mismatch, for the pre-record dialog to warn about.
     *
     * USB interfaces very commonly run at 48 kHz only, so recording them at the app's 44.1 kHz
     * default silently costs a resample.
     */
    fun rateAdvice(
        option: InputOption?,
        requested: Int,
        offered: List<Int> = AudioFormatSpec.SAMPLE_RATES
    ): RateAdvice? {
        val rates = option?.sampleRates.orEmpty()
        if (rates.isEmpty() || requested in rates) return null
        val suggested = offered.filter { it in rates }.minByOrNull { kotlin.math.abs(it - requested) }
        return RateAdvice(rates, suggested)
    }

    /**
     * The rate a recording will be resampled *down* from, or null when nothing is lost.
     *
     * Deliberately quieter than [rateAdvice], and mutually exclusive with it by construction: this
     * returns null unless [requested] is one of the device's own declared rates, which is exactly
     * the case [rateAdvice] treats as fine. Recording below the hardware's ceiling is a normal,
     * usually-deliberate size/quality trade — worth stating, not worth warning about — whereas
     * recording *above* it is pure waste and gets the tappable warning.
     *
     * The value returned is the highest rate the device declares, which is a proxy for its native
     * rate rather than a reading of it: [AudioDeviceInfo.getSampleRates] reports what the platform
     * will *deliver* (including rates it produces by resampling), not what the ADC runs at. Devices
     * list everything at or below native, so the top of that list is the best estimate available —
     * and being wrong costs an over-cautious line of text, nothing more.
     */
    fun downsampleFrom(option: InputOption?, requested: Int): Int? {
        val rates = option?.sampleRates.orEmpty()
        if (rates.isEmpty() || requested !in rates) return null
        val top = rates.max()
        return if (requested < top) top else null
    }

    /** Human label for a device type + product name. */
    fun label(type: Int, productName: String): String {
        val name = productName.trim()
        return when (type) {
            // The built-in mic reports the phone's model as its product name ("SM-G991U"), which is
            // noise here — everyone knows what their own phone is called.
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                if (name.isEmpty()) "Bluetooth headset" else "$name (Bluetooth)"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                if (name.isEmpty()) "USB microphone" else "$name (USB)"
            else -> if (name.isEmpty()) "Input $type" else name
        }
    }

    /** Rebuild a label from a stored [key] alone, for a device that isn't attached right now. */
    fun labelFromKey(key: String): String {
        val type = key.substringBefore(':').toIntOrNull() ?: return key
        return label(type, key.substringAfter(':', ""))
    }

    /**
     * Find the option a stored [key] refers to. Falls back to *any* device of the same type, so a
     * USB interface that comes back with a slightly different product string (or a second headset)
     * still satisfies "record from USB". It never crosses types: when nothing of the chosen type
     * is attached the answer is null, and the phone's mic is not offered in its place.
     */
    fun match(options: List<InputOption>, key: String?): InputOption? {
        if (key.isNullOrEmpty()) return null
        options.firstOrNull { it.key == key }?.let { return it }
        val type = key.substringBefore(':').toIntOrNull() ?: return null
        return options.firstOrNull { it.type == type }
    }

    /**
     * True when [key] names a specific input and nothing attached satisfies it. In that state
     * nothing may record: setting no preference would let Android route to the phone's own mic, so
     * the user is asked to pick an input instead. [AUTOMATIC] is never unavailable, because it
     * *means* "whatever Android picks".
     */
    fun isUnavailable(options: List<InputOption>, key: String?): Boolean =
        !key.isNullOrEmpty() && match(options, key) == null

    /** What a recording does when its chosen input is checked again; see [stepFor]. */
    enum class Step { KEEP, PAUSE, RESUME }

    /**
     * What a recording should do when the attached devices or the selection change. The service
     * applies this after every such change; it's pure so the rules are JVM-tested.
     *
     * - Input gone while capturing → [Step.PAUSE]. Android re-routes a stream whose preferred
     *   device has vanished to the phone's mic by itself, so carrying on would record from a mic
     *   nobody chose. A gap in the file is better than that.
     * - Input gone while already paused → [Step.KEEP]: nothing is being captured.
     * - Input present, and this pause was made because it was missing ([pausedForInput]) →
     *   [Step.RESUME]: the reason for the pause is gone.
     * - Input present otherwise → [Step.KEEP]. A pause the user made is theirs to end; plugging a
     *   mic back in never restarts it.
     */
    fun stepFor(available: Boolean, paused: Boolean, pausedForInput: Boolean): Step = when {
        !available -> if (paused) Step.KEEP else Step.PAUSE
        pausedForInput -> Step.RESUME
        else -> Step.KEEP
    }

    /**
     * The device a recording is *likely* to end up on for a given selection — [match] for an
     * explicit choice, and for [AUTOMATIC] a stand-in for Android's own priority, which puts an
     * attached external mic (USB, headset) ahead of the built-in one.
     *
     * Only used for advisory purposes (the sample-rate warning), never for routing: the default
     * selection is Automatic, so without this a USB mic's 48 kHz-only limitation would go
     * unmentioned for exactly the users who never opened the input picker. Being wrong costs a
     * warning shown or missed, nothing more.
     */
    fun effective(options: List<InputOption>, key: String?): InputOption? =
        if (!key.isNullOrEmpty()) match(options, key)
        else options.firstOrNull { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: options.firstOrNull()

    /** What the home screen shows for the current selection. */
    fun summary(options: List<InputOption>, key: String?): String = when {
        key.isNullOrEmpty() -> AUTOMATIC_LABEL
        else -> match(options, key)?.label ?: (labelFromKey(key) + DISCONNECTED_SUFFIX)
    }

    /**
     * Collapse devices that resolve to the same [InputOption.key]. Phones routinely expose several
     * `TYPE_BUILTIN_MIC` entries (bottom / top / back capsules) that are indistinguishable here.
     * Their capabilities are merged rather than the first one winning, so the surviving row doesn't
     * under-report what the mic can do.
     */
    fun dedupe(options: List<InputOption>): List<InputOption> {
        val byKey = LinkedHashMap<String, InputOption>()
        for (option in options) {
            val prev = byKey[option.key]
            byKey[option.key] = if (prev == null) option else prev.copy(
                sampleRates = mergeCaps(prev.sampleRates, option.sampleRates),
                channelCounts = mergeCaps(prev.channelCounts, option.channelCounts),
            )
        }
        return byKey.values.toList()
    }

    /**
     * Union of two capability lists — but an empty list means "unspecified", so merging anything
     * with it yields "unspecified" rather than falsely narrowing the result to the other list.
     */
    private fun mergeCaps(a: List<Int>, b: List<Int>): List<Int> =
        if (a.isEmpty() || b.isEmpty()) emptyList() else (a + b).distinct().sorted()

    // --- Android-facing -------------------------------------------------------

    fun optionFor(info: AudioDeviceInfo): InputOption = InputOption(
        deviceId = info.id,
        type = info.type,
        productName = info.productName?.toString().orEmpty().trim(),
        sampleRates = info.sampleRates.distinct().sorted(),
        channelCounts = info.channelCounts.distinct().sorted(),
    )

    /** Currently attached capture devices, de-duplicated, in the platform's own order. */
    fun options(context: Context): List<InputOption> = dedupe(scan(context).map { optionFor(it) })

    /**
     * What a stored selection comes to, given the devices attached right now. There are three
     * outcomes rather than a nullable device. "Set no preference" is right for [AUTOMATIC] and
     * wrong for a chosen device that has gone missing, and folding both into `null` is how an
     * unplugged USB mic used to turn into a recording from the phone's own mic.
     */
    sealed interface Resolved {
        /** [AUTOMATIC]: set no preference, and Android routes by its own priority. */
        data object Automatic : Resolved

        /** The chosen input is attached. [info] is looked up fresh, since ids change on reconnect. */
        class Device(val info: AudioDeviceInfo) : Resolved

        /** The chosen input isn't attached. Ask the user; never treat this as [Automatic]. */
        data object Unavailable : Resolved
    }

    /** Resolve a stored [key] against the capture devices attached right now. */
    fun resolve(context: Context, key: String?): Resolved {
        if (key.isNullOrEmpty()) return Resolved.Automatic
        val devices = scan(context)
        val chosen = match(dedupe(devices.map { optionFor(it) }), key) ?: return Resolved.Unavailable
        val info = devices.firstOrNull { optionFor(it).key == chosen.key } ?: return Resolved.Unavailable
        return Resolved.Device(info)
    }

    private fun scan(context: Context): List<AudioDeviceInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in CAPTURE_TYPES }
    }
}

/**
 * A recording was asked to start while its chosen input is unplugged. The service throws this
 * rather than start with no preference set, which would quietly record from the phone's own mic.
 */
class InputUnavailableException(key: String) :
    IllegalStateException("${InputDevices.labelFromKey(key)} isn't connected")

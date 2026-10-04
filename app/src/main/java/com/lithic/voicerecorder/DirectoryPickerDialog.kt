package com.lithic.voicerecorder

import android.app.Dialog
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import java.io.File

/**
 * A self-contained directory navigator.
 *
 * Lets the user browse the real filesystem, drill into folders, go up to the parent, create a new
 * folder, and choose the current folder as the save location. Folders that can't be written to are
 * shown greyed-out with a lock icon and can't be chosen.
 *
 * The chosen folder goes back to the host as a fragment result under [REQUEST_KEY] (decode it
 * with [pathOf]), not through a callback, so it still arrives after Android has recreated the
 * dialog and its Activity. See [PreRecordDialog] for how a callback lost that.
 */
class DirectoryPickerDialog : DialogFragment() {

    private lateinit var current: File
    private lateinit var pathText: TextView
    private lateinit var writableText: TextView
    private lateinit var listContainer: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Reopen where the user had browsed to if the dialog is being recreated.
        val startPath = savedInstanceState?.getString(STATE_CURRENT) ?: arguments?.getString(ARG_START_PATH)
        current = resolveStart(startPath)

        val root = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_directory_picker, null) as ViewGroup

        pathText = root.findViewById(R.id.dir_current_path)
        writableText = root.findViewById(R.id.dir_writable_status)
        listContainer = root.findViewById(R.id.dir_list_container)

        root.findViewById<View>(R.id.dir_up_button).setOnClickListener { navigateUp() }
        root.findViewById<View>(R.id.dir_new_folder_button).setOnClickListener { promptNewFolder() }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.choose_folder_title)
            .setView(root)
            .setPositiveButton(R.string.use_this_folder, null) // overridden below to prevent auto-dismiss
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
                chooseCurrent(dialog)
            }
        }

        render()
        return dialog
    }

    private fun resolveStart(startPath: String?): File {
        val candidate = startPath?.let { File(it) }
        return when {
            candidate != null && candidate.isDirectory -> candidate
            candidate != null && candidate.parentFile?.isDirectory == true -> candidate.parentFile!!
            else -> Environment.getExternalStorageDirectory()
                .takeIf { it.isDirectory } ?: File("/")
        }
    }

    private fun navigateUp() {
        current.parentFile?.let {
            current = it
            render()
        }
    }

    private fun chooseCurrent(dialog: Dialog) {
        if (!current.canWrite()) {
            Toast.makeText(requireContext(), R.string.folder_not_writable, Toast.LENGTH_SHORT).show()
            return
        }
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_PATH to current.absolutePath))
        dialog.dismiss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::current.isInitialized) outState.putString(STATE_CURRENT, current.absolutePath)
    }

    private fun promptNewFolder() {
        if (!current.canWrite()) {
            Toast.makeText(requireContext(), R.string.cant_create_here, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.folder_name_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.new_folder_title)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/') || name == "." || name == "..") {
                    Toast.makeText(requireContext(), R.string.invalid_folder_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newDir = File(current, name)
                if (newDir.exists()) {
                    Toast.makeText(requireContext(), R.string.folder_exists, Toast.LENGTH_SHORT).show()
                } else if (newDir.mkdirs()) {
                    current = newDir
                    render()
                } else {
                    Toast.makeText(requireContext(), R.string.create_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun render() {
        pathText.text = current.absolutePath
        val writable = current.canWrite()
        writableText.text = getString(
            if (writable) R.string.folder_writable else R.string.folder_readonly
        )
        writableText.setTextColor(
            resources.getColor(
                if (writable) R.color.ok_green else R.color.warn_red,
                null
            )
        )

        listContainer.removeAllViews()

        val children = current.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        if (children.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = getString(R.string.no_subfolders)
                setPadding(24, 24, 24, 24)
                setTextColor(resources.getColor(R.color.dim_text, null))
            }
            listContainer.addView(empty)
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        for (child in children) {
            val row = inflater.inflate(R.layout.item_directory, listContainer, false)
            val name = row.findViewById<TextView>(R.id.item_dir_name)
            val icon = row.findViewById<ImageView>(R.id.item_dir_icon)
            val lock = row.findViewById<ImageView>(R.id.item_dir_lock)

            name.text = child.name
            val canEnter = child.canRead() && child.canWrite()
            if (canEnter) {
                lock.visibility = View.GONE
                name.setTextColor(resources.getColor(R.color.normal_text, null))
                icon.setColorFilter(resources.getColor(R.color.folder_tint, null))
                row.isEnabled = true
                row.setOnClickListener {
                    current = child
                    render()
                }
            } else {
                // Off-limits: still let the user peek in if readable, but flag it.
                lock.visibility = View.VISIBLE
                name.setTextColor(resources.getColor(R.color.dim_text, null))
                icon.setColorFilter(resources.getColor(R.color.dim_text, null))
                row.setOnClickListener {
                    if (child.canRead()) {
                        current = child
                        render()
                    } else {
                        Toast.makeText(requireContext(), R.string.folder_off_limits, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            listContainer.addView(row)
        }
    }

    companion object {
        /** The fragment-result key the chosen folder arrives under. */
        const val REQUEST_KEY = "directory_picker"

        /** The chosen absolute path from a [REQUEST_KEY] result. */
        fun pathOf(result: Bundle): String = result.getString(RESULT_PATH).orEmpty()

        private const val RESULT_PATH = "path"
        private const val STATE_CURRENT = "state_current"
        private const val ARG_START_PATH = "start_path"

        fun new(startPath: String?): DirectoryPickerDialog =
            DirectoryPickerDialog().apply {
                arguments = Bundle().apply { putString(ARG_START_PATH, startPath) }
            }
    }
}

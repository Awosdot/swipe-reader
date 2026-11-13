package com.awoo.swipereader

import android.os.Environment
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Small in-app folder browser. Shows folders with a PDF count so the right one is easy to spot. */
object FolderPicker {

    private fun pdfCount(dir: File): Int =
        dir.listFiles()?.count { it.isFile && it.name.endsWith(".pdf", ignoreCase = true) } ?: 0

    fun show(activity: AppCompatActivity, store: PdfStore, onDone: () -> Unit) {
        val root = Environment.getExternalStorageDirectory()
        var current: File = store.chosenFolder() ?: store.folders().firstOrNull() ?: root
        if (!current.isDirectory) current = root

        val pathView = TextView(activity).apply {
            textSize = 13f
            setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(8))
        }
        val list = ListView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(pathView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(list, LinearLayout.LayoutParams(MATCH, activity.dp(360)))
        }

        var entries: List<File?> = emptyList()

        fun render() {
            val kids = current.listFiles()
            val dirs = (kids ?: emptyArray()).filter { it.isDirectory }
                .sortedBy { it.name.lowercase() }
            val rows = ArrayList<File?>()
            val labels = ArrayList<String>()
            if (current.path != root.path) {
                rows.add(null)
                labels.add(".. (up one level)")
            }
            for (d in dirs) {
                rows.add(d)
                val n = pdfCount(d)
                labels.add(if (n > 0) "${d.name}   ($n PDFs)" else d.name)
            }
            entries = rows
            pathView.text = if (kids == null) {
                "${current.path}\nCannot open this folder"
            } else {
                "${current.path}\n${pdfCount(current)} PDFs directly in this folder"
            }
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels)
        }

        list.setOnItemClickListener { _: android.widget.AdapterView<*>, _: android.view.View, pos: Int, _: Long ->
            val e = entries[pos]
            current = e ?: current.parentFile ?: root
            render()
        }
        render()

        AlertDialog.Builder(activity)
            .setTitle("Choose PDF folder")
            .setView(box)
            .setPositiveButton("Use this folder") { _, _ ->
                store.setChosenFolder(current)
                onDone()
            }
            .setNeutralButton("Auto") { _, _ ->
                store.setChosenFolder(null)
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

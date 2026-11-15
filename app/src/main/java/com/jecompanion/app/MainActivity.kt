package com.jecompanion.app

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("jee_companion", MODE_PRIVATE) }
    private val items = mutableListOf<JSONObject>()
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var timer: CountDownTimer? = null
    private var seconds = 0
    private var running = false
    private var timerText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        load()
        render()
    }

    private fun load() {
        val raw = prefs.getString("items", "[]") ?: "[]"
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) items.add(arr.getJSONObject(i))
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 20)
            setBackgroundColor(Color.BLACK)
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "JEE Companion"
            textSize = 27f
            setTextColor(Color.WHITE)
        }
        body.addView(title)
        body.addView(TextView(this).apply {
            text = "TODAY'S TARGETS"
            textSize = 12f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(0, 22, 0, 8)
        })
        summary = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        body.addView(summary)
        val add = Button(this).apply { text = "＋  Add today's question target"; setOnClickListener { addTarget() } }
        body.addView(add)
        body.addView(TextView(this).apply {
            text = "QUESTION LIST"
            textSize = 12f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(0, 22, 0, 8)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val stats = Button(this).apply { text = "Statistics"; setOnClickListener { showStats() } }
        root.addView(stats)
        setContentView(root)
        refresh()
    }

    private fun addTarget() {
        val subjects = arrayOf("Physics", "Chemistry", "Mathematics")
        val subject = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, subjects.toList()) }
        val chapter = EditText(this).apply { hint = "Chapter name"; setSingleLine() }
        val lecture = EditText(this).apply { hint = "Lecture label (e.g. Lecture 5)"; setSingleLine() }
        val questions = EditText(this).apply { hint = "Exact question numbers (e.g. 1,2,3,7,12)"; setSingleLine() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 0)
            addView(subject); addView(chapter); addView(lecture); addView(questions)
        }
        AlertDialogBuilder(this).show(box) {
            val nums = questions.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (chapter.text.isBlank() || nums.isEmpty()) {
                Toast.makeText(this, "Enter a chapter and question numbers", Toast.LENGTH_SHORT).show()
            } else {
                items.add(JSONObject().put("subject", subjects[subject.selectedItemPosition])
                    .put("chapter", chapter.text.toString()).put("lecture", lecture.text.toString())
                    .put("questions", JSONArray(nums)).put("done", false).put("status", "Pending").put("seconds", 0))
                save(); refresh()
            }
        }
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val done = items.count { it.optBoolean("done") }
        summary.text = "${done} / ${items.size} targets completed"
        items.forEachIndexed { index, item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12)
                setBackgroundColor(0xFF171717.toInt())
            }
            val questions = item.optJSONArray("questions") ?: JSONArray()
            val nums = (0 until questions.length()).joinToString(", ") { questions.optString(it) }
            card.addView(TextView(this).apply {
                text = "${item.optString("subject")}  •  ${item.optString("chapter")}\n${item.optString("lecture")}  |  Q: $nums\n${item.optString("status")}"
                textSize = 15f; setTextColor(Color.WHITE)
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            actions.addView(Button(this).apply { text = "Solve"; setOnClickListener { solve(index) } })
            actions.addView(Button(this).apply {
                text = if (item.optBoolean("done")) "Completed" else "Mark done"
                setOnClickListener { item.put("done", true).put("status", "Completed"); save(); refresh() }
            })
            actions.addView(Button(this).apply { text = "Delete"; setOnClickListener { items.removeAt(index); save(); refresh() } })
            card.addView(actions)
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 12
            list.addView(card, lp)
        }
    }

    private fun solve(index: Int) {
        val item = items[index]
        val q = item.optJSONArray("questions") ?: JSONArray()
        if (q.length() == 0) return
        val choices = (0 until q.length()).map { q.optString(it) }.toTypedArray()
        var selected = 0
        val picker = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices.toList()) }
        picker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { selected = position }
        }
        val time = TextView(this).apply { text = "00:00"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        timerText = time
        val start = Button(this).apply { text = "Start / Resume"; setOnClickListener { startTimer(time, index) } }
        val pause = Button(this).apply { text = "Pause"; setOnClickListener { timer?.cancel(); running = false } }
        val mark = Button(this).apply { text = "Save time & result"; setOnClickListener {
            item.put("seconds", item.optInt("seconds") + seconds)
            item.put("status", "Attempted")
            timer?.cancel(); running = false; seconds = 0; save(); refresh()
        } }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 0); addView(TextView(this@MainActivity).apply { text = "Question number"; setTextColor(Color.WHITE) }); addView(picker); addView(time); addView(start); addView(pause); addView(mark) }
        android.app.AlertDialog.Builder(this).setTitle("Solve question").setView(box).setNegativeButton("Close") { _, _ -> timer?.cancel(); running = false }.show()
    }

    private fun startTimer(view: TextView, index: Int) {
        if (running) return
        running = true
        timer = object : CountDownTimer(24 * 60 * 60 * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                seconds++
                view.text = String.format("%02d:%02d", seconds / 60, seconds % 60)
            }
            override fun onFinish() { running = false }
        }.start()
    }

    private fun showStats() {
        val done = items.count { it.optBoolean("done") }
        val totalSeconds = items.sumOf { it.optInt("seconds") }
        android.app.AlertDialog.Builder(this).setTitle("Statistics")
            .setMessage("Targets: ${items.size}\nCompleted: $done\nRecorded question time: ${totalSeconds / 60} min")
            .setPositiveButton("OK", null).show()
    }

    override fun onPause() {
        super.onPause()
        if (running) { timer?.cancel(); running = false }
    }
}

private class AlertDialogBuilder(private val activity: Activity) {
    fun show(view: View, onSave: () -> Unit) {
        android.app.AlertDialog.Builder(activity).setTitle("Add target").setView(view)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> onSave() }.show()
    }
}
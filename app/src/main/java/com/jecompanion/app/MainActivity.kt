package com.jecompanion.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("jee_companion", MODE_PRIVATE) }
    private val records = mutableListOf<JSONObject>()
    private val handler = Handler(Looper.getMainLooper())
    private var activeQuestion: JSONObject? = null
    private var activeStartedAt = 0L
    private var timerText: TextView? = null
    private var pendingPdf: File? = null

    private val timerTick = object : Runnable {
        override fun run() {
            val q = activeQuestion ?: return
            val elapsed = ((System.currentTimeMillis() - activeStartedAt) / 1000L).toInt()
            timerText?.text = formatTime(q.optInt("sessionSeconds") + elapsed)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        loadRecords()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        renderHome()
        updateNotification()
        CompanionWidget.updateAll(this)
    }

    override fun onResume() {
        super.onResume()
        renderHome()
        updateNotification()
    }

    override fun onPause() {
        flushTimer()
        super.onPause()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun loadRecords() {
        records.clear()
        val arr = try { JSONArray(prefs.getString("records", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) records.add(arr.optJSONObject(i) ?: JSONObject())
    }

    private fun saveRecords() {
        val arr = JSONArray()
        records.forEach { arr.put(it) }
        prefs.edit().putString("records", arr.toString()).apply()
        CompanionWidget.updateAll(this)
        updateNotification()
    }

    private fun makeText(value: String, size: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 3, 0, 3)
        }
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun makeColumn(padding: Int = 18): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.BLACK)
        }
    }

    private fun tasksFor(date: String = today()): List<JSONObject> = records.filter { it.optString("date") == date }
    private fun questions(task: JSONObject): JSONArray = task.optJSONArray("questions") ?: JSONArray()

    private fun nextQuestion(task: JSONObject): Pair<Int, Int>? {
        val qs = questions(task)
        for (i in 0 until qs.length()) {
            val q = qs.optJSONObject(i) ?: continue
            if (q.optString("round1").isEmpty()) return Pair(i, 1)
        }
        for (i in 0 until qs.length()) {
            val q = qs.optJSONObject(i) ?: continue
            if (q.optString("round1") == "Skipped" && q.optString("round2").isEmpty()) return Pair(i, 2)
        }
        return null
    }

    private fun renderHome() {
        val root = makeColumn(18)
        val scroll = ScrollView(this)
        val body = makeColumn(0)
        body.addView(makeText("JEE Companion", 28f, Color.WHITE, true))
        body.addView(makeText(SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()), 13f, 0xFFAAAAAA.toInt()))
        val todays = tasksFor()
        val completed = todays.count { it.optBoolean("lectureDone") }
        val streak = streakSummary()
        body.addView(makeText("TODAY'S PROGRESS", 12f, 0xFF9E9E9E.toInt(), true))
        body.addView(makeText(completed.toString() + " / " + todays.size + " lectures completed", 21f, Color.WHITE, true))
        body.addView(makeText(formatTime(focusedSeconds(today())) + " active question-solving time", 14f, 0xFFBBBBBB.toInt()))
        body.addView(makeText("Current streak: " + streak.first + " days  •  Longest: " + streak.second + " days", 13f, 0xFFBBBBBB.toInt()))
        body.addView(makeText("TODAY'S TARGETS", 12f, 0xFF9E9E9E.toInt(), true))
        if (todays.isEmpty()) body.addView(makeText("No targets added for today yet.", 15f, 0xFFBBBBBB.toInt()))
        else todays.forEach { addTaskCard(body, it) }
        body.addView(makeButton("＋  Add today's target") { showAddTarget() })
        body.addView(makeButton("Statistics") { showStatistics() })
        body.addView(makeButton("Generate daily PDF") { showPdfPreview() })
        body.addView(makeText("Offline-first. Your study records remain on this phone.", 12f, 0xFF777777.toInt()))
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun addTaskCard(parent: LinearLayout, task: JSONObject) {
        val card = makeColumn(12)
        card.setBackgroundColor(0xFF171717.toInt())
        val qs = questions(task)
        var firstRound = 0
        var skipped = 0
        for (i in 0 until qs.length()) {
            val q = qs.optJSONObject(i) ?: continue
            if (q.optString("round1").isNotEmpty()) firstRound++
            if (q.optString("round1") == "Skipped" && q.optString("round2").isEmpty()) skipped++
        }
        card.addView(makeText(task.optString("subject") + "  •  " + task.optString("chapter"), 17f, Color.WHITE, true))
        card.addView(makeText(task.optString("lecture") + "  |  " + task.optString("topic"), 13f, 0xFFBBBBBB.toInt()))
        if (task.optString("subtopic").isNotBlank()) card.addView(makeText(task.optString("subtopic"), 12f, 0xFF999999.toInt()))
        val nums = (0 until qs.length()).joinToString(", ") { qs.optJSONObject(it)?.optString("number") ?: "" }
        card.addView(makeText("Questions: " + nums, 13f, 0xFFCCCCCC.toInt()))
        card.addView(makeText("First round: " + firstRound + "/" + qs.length() + "  •  Skipped queue: " + skipped, 12f, 0xFFAAAAAA.toInt()))
        card.addView(makeText(if (task.optBoolean("lectureDone")) "Lecture completed" else "Lecture not completed", 13f,
            if (task.optBoolean("lectureDone")) 0xFF80CBC4.toInt() else 0xFFFFCC80.toInt(), true))
        card.addView(makeButton("Solve next question") {
            val next = nextQuestion(task)
            if (next == null) {
                AlertDialog.Builder(this).setTitle("Round complete")
                    .setMessage("No first-round or skipped reattempt questions remain. Your saved attempt history is retained.")
                    .setPositiveButton("OK", null).show()
            } else openQuestion(task, next.first, next.second)
        })

        val retryIndices = mutableListOf<Int>()
        for (i in 0 until qs.length()) {
            val q = qs.optJSONObject(i) ?: continue
            if (q.optString("round1").isNotEmpty() && q.optString("round1") != "Skipped" && q.optString("round2").isEmpty()) retryIndices.add(i)
        }
        if (retryIndices.isNotEmpty()) {
            card.addView(makeButton("Choose a question for second attempt") {
                val labels = retryIndices.map { "Question " + (qs.optJSONObject(it)?.optString("number") ?: "") }
                AlertDialog.Builder(this).setTitle("Choose a question to reattempt")
                    .setItems(labels.toTypedArray()) { _, chosen -> openQuestion(task, retryIndices[chosen], 2) }
                    .setNegativeButton("Cancel", null).show()
            })
        }
        card.addView(makeButton(if (task.optBoolean("lectureDone")) "Lecture marked complete" else "Mark lecture completed") {
            task.put("lectureDone", true)
            saveRecords()
            renderHome()
        })
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = 12
        parent.addView(card, lp)
    }

    private fun showAddTarget() {
        val subjects = arrayOf("Physics", "Chemistry", "Mathematics")
        val subject = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, subjects)
        }
        val chapter = EditText(this).apply { hint = "Chapter"; setSingleLine() }
        val lecture = EditText(this).apply { hint = "Lecture label (e.g. Lecture 5)"; setSingleLine() }
        val topic = EditText(this).apply { hint = "Topic (optional)"; setSingleLine() }
        val subtopic = EditText(this).apply { hint = "Subtopic (optional)"; setSingleLine() }
        val questionInput = EditText(this).apply { hint = "Exact question numbers: 1,2,3,7,12"; setSingleLine() }
        val box = makeColumn(10)
        box.addView(subject); box.addView(chapter); box.addView(lecture)
        box.addView(topic); box.addView(subtopic); box.addView(questionInput)
        AlertDialog.Builder(this).setTitle("Add today's target").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val chapterName = chapter.text.toString().trim()
                val numbers = questionInput.text.toString().trim().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (chapterName.isEmpty() || numbers.isEmpty()) {
                    Toast.makeText(this, "Enter a chapter and exact question numbers.", Toast.LENGTH_LONG).show()
                } else {
                    val questionArray = JSONArray()
                    numbers.forEach { n ->
                        questionArray.put(JSONObject().put("number", n).put("round1", "").put("round2", "")
                            .put("sessionSeconds", 0).put("attempts", JSONArray()))
                    }
                    records.add(JSONObject().put("date", today())
                        .put("subject", subjects[subject.selectedItemPosition])
                        .put("chapter", chapterName).put("lecture", lecture.text.toString().trim())
                        .put("topic", topic.text.toString().trim()).put("subtopic", subtopic.text.toString().trim())
                        .put("lectureDone", false).put("questions", questionArray))
                    saveRecords()
                    renderHome()
                }
            }.show()
    }

    private fun openQuestion(task: JSONObject, qIndex: Int, round: Int) {
        val q = questions(task).optJSONObject(qIndex) ?: return
        activeQuestion = q
        val elapsed = makeText(formatTime(q.optInt("sessionSeconds")), 34f, Color.WHITE, true).apply { gravity = android.view.Gravity.CENTER }
        timerText = elapsed
        val content = makeColumn(16)
        content.addView(makeText(task.optString("subject") + "  •  " + task.optString("chapter"), 14f, 0xFFBBBBBB.toInt()))
        content.addView(makeText("Question " + q.optString("number"), 23f, Color.WHITE, true))
        content.addView(makeText("Attempt " + round, 13f, 0xFF9E9E9E.toInt()))
        content.addView(elapsed)
        content.addView(makeButton("Start / Resume timer") { startTimer(q, elapsed) })
        content.addView(makeButton("Pause timer") { flushTimer() })
        val dialog = AlertDialog.Builder(this).setTitle("Question session").setView(content)
            .setNegativeButton("Close") { _, _ -> flushTimer(); activeQuestion = null; timerText = null }.create()
        content.addView(makeButton("Not done — save time") {
            flushTimer(); activeQuestion = null; timerText = null; dialog.dismiss(); renderHome()
        })
        content.addView(makeButton("Skip this question") {
            flushTimer()
            val statusKey = if (round == 1) "round1" else "round2"
            q.put(statusKey, "Skipped")
            val attempts = q.optJSONArray("attempts") ?: JSONArray()
            attempts.put(JSONObject().put("round", round).put("result", "Skipped")
                .put("seconds", q.optInt("sessionSeconds")).put("date", today()))
            q.put("attempts", attempts).put("sessionSeconds", 0)
            activeQuestion = null; timerText = null
            saveRecords(); dialog.dismiss(); renderHome()
        })
        content.addView(makeButton("Finish attempt and mark result") {
            flushTimer()
            showResultPicker(task, qIndex, q, round)
            dialog.dismiss()
        })
        dialog.setOnDismissListener {
            flushTimer()
            if (activeQuestion === q) activeQuestion = null
            timerText = null
        }
        dialog.show()
    }

    private fun startTimer(question: JSONObject, view: TextView) {
        if (activeQuestion !== question) activeQuestion = question
        if (activeStartedAt != 0L) return
        activeStartedAt = System.currentTimeMillis()
        timerText = view
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
    }

    private fun flushTimer() {
        val q = activeQuestion
        if (q != null && activeStartedAt != 0L) {
            val elapsed = ((System.currentTimeMillis() - activeStartedAt) / 1000L).toInt().coerceAtLeast(0)
            q.put("sessionSeconds", q.optInt("sessionSeconds") + elapsed)
            activeStartedAt = 0L
            handler.removeCallbacks(timerTick)
            timerText?.text = formatTime(q.optInt("sessionSeconds"))
            saveRecords()
        } else handler.removeCallbacks(timerTick)
    }

    private fun showResultPicker(task: JSONObject, qIndex: Int, q: JSONObject, round: Int) {
        val options = arrayOf("Incorrect", "Correct", "Correct but Important")
        AlertDialog.Builder(this).setTitle("Check your solution").setItems(options) { _, which ->
            val result = options[which]
            q.put(if (round == 1) "round1" else "round2", result)
            val attempts = q.optJSONArray("attempts") ?: JSONArray()
            attempts.put(JSONObject().put("round", round).put("result", result)
                .put("seconds", q.optInt("sessionSeconds")).put("date", today()))
            q.put("attempts", attempts).put("sessionSeconds", 0)
            activeQuestion = null; timerText = null
            saveRecords(); renderHome()
            if (round == 1) {
                AlertDialog.Builder(this).setTitle("First attempt saved")
                    .setMessage("You can make a second attempt for this question now or choose it later from the target card.")
                    .setPositiveButton("Reattempt now") { _, _ -> openQuestion(task, qIndex, 2) }
                    .setNegativeButton("Later", null).show()
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun formatTime(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun attemptsFor(task: JSONObject): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val qs = questions(task)
        for (i in 0 until qs.length()) {
            val q = qs.optJSONObject(i) ?: continue
            val arr = q.optJSONArray("attempts") ?: JSONArray()
            for (j in 0 until arr.length()) result.add(arr.optJSONObject(j) ?: JSONObject())
        }
        return result
    }

    private fun focusedSeconds(date: String): Int {
        var total = 0
        tasksFor(date).forEach { task ->
            val qs = questions(task)
            for (i in 0 until qs.length()) {
                val q = qs.optJSONObject(i) ?: continue
                total += q.optInt("sessionSeconds")
                val attempts = q.optJSONArray("attempts") ?: JSONArray()
                for (j in 0 until attempts.length()) total += attempts.optJSONObject(j)?.optInt("seconds") ?: 0
            }
        }
        return total
    }

    private fun qualifiedDay(date: String): Boolean {
        val completed = tasksFor(date).filter { it.optBoolean("lectureDone") }.map { it.optString("subject") }.toSet()
        return completed.containsAll(listOf("Physics", "Chemistry", "Mathematics")) && focusedSeconds(date) >= 1800
    }

    private fun streakSummary(): Pair<Int, Int> {
        if (records.isEmpty()) return Pair(0, 0)
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val first = records.map { it.optString("date") }.filter { it.isNotBlank() }.minOrNull() ?: return Pair(0, 0)
        val calendar = Calendar.getInstance().apply { time = parser.parse(first) ?: Date() }
        val end = parser.parse(today()) ?: Date()
        var run = 0
        var longest = 0
        var current = 0
        while (!calendar.time.after(end)) {
            val day = parser.format(calendar.time)
            if (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
                if (qualifiedDay(day)) {
                    run++
                    longest = maxOf(longest, run)
                } else if (day != today()) run = 0
            }
            if (day == today()) current = run
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return Pair(current, longest)
    }

    private fun showStatistics() {
        val day = tasksFor()
        val attempts = day.flatMap { attemptsFor(it) }
        val correct = attempts.count { it.optString("result") == "Correct" || it.optString("result") == "Correct but Important" }
        val incorrect = attempts.count { it.optString("result") == "Incorrect" }
        val skipped = attempts.count { it.optString("result") == "Skipped" }
        val out = StringBuilder()
        out.append("TODAY\nLectures completed: " + day.count { it.optBoolean("lectureDone") } + " / " + day.size + "\n")
        out.append("Attempts: " + attempts.size + " | Correct: " + correct + " | Incorrect: " + incorrect + " | Skipped: " + skipped + "\n")
        out.append("Active time: " + formatTime(focusedSeconds(today())) + "\n\nSUBJECTS\n")
        listOf("Physics", "Chemistry", "Mathematics").forEach { subject ->
            val subset = day.filter { it.optString("subject") == subject }.flatMap { attemptsFor(it) }
            out.append(subject + ": " + subset.size + " attempts, " +
                subset.count { it.optString("result") == "Correct" || it.optString("result") == "Correct but Important" } +
                " correct, " + subset.count { it.optString("result") == "Incorrect" } + " incorrect\n")
        }
        val timedAttempts = attempts.filter { it.optString("result") != "Skipped" && it.optInt("seconds") > 0 }
        val averageSeconds = if (timedAttempts.isEmpty()) 0 else timedAttempts.sumOf { it.optInt("seconds") } / timedAttempts.size
        out.append("Average time per completed attempt: " + formatTime(averageSeconds) + "\n")
        var improved = 0
        var unchangedOrLower = 0
        records.forEach { task ->
            val qs = questions(task)
            for (i in 0 until qs.length()) {
                val q = qs.optJSONObject(i) ?: continue
                val first = q.optString("round1")
                val second = q.optString("round2")
                if (first.isNotEmpty() && first != "Skipped" && second.isNotEmpty() && second != "Skipped") {
                    val firstRight = first == "Correct" || first == "Correct but Important"
                    val secondRight = second == "Correct" || second == "Correct but Important"
                    if (!firstRight && secondRight) improved++ else unchangedOrLower++
                }
            }
        }
        out.append("Second-attempt improvements: " + improved + " | Other recorded reattempts: " + unchangedOrLower + "\n")
        out.append("\nCHAPTER ACCURACY — ALL SAVED DAYS\n")
        records.groupBy { it.optString("subject") + " — " + it.optString("chapter") }.forEach { (chapter, tasks) ->
            val tries = tasks.flatMap { attemptsFor(it) }.filter { it.optString("result") != "Skipped" }
            val right = tries.count { it.optString("result") == "Correct" || it.optString("result") == "Correct but Important" }
            val percent = if (tries.isEmpty()) 0 else right * 100 / tries.size
            out.append(chapter + ": " + percent + "% (" + right + "/" + tries.size + ")\n")
        }
        val streak = streakSummary()
        out.append("\nCurrent streak: " + streak.first + "\nLongest streak: " + streak.second)
        val view = makeText(out.toString(), 14f, Color.WHITE).apply { setPadding(16, 12, 16, 12) }
        AlertDialog.Builder(this).setTitle("Statistics").setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Close", null).show()
    }

    private fun createDailyReport(): String {
        val dayTasks = tasksFor()
        val report = StringBuilder()
        report.append("JEE COMPANION — DAILY STUDY REPORT\nDate: " + today() + "\n\n")
        report.append("Lectures completed: " + dayTasks.count { it.optBoolean("lectureDone") } + " / " + dayTasks.size + "\n")
        report.append("Active question-solving time: " + formatTime(focusedSeconds(today())) + "\n\n")
        listOf("Physics", "Chemistry", "Mathematics").forEach { subject ->
            val selected = dayTasks.filter { it.optString("subject") == subject }
            val attempts = selected.flatMap { attemptsFor(it) }
            report.append(subject + " — " + selected.size + " lecture target(s)\n")
            report.append("Attempts: " + attempts.size + " | Correct: " +
                attempts.count { it.optString("result") == "Correct" || it.optString("result") == "Correct but Important" } +
                " | Incorrect: " + attempts.count { it.optString("result") == "Incorrect" } +
                " | Skipped: " + attempts.count { it.optString("result") == "Skipped" } + "\n")
            selected.forEach { task ->
                report.append("\n  Chapter: " + task.optString("chapter") + "\n  Lecture: " + task.optString("lecture") +
                    "\n  Topic: " + task.optString("topic") + "\n  Subtopic: " + task.optString("subtopic") + "\n")
                val qs = questions(task)
                report.append("  Exact question numbers: " + (0 until qs.length()).joinToString(", ") { qs.optJSONObject(it)?.optString("number") ?: "" } + "\n")
                for (i in 0 until qs.length()) {
                    val q = qs.optJSONObject(i) ?: continue
                    val tries = q.optJSONArray("attempts") ?: JSONArray()
                    for (j in 0 until tries.length()) {
                        val a = tries.optJSONObject(j) ?: continue
                        report.append("    Q" + q.optString("number") + " • Round " + a.optInt("round") + " • " +
                            a.optString("result") + " • " + formatTime(a.optInt("seconds")) + "\n")
                    }
                    if (q.optInt("sessionSeconds") > 0) report.append("    Q" + q.optString("number") + " • In progress • " + formatTime(q.optInt("sessionSeconds")) + "\n")
                }
                report.append("  Lecture completed: " + if (task.optBoolean("lectureDone")) "Yes\n" else "No\n")
            }
            report.append("\n")
        }
        val streak = streakSummary()
        report.append("Current streak: " + streak.first + " days | Longest streak: " + streak.second + " days\n")
        return report.toString()
    }

    private fun showPdfPreview() {
        val report = createDailyReport()
        val view = makeText(report, 12f, Color.WHITE).apply { setPadding(16, 12, 16, 12) }
        AlertDialog.Builder(this).setTitle("Daily PDF preview").setView(ScrollView(this).apply { addView(view) })
            .setNegativeButton("Close", null)
            .setPositiveButton("Save PDF") { _, _ -> createPdfFile(report) }.show()
    }

    private fun createPdfFile(report: String) {
        val document = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 10f; isAntiAlias = true }
        val lines = mutableListOf<String>()
        report.lines().forEach { line ->
            if (line.length <= 92) lines.add(line) else line.chunked(92).forEach { lines.add(it) }
        }
        var lineIndex = 0
        var pageNumber = 1
        while (lineIndex < lines.size || pageNumber == 1) {
            val info = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
            val page = document.startPage(info)
            var y = 42f
            while (lineIndex < lines.size && y < 810f) {
                page.canvas.drawText(lines[lineIndex], 36f, y, paint)
                y += 16f
                lineIndex++
            }
            document.finishPage(page)
            pageNumber++
            if (lineIndex >= lines.size) break
        }
        val file = File(cacheDir, "jee-companion-" + today() + ".pdf")
        file.outputStream().use { document.writeTo(it) }
        document.close()
        pendingPdf = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        startActivityForResult(intent, 810)
    }

    @Deprecated("Deprecated by Android; retained for broad device compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 810 && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val file = pendingPdf ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(file).use { input -> input.copyTo(output) }
                }
                Toast.makeText(this, "Daily PDF saved.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not save PDF: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("jee_daily", "JEE Companion progress", NotificationManager.IMPORTANCE_LOW))
        }
    }

    @Suppress("DEPRECATION")
    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val day = tasksFor()
        val message = day.count { it.optBoolean("lectureDone") }.toString() + "/" + day.size +
            " lectures • " + formatTime(focusedSeconds(today())) + " focus today"
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "jee_daily")
            else android.app.Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("JEE Companion").setContentText(message)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
        manager.notify(61, notification)
    }
}

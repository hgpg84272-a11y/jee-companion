package com.jecompanion.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CompanionWidget : AppWidgetProvider() {
    companion object {
        const val ACTION_COMPLETE = "com.jecompanion.app.COMPLETE_NEXT"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, CompanionWidget::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val raw = context.getSharedPreferences("jee_companion", Context.MODE_PRIVATE)
                .getString("records", "[]") ?: "[]"
            val records = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val totals = linkedMapOf("Physics" to 0, "Chemistry" to 0, "Mathematics" to 0)
            val completed = linkedMapOf("Physics" to 0, "Chemistry" to 0, "Mathematics" to 0)
            var pending = false
            for (i in 0 until records.length()) {
                val task = records.optJSONObject(i) ?: continue
                if (task.optString("date") != today) continue
                val subject = task.optString("subject")
                if (totals.containsKey(subject)) {
                    totals[subject] = (totals[subject] ?: 0) + 1
                    if (task.optBoolean("lectureDone")) completed[subject] = (completed[subject] ?: 0) + 1
                }
                if (!task.optBoolean("lectureDone")) pending = true
            }
            val summary = "P " + (completed["Physics"] ?: 0) + "/" + (totals["Physics"] ?: 0) +
                "   C " + (completed["Chemistry"] ?: 0) + "/" + (totals["Chemistry"] ?: 0) +
                "   M " + (completed["Mathematics"] ?: 0) + "/" + (totals["Mathematics"] ?: 0)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.jee_widget)
                views.setTextViewText(R.id.widget_progress, summary)
                views.setTextViewText(R.id.widget_hint, if (pending) "Tap to complete the next lecture" else "All today's lectures are complete")
                val completeIntent = Intent(context, CompanionWidget::class.java).setAction(ACTION_COMPLETE)
                val completePending = PendingIntent.getBroadcast(
                    context, 200 + id, completeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val openIntent = PendingIntent.getActivity(
                    context, 300 + id, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_complete, completePending)
                views.setOnClickPendingIntent(R.id.widget_open, openPending)
                manager.updateAppWidget(id, views)
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_COMPLETE) return
        val prefs = context.getSharedPreferences("jee_companion", Context.MODE_PRIVATE)
        val array = try { JSONArray(prefs.getString("records", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var marked = false
        for (i in 0 until array.length()) {
            val task: JSONObject = array.optJSONObject(i) ?: continue
            if (task.optString("date") == today && !task.optBoolean("lectureDone")) {
                task.put("lectureDone", true)
                marked = true
                break
            }
        }
        if (marked) prefs.edit().putString("records", array.toString()).apply()
        updateAll(context)
    }
}
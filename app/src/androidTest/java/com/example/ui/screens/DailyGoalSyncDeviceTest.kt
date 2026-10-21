package com.example.ui.screens

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.core.goal.FocusTargetType
import com.example.data.database.AppDatabase
import com.example.data.entity.SessionLogEntity
import com.example.ui.viewmodel.PomodoroViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyGoalSyncDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun publishesOneOfThreeSessionGoalFromIsolatedRecords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Fixture writes require -PisolatedQa=true", context.packageName.endsWith(".qa"))
        val viewModel = ViewModelProvider(compose.activity)[PomodoroViewModel::class.java]
        compose.runOnIdle {
            viewModel.setDailyTargetType(FocusTargetType.SESSIONS)
            viewModel.setDailyTarget(3)
            viewModel.setDailyTargetEnabled(true)
        }
        val now = System.currentTimeMillis()
        runBlocking(Dispatchers.IO) {
            AppDatabase.getDatabase(context).sessionDao().clearAllSessions()
            AppDatabase.getDatabase(context).sessionDao().insertSessionIfAbsent(SessionLogEntity(
                sessionType = "FOCUS", startTimeMillis = now - 60_000, durationSeconds = 60,
                status = "COMPLETED", timestamp = now, recordId = "qa-goal-sync-one"
            ))
        }
        compose.waitUntil(timeoutMillis = 10_000) { viewModel.dailyFocusProgress.value.completedSessions == 1 }
        assertTrue(viewModel.dailyFocusProgress.value.settings.enabled)
        assertEquals(FocusTargetType.SESSIONS, viewModel.dailyFocusProgress.value.settings.type)
        assertEquals(3, viewModel.dailyFocusProgress.value.settings.targetSessions)
        assertEquals(1, viewModel.dailyFocusProgress.value.value)
    }
}

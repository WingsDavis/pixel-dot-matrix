package com.example.wear

import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearCommandRetryServiceTest {
    @Test fun retrySurvivesRebootAndDoesNotRequirePhoneNetworkOrActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        WearCommandRetryService.cancel(context)
        WearCommandRetryService.schedule(context)
        WearCommandRetryService.schedule(context)
        val jobs = scheduler.allPendingJobs.filter { it.id == WearCommandRetryService.JOB_ID }
        assertEquals(1, jobs.size)
        assertTrue(jobs.single().isPersisted)
        assertTrue(jobs.single().isPeriodic)
        assertEquals(15 * 60 * 1000L, jobs.single().intervalMillis)
        assertEquals(0, jobs.single().networkType)
        val info = context.packageManager.getServiceInfo(ComponentName(context, WearCommandRetryService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertTrue(info.exported)
        WearCommandRetryService.cancel(context)
        assertNull(scheduler.getPendingJob(WearCommandRetryService.JOB_ID))
    }
}

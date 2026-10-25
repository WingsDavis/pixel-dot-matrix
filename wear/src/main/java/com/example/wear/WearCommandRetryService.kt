package com.example.wear

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WearCommandRetryService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            runCatching {
                WearCommandOutbox(applicationContext, Wearable.getNodeClient(this@WearCommandRetryService),
                    Wearable.getMessageClient(this@WearCommandRetryService)).flush { isActive }
            }.onFailure { Log.e("WearCommandRetry", "Could not retry pending commands", it) }
            if (isActive) jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        internal const val JOB_ID = 2451
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
                ?: error("Background retry is unavailable")
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, WearCommandRetryService::class.java))
                .setPeriodic(15 * 60 * 1000L).setPersisted(true).build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Could not schedule background retry" }
        }
        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
    }
}

package com.example.myasr.context.kanakanji.infrastructure

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.example.myasr.context.kanakanji.application.UserHistorySyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * OS-managed periodic background sync service (JobScheduler) that keeps
 * user learning history and custom dictionary synchronized with Google Drive
 * even when the keyboard is not opened or closed recently.
 */
class HistorySyncJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    companion object {
        private const val TAG = "HistorySyncJobService"
        const val JOB_ID = 7301

        fun schedulePeriodic(context: Context, intervalMinutes: Long) {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val intervalMillis = intervalMinutes.coerceAtLeast(15L) * 60_000L

            val existing = scheduler.allPendingJobs.firstOrNull { it.id == JOB_ID }
            if (existing != null && existing.intervalMillis == intervalMillis) {
                Log.d(TAG, "Periodic sync job already scheduled (${intervalMinutes}m)")
                return
            }

            val component = ComponentName(appContext, HistorySyncJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, component)
                .setPeriodic(intervalMillis)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()

            try {
                val result = scheduler.schedule(jobInfo)
                Log.i(TAG, "Scheduled periodic Google Drive sync every ${intervalMinutes}m (result=$result)")
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to schedule periodic sync job due to missing permission", e)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Failed to schedule periodic sync job", e)
            }
        }

        fun cancel(context: Context) {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            scheduler.cancel(JOB_ID)
            Log.i(TAG, "Cancelled periodic Google Drive sync job")
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val syncManager = UserHistorySyncManager.getInstance(applicationContext)
        if (!syncManager.isAutoSyncEnabled() || !syncManager.hasValidSyncPermission()) {
            Log.w(TAG, "Skipping periodic sync job: autoSync=${syncManager.isAutoSyncEnabled()}, validPerm=${syncManager.hasValidSyncPermission()}")
            syncManager.ensurePeriodicSyncSchedule()
            jobFinished(params, false)
            return false
        }

        val uri = syncManager.getSyncUri()
        if (uri == null) {
            syncManager.ensurePeriodicSyncSchedule()
            jobFinished(params, false)
            return false
        }

        activeJob = serviceScope.launch {
            var needsReschedule = false
            try {
                val result = syncManager.performSync(uri, trigger = "periodic_job_scheduler")
                if (!result.success) {
                    Log.w(TAG, "Periodic sync failed: ${result.message}")
                    needsReschedule = syncManager.hasValidSyncPermission()
                } else {
                    Log.i(TAG, "Periodic sync completed: ${result.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Periodic sync exception: ${e.message}")
                needsReschedule = syncManager.hasValidSyncPermission()
            } finally {
                jobFinished(params, needsReschedule)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

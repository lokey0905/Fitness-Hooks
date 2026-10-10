package com.lokey0905.FitnessHooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HealthConnectSteps {
    enum class Availability {
        AVAILABLE, INSTALL_REQUIRED, UPDATE_REQUIRED, WORK_PROFILE_UNSUPPORTED, PROFILE_UNSUPPORTED, UNAVAILABLE
    }

    enum class Result {
        READY, PERMISSION_REQUIRED, SYNCED, CLEARED, HOUR_CHANGED, UNAVAILABLE, FAILED
    }

    fun interface Callback {
        fun onResult(result: Result)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun checkPermission(context: Context, callback: Callback) {
        runWithPermission(context, callback) { Result.READY }
    }

    fun sync(context: Context, count: Int, expectedHourStartSeconds: Long, callback: Callback) {
        val appContext = context.applicationContext
        runWithPermission(appContext, callback) { client ->
            if (count !in 0..HealthConnectStepPlan.MAX_COUNT) {
                return@runWithPermission Result.FAILED
            }

            // Finish an accepted write before another Activity instance can mutate the same record.
            withContext(NonCancellable) {
                var now = Instant.now()
                var zone = ZoneId.systemDefault()
                if (HealthConnectStepPlan.currentHourStartSeconds(now, zone) != expectedHourStartSeconds) {
                    return@withContext Result.HOUR_CHANGED
                }
                if (!now.isAfter(Instant.ofEpochSecond(expectedHourStartSeconds))) {
                    return@withContext Result.FAILED
                }

                if (count == 0) {
                    val interval = HealthConnectStepPlan.interval(now, zone, expectedHourStartSeconds)
                    client.deleteRecords(
                        StepsRecord::class,
                        TimeRangeFilter.between(interval.start, interval.end),
                    )
                    return@withContext if (persistTotal(appContext, count, expectedHourStartSeconds)) {
                        Result.CLEARED
                    } else Result.FAILED
                }

                val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                val revision = HealthConnectStepPlan.nextRevision(
                    preferences.getLong(REVISION_KEY, 0),
                    now.toEpochMilli(),
                )
                if (!preferences.edit().putLong(REVISION_KEY, revision).commit()) {
                    return@withContext Result.FAILED
                }

                now = Instant.now()
                zone = ZoneId.systemDefault()
                if (HealthConnectStepPlan.currentHourStartSeconds(now, zone) != expectedHourStartSeconds) {
                    return@withContext Result.HOUR_CHANGED
                }
                val interval = HealthConnectStepPlan.interval(now, zone, expectedHourStartSeconds)
                val record = StepsRecord(
                    startTime = interval.start,
                    startZoneOffset = interval.startOffset,
                    endTime = interval.end,
                    endZoneOffset = interval.endOffset,
                    count = count.toLong(),
                    metadata = Metadata.manualEntry(interval.clientRecordId, revision),
                )
                client.insertRecords(listOf(record))
                if (persistTotal(appContext, count, expectedHourStartSeconds)) Result.SYNCED else Result.FAILED
            }
        }
    }

    private fun runWithPermission(
        context: Context,
        callback: Callback,
        operation: suspend (HealthConnectClient) -> Result,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val result = try {
                operationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        if (availability(appContext) != Availability.AVAILABLE) {
                            return@withContext Result.UNAVAILABLE
                        }
                        val client = HealthConnectClient.getOrCreate(appContext)
                        if (!client.permissionController.getGrantedPermissions().containsAll(writePermissions())) {
                            return@withContext Result.PERMISSION_REQUIRED
                        }
                        coroutineContext.ensureActive()
                        operation(client)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                Result.PERMISSION_REQUIRED
            } catch (_: Exception) {
                Result.FAILED
            }
            coroutineContext.ensureActive()
            callback.onResult(result)
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun persistTotal(context: Context, count: Int, hour: Long): Boolean =
        context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(HookConfig.KEY_HEALTH_STEP_COUNT, count)
            .putLong(HookConfig.KEY_HEALTH_STEP_HOUR, hour)
            .commit()

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val PREFERENCES = "health_connect_steps"
        private const val REVISION_KEY = "last_record_revision"
        private val operationMutex = Mutex()

        @JvmStatic
        fun currentHourStartSeconds(): Long =
            HealthConnectStepPlan.currentHourStartSeconds(Instant.now(), ZoneId.systemDefault())

        @JvmStatic
        fun availability(context: Context): Availability {
            return try {
                val sdkStatus = HealthConnectClient.getSdkStatus(context)
                val userManager = context.getSystemService(UserManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && userManager?.isManagedProfile == true) {
                    return Availability.WORK_PROFILE_UNSUPPORTED
                }
                // OEM clone spaces are not necessarily managed work profiles. Let the SDK decide.
                when (sdkStatus) {
                    HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            Availability.UNAVAILABLE
                        } else {
                            try {
                                context.packageManager.getApplicationInfo(PROVIDER_PACKAGE, 0)
                                Availability.UPDATE_REQUIRED
                            } catch (_: PackageManager.NameNotFoundException) {
                                Availability.INSTALL_REQUIRED
                            }
                        }
                    }
                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && userManager?.isProfile == true
                    ) Availability.PROFILE_UNSUPPORTED else Availability.UNAVAILABLE
                }
            } catch (_: RuntimeException) {
                Availability.UNAVAILABLE
            }
        }

        @JvmStatic
        fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()

        @JvmStatic
        fun writePermissions(): Set<String> = setOf(HealthPermission.getWritePermission(StepsRecord::class))

        @JvmStatic
        fun settingsIntent(context: Context): Intent {
            val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                intent.setPackage(PROVIDER_PACKAGE)
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return intent
        }
    }
}

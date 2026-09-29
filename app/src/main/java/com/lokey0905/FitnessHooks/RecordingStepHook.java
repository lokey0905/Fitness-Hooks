package com.lokey0905.FitnessHooks;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class RecordingStepHook {
    private static final String CLIENT_CLASS =
            "com.nianticproject.ichigo.fitness.FitnessClientRecordingApi";
    private static final String COLLECTION_CLASS =
            "com.nianticproject.ichigo.proto.HourlyStepCountCollectionProto";
    private static final String COLLECTION_SOURCE_CLASS = COLLECTION_CLASS + "$FitnessDataSource";
    private static final String STEP_CLASS =
            "com.nianticproject.ichigo.proto.HourlyStepCountProto";
    private static final AtomicBoolean OVERRIDE_LOGGED = new AtomicBoolean(false);

    private RecordingStepHook() {
    }

    static int install(ClassLoader classLoader) {
        try {
            Class<?> targetClass = XposedHelpers.findClassIfExists(CLIENT_CLASS, classLoader);
            if (targetClass == null) {
                FitnessHooksEntry.log("Recording client class not found: " + CLIENT_CLASS);
                return 0;
            }

            Method queryMethod = targetClass.getDeclaredMethod(
                    "queryStepCounts",
                    int.class,
                    int.class
            );
            queryMethod.setAccessible(true);
            XposedBridge.hookMethod(queryMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    HookConfig.Snapshot config = HookConfig.readFromTarget(classLoader);
                    if (!config.stepHookEnabled || ((Integer) param.args[0]) <= 0) {
                        return;
                    }

                    try {
                        int requestedStartSec = (Integer) param.args[1];
                        Object replacement = buildStepResult(
                                classLoader,
                                requestedStartSec,
                                config.stepCount
                        );
                        param.setResult(replacement);
                        if (OVERRIDE_LOGGED.compareAndSet(false, true)) {
                            FitnessHooksEntry.log(
                                    "Recording step override active; steps=" + config.stepCount
                            );
                        }
                    } catch (Throwable throwable) {
                        FitnessHooksEntry.log("Recording step override failed: " + throwable);
                        XposedBridge.log(throwable);
                    }
                }
            });
            FitnessHooksEntry.log("Recording step hook installed: " + CLIENT_CLASS);
            return 1;
        } catch (Throwable throwable) {
            FitnessHooksEntry.log("Recording step hook failed to install: " + throwable);
            XposedBridge.log(throwable);
            return 0;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object buildStepResult(
            ClassLoader classLoader,
            int requestedStartSec,
            int stepCount
    ) throws Exception {
        Class<?> collectionClass = Class.forName(COLLECTION_CLASS, false, classLoader);
        Class<?> sourceClass = Class.forName(COLLECTION_SOURCE_CLASS, false, classLoader);
        Class<?> stepClass = Class.forName(STEP_CLASS, false, classLoader);

        Object collectionBuilder = collectionClass.getMethod("newBuilder").invoke(null);
        Object recordingSource = Enum.valueOf(
                (Class<? extends Enum>) sourceClass.asSubclass(Enum.class),
                "RECORDING_API_ON_MOBILE"
        );

        long epochSeconds = System.currentTimeMillis() / 1000L;
        int nowSec = (int) Math.min(Integer.MAX_VALUE, epochSeconds);
        int currentHourStartSec = nowSec - Math.floorMod(nowSec, 3600);
        int bucketStartSec = Math.min(nowSec, Math.max(requestedStartSec, currentHourStartSec));

        collectionBuilder.getClass()
                .getMethod("setFitnessDataSource", sourceClass)
                .invoke(collectionBuilder, recordingSource);
        collectionBuilder.getClass()
                .getMethod("setUpdateTimeSec", int.class)
                .invoke(collectionBuilder, nowSec);

        if (stepCount > 0) {
            Object stepBuilder = stepClass.getMethod("newBuilder").invoke(null);
            stepBuilder.getClass()
                    .getMethod("setStartTimeSec", int.class)
                    .invoke(stepBuilder, bucketStartSec);
            stepBuilder.getClass()
                    .getMethod("setStepCount", int.class)
                    .invoke(stepBuilder, stepCount);
            Object step = stepBuilder.getClass().getMethod("build").invoke(stepBuilder);
            collectionBuilder.getClass()
                    .getMethod("addStepCounts", stepClass)
                    .invoke(collectionBuilder, step);
        }

        return collectionBuilder.getClass().getMethod("build").invoke(collectionBuilder);
    }
}

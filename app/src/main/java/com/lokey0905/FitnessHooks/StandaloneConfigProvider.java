package com.lokey0905.FitnessHooks;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;

/** One read-only switch for HC and Fit startup, before they choose a provider route. */
public final class StandaloneConfigProvider extends ContentProvider {
    static final String AUTHORITY = ConfigTransport.MODULE_PACKAGE + ".standalone_config";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final String METHOD_GET_ROUTE = "get_standalone_route";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null || !isRouteClientCaller(context, Binder.getCallingUid())) {
            throw new SecurityException("Only Health Connect or Google Fit in the same Android user may read this switch");
        }
        if (!METHOD_GET_ROUTE.equals(method)) {
            throw new IllegalArgumentException("Unknown configuration method");
        }
        Bundle result = new Bundle();
        // Do not initialize/check HC here: a client may be waiting during Application.attach.
        result.putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED,
                StandaloneHealthConnect.isModuleSelected(context));
        return result;
    }

    private static boolean isRouteClientCaller(Context context, int uid) {
        if (!UserHandle.getUserHandleForUid(uid).equals(Process.myUserHandle())) {
            return false;
        }
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String packageName : packages) {
                if (ConfigTransport.HC_PACKAGE.equals(packageName)
                        || ConfigTransport.FIT_PACKAGE.equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Use the read-only configuration call");
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}

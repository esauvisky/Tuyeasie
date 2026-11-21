package com.thingclips.smart.security.jni;

import android.content.Context;
import androidx.annotation.Keep;
import com.thingclips.smart.security.callback.ICheckCallback;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Keep
public final class JNICLibrary {
    private static final List<ICheckCallback> checkCallbacks = new CopyOnWriteArrayList<>();
    private static volatile Context mContext;
    private static volatile boolean soLoaded;

    static {
        loadSo();
    }

    private JNICLibrary() {
    }

    private static synchronized void loadSo() {
        if (soLoaded) {
            return;
        }
        System.loadLibrary("c++_shared");
        System.loadLibrary("thing_security_algorithm");
        System.loadLibrary("thing_security");
        soLoaded = true;
    }

    public static void addCheckCallback(ICheckCallback callback) {
        checkCallbacks.add(callback);
    }

    public static void removeCheckCallback(ICheckCallback callback) {
        checkCallbacks.remove(callback);
    }

    @Keep
    public static void checkStatus(int status) {
        for (ICheckCallback callback : checkCallbacks) {
            callback.onCheck(ICheckCallback.CheckStatus.getCheckStatus(status));
        }
    }

    public static String computeDigest(String data, String key) {
        return SecureNativeApi.computeDigest(data, key);
    }

    public static String decryptResponseData(String data, String key) {
        return SecureNativeApi.decryptResponseData(data, key);
    }

    public static Object doCommandNative(Context context, int command, byte[] input, byte[] output, boolean flag) {
        synchronized (JNICLibrary.class) {
            if (context == null) {
                throw new IllegalArgumentException("context is null");
            }
            if (mContext == null) {
                mContext = context.getApplicationContext();
            }
        }
        return SecureNativeApi.doCommandNative(context, command, input, output, flag);
    }

    public static byte[] encryptPostData(String key, byte[] data) {
        return SecureNativeApi.encryptPostData(key, data);
    }

    public static String genKey(String first, String second, String third) {
        return SecureNativeApi.genKey(first, second, third);
    }

    public static String getChKey(Context context, byte[] data) {
        return SecureNativeApi.getChKey(context, data);
    }

    public static String getConfig(Context context, String key, String defaultValue) {
        return SecureNativeApi.getConfig(context, key, defaultValue);
    }

    public static Context getContext() {
        return mContext;
    }

    public static byte[] getEncryptoKey(String data, String key) {
        return SecureNativeApi.getEncryptoKey(data, key);
    }

    public static String testSign(Context context) {
        return SecureNativeApi.testSign(context);
    }
}

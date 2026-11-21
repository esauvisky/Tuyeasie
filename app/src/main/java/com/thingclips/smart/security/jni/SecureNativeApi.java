package com.thingclips.smart.security.jni;

import android.content.Context;
import androidx.annotation.Keep;

@Keep
public final class SecureNativeApi {
    private SecureNativeApi() {
    }

    public static native String computeDigest(String data, String key);

    public static native String decryptResponseData(String data, String key);

    protected static native Object doCommandNative(Context context, int command, byte[] input, byte[] output, boolean flag);

    public static native byte[] encryptPostData(String key, byte[] data);

    public static native String genKey(String first, String second, String third);

    public static native String getChKey(Context context, byte[] data);

    public static native String getConfig(Context context, String key, String defaultValue);

    public static native byte[] getEncryptoKey(String data, String key);

    public static native String testSign(Context context);
}

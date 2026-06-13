package com.neroflix.tv.app.util;

import android.util.Base64;
import android.util.Log;
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class ClearKeyUtil {
    private static final String TAG = "ClearKeyUtil";

    public static LocalMediaDrmCallback buildCallback(String hexKid, String hexKey) {
        try {
            String kid64 = Base64.encodeToString(hexToBytes(hexKid),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            String key64 = Base64.encodeToString(hexToBytes(hexKey),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

            JSONObject keyObj = new JSONObject();
            keyObj.put("kty", "oct");
            keyObj.put("k",   key64);
            keyObj.put("kid", kid64);

            JSONArray keys = new JSONArray();
            keys.put(keyObj);

            JSONObject license = new JSONObject();
            license.put("keys", keys);
            license.put("type", "temporary");

            byte[] licenseBytes = license.toString().getBytes(StandardCharsets.UTF_8);
            Log.d(TAG, "Built ClearKey license for kid=" + hexKid);
            return new LocalMediaDrmCallback(licenseBytes);
        } catch (Exception e) {
            Log.e(TAG, "Failed to build ClearKey license", e);
            return null;
        }
    }

    private static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s", "");
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                            +  Character.digit(hex.charAt(i * 2 + 1), 16));
        return data;
    }
}

package com.example.minseo3.nas;

import org.json.JSONException;
import org.json.JSONObject;

public final class RemotePosition {
    public final String fileName;
    public final long fileSize;
    public final int charOffset;
    public final int totalChars;
    public final String deviceId;
    public final long lastUpdatedEpoch;

    public RemotePosition(String fileName, long fileSize,
                          int charOffset, int totalChars,
                          String deviceId, long lastUpdatedEpoch) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.charOffset = charOffset;
        this.totalChars = totalChars;
        this.deviceId = deviceId;
        this.lastUpdatedEpoch = lastUpdatedEpoch;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("fileName", fileName);
        o.put("fileSize", fileSize);
        o.put("charOffset", charOffset);
        o.put("totalChars", totalChars);
        o.put("deviceId", deviceId);
        o.put("lastUpdatedEpoch", lastUpdatedEpoch);
        return o;
    }

    public static RemotePosition fromJson(JSONObject o) throws JSONException {
        return new RemotePosition(
                o.getString("fileName"),
                o.getLong("fileSize"),
                o.getInt("charOffset"),
                o.getInt("totalChars"),
                o.getString("deviceId"),
                o.getLong("lastUpdatedEpoch"));
    }
}

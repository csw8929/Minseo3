package com.example.minseo3;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Immutable bookmark record. Locally grouped by fileHash in bookmarks.json;
 * on NAS each bm_{fileHash}.json carries a flat list of these.
 *
 * id is deterministic (sha1(fileHash + ':' + charOffset) first 16 hex chars) so
 * two devices creating a bookmark at the same page-start offset collide into a
 * single record during union — no duplicates.
 */
public final class Bookmark {

    public static final int PREVIEW_MAX_LEN = 40;

    public final String id;
    public final String deviceId;
    public final int charOffset;
    public final String preview;
    public final long createdAt;
    /** null when alive; epoch millis when soft-deleted (tombstone). */
    public final Long deletedAt;

    public Bookmark(String id, String deviceId, int charOffset, String preview,
                    long createdAt, Long deletedAt) {
        this.id = id;
        this.deviceId = deviceId;
        this.charOffset = charOffset;
        this.preview = preview;
        this.createdAt = createdAt;
        this.deletedAt = deletedAt;
    }

    public boolean isAlive() { return deletedAt == null; }

    /** Last-meaningfully-changed timestamp for conflict resolution. */
    public long effectiveTimestamp() {
        return deletedAt != null ? Math.max(createdAt, deletedAt) : createdAt;
    }

    public Bookmark withDeletedAt(long when) {
        return new Bookmark(id, deviceId, charOffset, preview, createdAt, when);
    }

    public Bookmark revived(String newDeviceId, long now) {
        return new Bookmark(id, newDeviceId, charOffset, preview, now, null);
    }

    public static Bookmark createAtPage(String fileHash, int charOffset, String text,
                                        String deviceId, long now) {
        String id = deterministicId(fileHash, charOffset);
        String preview = extractPreview(text, charOffset);
        return new Bookmark(id, deviceId, charOffset, preview, now, null);
    }

    static String deterministicId(String fileHash, int charOffset) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest((fileHash + ":" + charOffset).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            // SHA-1 and UTF-8 are guaranteed available.
            throw new RuntimeException(e);
        }
    }

    /**
     * Pull ~40 chars starting at charOffset, collapse \n/\t to spaces, append …
     * only if we actually truncated before reaching end-of-text.
     */
    static String extractPreview(String text, int charOffset) {
        if (text == null || charOffset < 0 || charOffset >= text.length()) return "";
        int end = Math.min(charOffset + PREVIEW_MAX_LEN, text.length());
        String slice = text.substring(charOffset, end)
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replace('\r', ' ');
        if (end < text.length()) slice = slice + "…";
        return slice.trim();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("deviceId", deviceId);
        o.put("charOffset", charOffset);
        o.put("preview", preview);
        o.put("createdAt", createdAt);
        if (deletedAt != null) o.put("deletedAt", deletedAt.longValue());
        return o;
    }

    public static Bookmark fromJson(JSONObject o) throws JSONException {
        Long deletedAt = o.has("deletedAt") && !o.isNull("deletedAt")
                ? Long.valueOf(o.getLong("deletedAt"))
                : null;
        return new Bookmark(
                o.getString("id"),
                o.optString("deviceId", ""),
                o.getInt("charOffset"),
                o.optString("preview", ""),
                o.getLong("createdAt"),
                deletedAt);
    }
}

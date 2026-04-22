package com.example.minseo3.nas;

import static com.example.minseo3.nas.NasSyncConstants.SAME_SESSION_OFFSET_DIFF;
import static com.example.minseo3.nas.NasSyncConstants.SAME_SESSION_WINDOW_MS;

public final class ConflictResolver {
    private ConflictResolver() {}

    /**
     * local 위치와 NAS 위치를 비교해 어떤 offset으로 갈지 결정.
     *
     * @param localOffset        현재 앱이 쥐고 있는 로컬 charOffset (없으면 의미 없음, 호출부가 결정)
     * @param localLastReadEpoch 로컬이 마지막으로 저장된 시각 (ms), 없으면 0
     * @param localDeviceId      로컬 deviceId (비교 전용, null 가능)
     * @param nas                NAS에서 읽은 최신 위치 (null이면 NAS에 기록 없음)
     */
    public static ConflictOutcome resolve(int localOffset,
                                          long localLastReadEpoch,
                                          String localDeviceId,
                                          RemotePosition nas) {
        if (nas == null) return ConflictOutcome.keep(localOffset);
        if (localLastReadEpoch == 0L) return ConflictOutcome.jump(nas.charOffset);

        long timeDiff = Math.abs(nas.lastUpdatedEpoch - localLastReadEpoch);
        int offsetDiff = Math.abs(nas.charOffset - localOffset);
        boolean sameDevice = localDeviceId != null && localDeviceId.equals(nas.deviceId);

        if (timeDiff < SAME_SESSION_WINDOW_MS && offsetDiff < SAME_SESSION_OFFSET_DIFF) {
            return ConflictOutcome.keep(localOffset);
        }

        if (nas.lastUpdatedEpoch > localLastReadEpoch) {
            if (!sameDevice && offsetDiff >= SAME_SESSION_OFFSET_DIFF
                    && timeDiff < SAME_SESSION_WINDOW_MS) {
                return ConflictOutcome.ask(localOffset);
            }
            return ConflictOutcome.jump(nas.charOffset);
        }

        return ConflictOutcome.keep(localOffset);
    }
}

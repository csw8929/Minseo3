package com.example.minseo3.nas;

public final class ConflictOutcome {
    public final int resolvedOffset;
    public final boolean needsDialog;

    private ConflictOutcome(int resolvedOffset, boolean needsDialog) {
        this.resolvedOffset = resolvedOffset;
        this.needsDialog = needsDialog;
    }

    public static ConflictOutcome keep(int offset)  { return new ConflictOutcome(offset, false); }
    public static ConflictOutcome jump(int offset)  { return new ConflictOutcome(offset, false); }
    public static ConflictOutcome ask(int localOffset) { return new ConflictOutcome(localOffset, true); }
}

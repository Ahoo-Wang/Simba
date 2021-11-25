package me.ahoo.simba.core;

/**
 * @author ahoo wang
 */
public class MutexState {
    public static final MutexState NONE = new MutexState(MutexOwner.NONE, MutexOwner.NONE);
    /**
     * 旧的持有者
     */
    private final MutexOwner before;
    /**
     * 新的持有者
     */
    private final MutexOwner after;

    public MutexState(MutexOwner before, MutexOwner after) {
        this.before = before;
        this.after = after;
    }

    public MutexOwner getBefore() {
        return before;
    }

    public MutexOwner getAfter() {
        return after;
    }

    public boolean isChanged() {
        return !before.isOwner(after.getOwnerId());
    }

    public boolean isAcquired(String contenderId) {
        return isChanged() && isOwner(contenderId);
    }

    public boolean isReleased(String contenderId) {
        return isChanged() && before.isOwner(contenderId);
    }

    public boolean isOwner(String contenderId) {
        return after.isOwner(contenderId);
    }

    public boolean isInTtl(String contenderId) {
        return isOwner(contenderId) && after.isInTtl(contenderId);
    }
}

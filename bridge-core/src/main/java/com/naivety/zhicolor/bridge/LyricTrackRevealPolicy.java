package com.naivety.zhicolor.bridge;

/** Commits entrance motion once per observed track, independently of lyric refreshes. */
final class LyricTrackRevealPolicy {
    private String observedIdentity = "";
    private long observedGeneration;
    private boolean committed;
    private boolean committedWithIdentity;
    private long committedGeneration;
    private String committedModelKey = "";

    synchronized void observe(String identity) {
        if (!hasIdentity(identity)) return;
        if (!observedIdentity.isEmpty()
                && !TrackIdentity.matchesHintKey(observedIdentity, identity)
                && !TrackIdentity.matchesHintKey(identity, observedIdentity)) {
            observedGeneration++;
        }
        observedIdentity = identity;
    }

    synchronized boolean consumeCommittedTrack(String modelKey) {
        String key = modelKey == null ? "" : modelKey;
        boolean hasObservedIdentity = !observedIdentity.isEmpty();
        if (!hasObservedIdentity && key.isEmpty()) return false;
        boolean reveal = !committed
                || (hasObservedIdentity
                ? (committedWithIdentity ? committedGeneration != observedGeneration
                : !committedModelKey.equals(key))
                : !committedModelKey.equals(key));
        committed = true;
        committedWithIdentity = hasObservedIdentity;
        committedGeneration = observedGeneration;
        committedModelKey = key;
        return reveal;
    }

    private static boolean hasIdentity(String identity) {
        return identity != null && !identity.isEmpty() && !"|".equals(identity);
    }
}

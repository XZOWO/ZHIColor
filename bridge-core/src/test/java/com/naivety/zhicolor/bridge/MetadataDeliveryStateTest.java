package com.naivety.zhicolor.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class MetadataDeliveryStateTest {
    @Test public void firstLyricsNeedEventWithoutPreviousLoad() {
        assertTrue(new MetadataDeliveryState().needsDelivery("song-A"));
    }
    @Test public void absentLyricsDoNotInventAnEvent() {
        MetadataDeliveryState state = new MetadataDeliveryState();
        state.listenerAdded();
        assertFalse(state.needsDelivery(null));
    }
    @Test public void identicalLyricsAreNotRepeated() {
        MetadataDeliveryState state = new MetadataDeliveryState();
        state.delivered("A");
        assertFalse(state.needsDelivery("A"));
    }
    @Test public void changedSongOrTextIsDelivered() {
        MetadataDeliveryState state = new MetadataDeliveryState();
        state.delivered("A");
        assertTrue(state.needsDelivery("B"));
    }
    @Test public void disableOrDisconnectRestoresNativeOnce() {
        MetadataDeliveryState state = new MetadataDeliveryState();
        state.delivered("A");
        assertTrue(state.needsDelivery(null));
        state.delivered(null);
        assertFalse(state.needsDelivery(null));
    }
    @Test public void reenableRepublishesCachedLyrics() {
        MetadataDeliveryState state = new MetadataDeliveryState();
        state.delivered("A"); state.delivered(null);
        assertTrue(state.needsDelivery("A"));
    }
    @Test public void newlyRegisteredListenerReceivesExistingSong() {
        MetadataDeliveryState state = new MetadataDeliveryState();
        state.delivered("A"); state.listenerAdded();
        assertTrue(state.needsDelivery("A"));
    }
    @Test public void failedDispatchRemainsRetryable() {
        MetadataDeliveryState state = new MetadataDeliveryState();
        assertTrue(state.needsDelivery("A"));
        assertTrue(state.needsDelivery("A"));
    }
    @Test public void controllersHaveIndependentDeliveryState() {
        MetadataDeliveryState cloud = new MetadataDeliveryState(), aod = new MetadataDeliveryState();
        cloud.delivered("A");
        assertFalse(cloud.needsDelivery("A"));
        assertTrue(aod.needsDelivery("A"));
    }
}

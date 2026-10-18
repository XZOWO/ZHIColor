package com.naivety.zhicolor.bridge;

import java.util.Objects;

/** Per-controller publication state. A source update is not a MediaSession event by itself. */
final class MetadataDeliveryState {
    private String deliveredPayload;
    private boolean newListener;

    synchronized void listenerAdded() { newListener = true; }

    synchronized boolean needsDelivery(String payload) {
        return (payload != null || deliveredPayload != null)
                && (newListener || !Objects.equals(deliveredPayload, payload));
    }

    synchronized void delivered(String payload) {
        deliveredPayload = payload;
        newListener = false;
    }
}

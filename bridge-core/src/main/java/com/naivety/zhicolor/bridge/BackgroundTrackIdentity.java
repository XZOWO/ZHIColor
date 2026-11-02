package com.naivety.zhicolor.bridge;

/** Song identity for backdrop/audio ownership, never the current lyric line. */
final class BackgroundTrackIdentity {
    private BackgroundTrackIdentity() { }

    static String key(String player, String mediaId, String mediaUri, String title,
                      String artist, String album) {
        if (!text(mediaId).isEmpty()) return parts("id", mediaId);
        if (!text(mediaUri).isEmpty()) return parts("uri", mediaUri);
        String credit = text(artist);
        if (text(title).isEmpty() && credit.isEmpty() && text(album).isEmpty()) return "";
        return parts("metadata", title, artist, album);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String parts(String... values) {
        StringBuilder key = new StringBuilder();
        for (String value : values) {
            String part = text(value);
            key.append(part.length()).append(':').append(part).append(';');
        }
        return key.toString();
    }
}

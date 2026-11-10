package com.heartsong;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Imported MP3 library: one flat folder, one selected track at a time. */
public final class SongLibrary {
    public static final Path MUSIC = SongConfig.DIRECTORY.resolve("music");

    private SongLibrary() {}

    public static void ensureDirectory() throws IOException {
        Files.createDirectories(MUSIC);
    }

    /** Sorted .mp3 file names currently in the library. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        try {
            ensureDirectory();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(MUSIC, "*.mp3")) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p)) names.add(p.getFileName().toString());
                }
            }
        } catch (Exception e) {
            HeartSongClient.LOGGER.warn("Listing music folder", e);
        }
        names.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
        return names;
    }

    public static Path pathOf(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        Path p = MUSIC.resolve(fileName).normalize();
        // Never allow a settings entry to escape the music folder.
        return p.startsWith(MUSIC) ? p : null;
    }

    public static boolean exists(String fileName) {
        Path p = pathOf(fileName);
        return p != null && Files.isRegularFile(p);
    }

    /** Copies {@code source} into the library under a safe, unique file name. */
    public static String importTrack(Path source) throws IOException {
        ensureDirectory();
        String base = sanitize(source.getFileName().toString());
        String name = uniqueName(base);
        Path target = MUSIC.resolve(name);
        Path temp = Files.createTempFile(MUSIC, "import-", ".mp3");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(temp) == 0) throw new IOException("文件为空");
            com.heartsong.audio.Mp3Player.verify(temp);
            SongConfig.replace(temp, target);
            return name;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String sanitize(String fileName) {
        String name = fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (name.toLowerCase(Locale.ROOT).endsWith(".mp3")) name = name.substring(0, name.length() - 4);
        if (name.isBlank()) name = "track";
        if (name.length() > 80) name = name.substring(0, 80);
        return name + ".mp3";
    }

    static String uniqueName(String fileName) {
        String stem = fileName.substring(0, fileName.length() - 4);
        String candidate = fileName;
        int n = 2;
        while (Files.exists(MUSIC.resolve(candidate))) candidate = stem + " (" + (n++) + ").mp3";
        return candidate;
    }

    /** Moves a 1.0.x single track into the library and returns its new name ("" when none). */
    public static String migrateLegacy(String legacyDisplayName) {
        if (!Files.isRegularFile(SongConfig.LEGACY_SONG)) return "";
        try {
            ensureDirectory();
            String base = sanitize(legacyDisplayName == null || legacyDisplayName.isBlank()
                    ? "selected.mp3" : legacyDisplayName);
            String name = uniqueName(base);
            SongConfig.replace(SongConfig.LEGACY_SONG, MUSIC.resolve(name));
            return name;
        } catch (Exception e) {
            HeartSongClient.LOGGER.warn("Migrating legacy track", e);
            return "";
        }
    }
}

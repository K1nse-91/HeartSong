package com.heartsong;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class SongConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("heartsong");
    public static final Path FILE = DIRECTORY.resolve("settings.json");
    public static final Path LEGACY_SONG = DIRECTORY.resolve("selected.mp3");
    public boolean enabled = true;
    public float volume = 0.75f;
    /** Play starts below this percentage of max health. */
    public int startPercent = 20;
    /** Play stops above this percentage of max health. */
    public int stopPercent = 40;
    /** File name inside the music folder; the only committed selection. */
    public String selected = "";
    /** Legacy display name kept for migration of 1.0.x installs. */
    public String songName = "";

    public static SongConfig load() {
        try {
            if (Files.isRegularFile(FILE)) {
                SongConfig c = GSON.fromJson(Files.readString(FILE), SongConfig.class);
                if (c != null) {
                    c.volume = Float.isFinite(c.volume) ? Math.clamp(c.volume, 0f, 1f) : 0.75f;
                    c.startPercent = Math.clamp(c.startPercent, 1, 98);
                    c.stopPercent = Math.clamp(c.stopPercent, c.startPercent + 1, 99);
                    if (c.selected == null) c.selected = "";
                    if (c.songName == null) c.songName = "";
                    return c;
                }
            }
        } catch (Exception e) { HeartSongClient.LOGGER.warn("Reading HeartSong settings", e); }
        return new SongConfig();
    }

    public void save() throws IOException {
        Path temp = writeTemp();
        try { replace(temp, FILE); }
        finally { Files.deleteIfExists(temp); }
    }

    /** Writes a complete settings file without publishing it yet. */
    public Path writeTemp() throws IOException {
        Files.createDirectories(DIRECTORY);
        Path temp = Files.createTempFile(DIRECTORY, "settings-", ".tmp");
        try { Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8); }
        catch (IOException e) { Files.deleteIfExists(temp); throw e; }
        return temp;
    }

    public static void replace(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); }
    }
}

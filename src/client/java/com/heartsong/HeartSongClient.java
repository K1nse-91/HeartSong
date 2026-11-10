package com.heartsong;

import com.heartsong.audio.Mp3Player;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class HeartSongClient implements ClientModInitializer {
    public static final String MOD_ID = "heartsong";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static HeartSongClient INSTANCE;
    public final Mp3Player player = new Mp3Player();
    public final HealthGate gate = new HealthGate();
    public SongConfig config;
    public volatile String error = "";
    public boolean importing;
    private boolean playbackRequested;
    private boolean preview;
    private long previewDeadline;
    private KeyMapping openKey;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HeartSong-Import"); t.setDaemon(true); return t;
    });

    @Override public void onInitializeClient() {
        INSTANCE = this;
        config = SongConfig.load();
        gate.setThresholds(config.startPercent, config.stopPercent);
        migrateIfNeeded();
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.heartsong.open",
            GLFW.GLFW_KEY_H,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "settings"))));
        player.setListener(new Mp3Player.Listener() {
            public void onTrackFinished() { }
            public void onError(String message) { }
            @Override public void onTrackFinished(long generation) {
                Minecraft.getInstance().execute(() -> {
                    if (generation == player.getGeneration() && preview) stopPreview();
                });
            }
            @Override public void onError(long generation, String message) {
                LOGGER.warn("HeartSong audio: {}", message);
                Minecraft.getInstance().execute(() -> {
                    if (generation != player.getGeneration()) return;
                    error = message;
                    preview = false;
                    player.stop();
                });
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { player.stop(); io.shutdownNow(); });
        LOGGER.info("HeartSong 1.3.0 for Minecraft 26.2 loaded");
    }

    private void migrateIfNeeded() {
        if (!config.selected.isBlank()) return;
        String migrated = SongLibrary.migrateLegacy(config.songName);
        if (!migrated.isBlank()) {
            config.selected = migrated;
            try { config.save(); }
            catch (Exception e) { LOGGER.warn("Saving migrated selection", e); }
        }
    }

    private void tick(Minecraft client) {
        while (openKey.consumeClick()) {
            if (currentScreen(client) == null) openScreen(client, new MusicScreen(this));
        }
        boolean inWorld = client.player != null && client.level != null;
        boolean alive = inWorld && client.player.isAlive();
        gate.update(alive ? client.player.getHealth() : 0,
                    alive ? client.player.getMaxHealth() : 0, alive);
        if (preview) {
            if (alive && gate.active() && config.enabled) {
                stopPreview();
            } else if (System.nanoTime() >= previewDeadline || (inWorld && !alive)) {
                stopPreview();
            } else {
                return;
            }
        }
        if (!alive) {
            if (playbackRequested) { player.stop(); playbackRequested = false; }
            return;
        }
        boolean wanted = gate.active() && config.enabled && hasSong() && !importing;
        if (wanted && !playbackRequested) {
            playbackRequested = true; error = "";
            player.play(selectedPath(), true, config.volume);
        } else if (!wanted && playbackRequested) {
            player.stop(); playbackRequested = false;
        }
    }

    /** 26.1 exposes Gui.screen as a field, 26.2+ exposes screen(); read either shape. */
    public static Screen currentScreen(Minecraft client) {
        if (client == null || client.gui == null) return null;
        try {
            return (Screen) client.gui.getClass().getField("screen").get(client.gui);
        } catch (Throwable ignored) {
        }
        try {
            return (Screen) client.gui.getClass().getMethod("screen").invoke(client.gui);
        } catch (Throwable ignored) {
        }
        try {
            return (Screen) Minecraft.class.getField("screen").get(client);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 26.2 moved setScreen to Gui; 26.1 keeps it on Minecraft. */
    public static void openScreen(Minecraft client, Screen screen) {
        try {
            client.gui.getClass().getMethod("setScreen", Screen.class).invoke(client.gui, screen);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Minecraft.class.getMethod("setScreen", Screen.class).invoke(client, screen);
        } catch (Throwable ignored) {
            LOGGER.warn("Unable to open screen {}", screen.getClass().getSimpleName());
        }
    }

    public Path selectedPath() { return SongLibrary.pathOf(config.selected); }

    /** The selected file is the source of truth. */
    public boolean hasSong() { return SongLibrary.exists(config.selected); }

    public String songName() {
        return config.selected == null || config.selected.isBlank() ? "未选择" : config.selected;
    }

    public List<String> tracks() { return SongLibrary.list(); }

    /** Toggles the selection; restarts the running health playback with the new track. */
    public void selectTrack(String fileName) {
        if (fileName == null || fileName.equals(config.selected) || !SongLibrary.exists(fileName)) return;
        config.selected = fileName;
        error = "";
        save();
        if (preview) stopPreview();
        if (playbackRequested) player.play(selectedPath(), true, config.volume);
    }

    public String keyName() {
        return openKey == null ? "?" : openKey.getTranslatedKeyMessage().getString();
    }

    /** Live threshold edit; the slider persists on release. */
    public void setStartPercent(int percent) {
        config.startPercent = Math.clamp(percent, 1, Math.max(1, config.stopPercent - 1));
        gate.setThresholds(config.startPercent, config.stopPercent);
    }

    public void setStopPercent(int percent) {
        config.stopPercent = Math.clamp(percent, Math.min(99, config.startPercent + 1), 99);
        gate.setThresholds(config.startPercent, config.stopPercent);
    }

    /** Removes the selected track from the library and clears the selection. */
    public void deleteSelected() {
        Path file = selectedPath();
        if (file == null) return;
        playbackRequested = false;
        preview = false;
        player.stop();
        try {
            Files.deleteIfExists(file);
            config.selected = "";
            error = "";
            save();
        } catch (Exception e) {
            error = "删除失败：" + e.getMessage();
            LOGGER.warn("Deleting track", e);
        }
    }

    public boolean isPreview() { return preview; }

    public void preview() {
        if (preview) { stopPreview(); return; }
        if (hasSong() && !importing && !playbackRequested) {
            error = ""; preview = true; previewDeadline = System.nanoTime() + 10_000_000_000L;
            player.play(selectedPath(), false, config.volume);
        }
    }
    public void stopPreview() {
        if (preview) { preview = false; player.stop(); }
    }
    public void save() {
        try { config.save(); }
        catch (Exception e) { error = "保存失败：" + e.getMessage(); LOGGER.warn("Saving settings", e); }
    }

    public void importSong(Path source, Consumer<String> completed) {
        if (importing) return;
        if (source == null || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            completed.accept("请选择一首 .mp3 文件"); return;
        }
        importing = true; preview = false; playbackRequested = false;
        error = "";
        io.execute(() -> {
            try {
                if (!Files.isRegularFile(source)) throw new java.io.IOException("文件路径无效");
                if (Files.size(source) > 512L * 1024 * 1024) throw new java.io.IOException("文件超过 512 MB");
                if (player.isPlaying() && !player.stopAndAwait(5000)) {
                    throw new java.io.IOException("音频设备仍在释放，请重试导入");
                }
                String name = SongLibrary.importTrack(source);
                Minecraft.getInstance().execute(() -> {
                    config.selected = name;
                    error = "";
                    importing = false;
                    try { config.save(); completed.accept(""); }
                    catch (Exception e) {
                        error = "音乐已导入，设置保存失败：" + e.getMessage();
                        LOGGER.warn("Saving imported selection", e); completed.accept(error);
                    }
                });
            } catch (Exception e) {
                String message = "导入失败：" + e.getMessage();
                LOGGER.warn("Importing MP3", e);
                Minecraft.getInstance().execute(() -> { importing = false; completed.accept(message); });
            }
        });
    }
}

package com.heartsong;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Library on the left, controls on the right; browse imports immediately. */
public final class MusicScreen extends Screen {
    private final HeartSongClient mod;
    private Button browse, preview, auto, delete, changeKey, done;
    private VolumeSlider volume;
    private ThresholdSlider startSlider, stopSlider;
    private boolean dialogOpen, removed;
    private int dialogRequest;
    private String message = "";
    private boolean messageError;
    private long deleteConfirmUntil;

    private List<String> tracks = List.of();
    private long nextRefresh;

    private int left, top, panelWidth, statusY, buttonsY, hintY;
    private int listX, listY, listW, listH, listContentTop, listContentBottom, rowsVisible, scroll;
    private int rightX, rightY, rightW;
    private boolean wide, compact;

    public MusicScreen(HeartSongClient mod) {
        super(Component.translatable("screen.heartsong.title"));
        this.mod = mod;
    }
    private Component tr(String key) { return Component.translatable("screen.heartsong." + key); }
    private Button button(Component label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(label, b -> action.run()).bounds(x, y, w, 20).build());
    }

    @Override protected void init() {
        removed = false;
        compact = height < 320;
        panelWidth = Math.min(640, width - 24);
        wide = width >= 520 && panelWidth >= 500;
        left = (width - panelWidth) / 2;
        listH = wide ? (compact ? 100 : 168) : (compact ? 44 : 68);
        top = compact ? 4 : Math.max(4, (height - (wide ? 262 : 314)) / 2);
        int contentTop = top + (compact ? 16 : 20);
        int rowBrowse = compact ? 10 : 12;
        int rowPreview = compact ? 28 : 36;
        int rowVolume = compact ? 46 : 60;
        int rowAuto = compact ? 64 : 84;
        int rowStart = compact ? 82 : 108;
        int rowStop = compact ? 100 : 132;
        int rightH = rowStop + 20;
        if (wide) {
            listX = left; listY = contentTop; listW = Math.min(230, Math.max(180, panelWidth * 35 / 100));
            rightX = left + listW + 10; rightW = panelWidth - listW - 10; rightY = contentTop;
        } else {
            listX = left; listY = contentTop; listW = panelWidth;
            rightX = left; rightY = contentTop + listH + 24; rightW = panelWidth;
        }
        listContentTop = listY + 13;
        listContentBottom = listY + listH - 4;
        rowsVisible = Math.max(1, (listContentBottom - listContentTop) / 18);
        int bodyBottom = wide ? contentTop + Math.max(listH + 22, rightH) : rightY + rightH;
        statusY = bodyBottom + 3;
        buttonsY = compact ? bodyBottom + 4 : statusY + 16;
        hintY = buttonsY + (compact ? 18 : 22);

        int half = (panelWidth - 6) / 2;
        browse = button(tr("browse_import"), rightX, rightY + rowBrowse, rightW, this::browse);
        preview = button(tr("preview"), rightX, rightY + rowPreview, rightW, mod::preview);
        volume = addRenderableWidget(new VolumeSlider(rightX, rightY + rowVolume, rightW, 20));
        auto = button(enabledLabel(), rightX, rightY + rowAuto, rightW, () -> {
            mod.config.enabled = !mod.config.enabled; mod.save(); rebuildWidgets();
        });
        startSlider = addRenderableWidget(new ThresholdSlider(rightX, rightY + rowStart, rightW, 20, true));
        stopSlider = addRenderableWidget(new ThresholdSlider(rightX, rightY + rowStop, rightW, 20, false));
        delete = button(deleteLabel(), listX, listY + listH + 2, listW, this::deletePressed);
        changeKey = button(Component.translatable("screen.heartsong.change_key", mod.keyName()),
                left, buttonsY, half, this::openKeyBinds);
        done = button(tr("done"), left + half + 6, buttonsY, panelWidth - half - 6, this::onClose);
        setInitialFocus(browse);
        refreshTracks();
        updateControls();
    }

    @Override protected void rebuildWidgets() {
        super.rebuildWidgets();
        refreshTracks();
    }

    private void refreshTracks() {
        tracks = mod.tracks();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, tracks.size() - rowsVisible)));
        nextRefresh = System.nanoTime() + 1_000_000_000L;
    }

    private Component enabledLabel() {
        return Component.translatable("screen.heartsong.auto", tr(mod.config.enabled ? "on" : "off"));
    }

    private Component deleteLabel() {
        return System.nanoTime() < deleteConfirmUntil ? tr("delete_confirm") : tr("delete");
    }

    private void deletePressed() {
        if (!mod.hasSong()) {
            message = tr("delete_empty").getString(); messageError = true;
            return;
        }
        if (System.nanoTime() >= deleteConfirmUntil) {
            deleteConfirmUntil = System.nanoTime() + 4_000_000_000L;
            message = tr("delete_confirm").getString(); messageError = false;
            delete.setMessage(deleteLabel());
            return;
        }
        deleteConfirmUntil = 0;
        mod.deleteSelected();
        refreshTracks();
        message = mod.error.isEmpty() ? tr("deleted").getString() : mod.error;
        messageError = !mod.error.isEmpty();
        updateControls();
    }

    private void openKeyBinds() {
        mod.stopPreview();
        HeartSongClient.openScreen(minecraft, new KeyBindsScreen(this, minecraft.options));
    }

    private void browse() {
        if (dialogOpen || mod.importing) return;
        dialogOpen = true; updateControls();
        int request = ++dialogRequest;
        Thread.ofPlatform().daemon().name("HeartSong-FileDialog").start(() -> {
            String selection = null, failure = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                selection = TinyFileDialogs.tinyfd_openFileDialog("HeartSong - Select one MP3", "",
                    stack.pointers(stack.UTF8("*.mp3")), "MP3 audio (*.mp3)", false);
            } catch (Throwable e) {
                failure = e.getMessage();
                HeartSongClient.LOGGER.warn("Opening MP3 file dialog", e);
            }
            final String chosen = selection, problem = failure;
            minecraft.execute(() -> {
                if (removed || request != dialogRequest) return;
                dialogOpen = false;
                if (problem != null) {
                    message = tr("dialog_error").getString(); messageError = true;
                } else if (chosen != null) {
                    importSong(Path.of(chosen));   // choosing in the dialog is the save action
                }
                updateControls();
            });
        });
    }

    private void importSong(Path selected) {
        if (mod.importing) return;
        message = tr("importing").getString(); messageError = false;
        mod.importSong(selected, problem -> {
            messageError = !problem.isEmpty();
            message = messageError ? problem : tr("saved").getString();
            refreshTracks();
            updateControls();
        });
        updateControls();
    }

    @Override public void onFilesDrop(List<Path> paths) {
        if (paths.size() == 1) importSong(paths.getFirst());
        else { message = tr("single_only").getString(); messageError = true; }
    }

    private void updateControls() {
        if (browse == null) return;
        boolean busy = dialogOpen || mod.importing;
        browse.active = !busy;
        delete.active = !busy;
        preview.active = !busy && mod.hasSong() && (!mod.gate.active() || !mod.config.enabled);
        preview.setMessage(tr(mod.isPreview() ? "stop_preview" : "preview"));
        if (auto != null) auto.setMessage(enabledLabel());
        if (delete != null) delete.setMessage(deleteLabel());
        if (System.nanoTime() >= deleteConfirmUntil && deleteConfirmUntil != 0) {
            deleteConfirmUntil = 0;
        }
    }

    private int maxScroll() { return Math.max(0, tracks.size() - rowsVisible); }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < listX || mouseX > listX + listW) return -1;
        if (mouseY < listContentTop || mouseY >= listContentBottom) return -1;
        int idx = scroll + (int) ((mouseY - listContentTop) / 18);
        return idx >= 0 && idx < tracks.size() ? idx : -1;
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int row = rowAt(event.x(), event.y());
        if (row >= 0 && event.button() == 0) {
            String name = tracks.get(row);
            if (!name.equals(mod.config.selected)) {
                mod.selectTrack(name);
                message = ""; messageError = false;
                updateControls();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            if (maxScroll() > 0 && scrollY != 0) {
                scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY) * 2));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override public void tick() {
        if (System.nanoTime() >= nextRefresh) refreshTracks();
        updateControls();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, title, width / 2, top, 0xFFFFFFFF);

        drawLibrary(g, mouseX, mouseY);

        String name = mod.hasSong() ? mod.songName() : tr("empty").getString();
        String current = Component.translatable("screen.heartsong.selected", name).getString();
        g.text(font, font.plainSubstrByWidth(current, rightW), rightX, rightY, 0xFFFFFFFF);

        if (!compact) {
            Component state = tr(mod.importing ? "importing" : mod.player.isPlaying() ? "playing" : "waiting");
            if (minecraft.player != null) {
                state = Component.translatable("screen.heartsong.health_state",
                    String.format(Locale.ROOT, "%.0f%%", minecraft.player.getHealth() / minecraft.player.getMaxHealth() * 100f), state);
            }
            g.text(font, font.plainSubstrByWidth(state.getString(), panelWidth), left, statusY, 0xFFFFFFFF);
        }

        String feedback = mod.error.isEmpty() ? message : mod.error;
        if (!feedback.isEmpty()) {
            int color = messageError || !mod.error.isEmpty() ? 0xFFFFAAAA : 0xFFAADDFF;
            g.fill(left, hintY - 2, left + panelWidth, hintY + 10, 0x66000000);
            g.text(font, font.plainSubstrByWidth(feedback, panelWidth), left, hintY, color);
            if (mouseY >= hintY - 2 && mouseY <= hintY + 10)
                g.setTooltipForNextFrame(font, Component.literal(feedback), mouseX, mouseY);
        } else if (!compact) {
            g.text(font, font.plainSubstrByWidth(tr("hint").getString(), panelWidth), left, hintY, 0xFFCCCCCC);
        }
    }

    private void drawLibrary(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(listX, listY, listX + listW, listY + listH, 0x66000000);
        int border = 0xFF909090;
        g.fill(listX, listY, listX + listW, listY + 1, border);
        g.fill(listX, listY + listH - 1, listX + listW, listY + listH, border);
        g.fill(listX, listY, listX + 1, listY + listH, border);
        g.fill(listX + listW - 1, listY, listX + listW, listY + listH, border);
        String header = tr("library").getString();
        g.text(font, header, listX + 4, listY + 3, 0xFFFFFFFF);
        String count = Integer.toString(tracks.size());
        g.text(font, count, listX + listW - 4 - font.width(count), listY + 3, 0xFFA0A0A0);
        if (tracks.isEmpty()) {
            g.text(font, tr("library_empty"), listX + 4, listContentTop + 4, 0xFF909090);
            return;
        }
        int visible = Math.min(rowsVisible, tracks.size());
        g.enableScissor(listX + 1, listContentTop, listX + listW - 1, listContentBottom);
        for (int i = 0; i < visible; i++) {
            int index = scroll + i;
            if (index >= tracks.size()) break;
            String track = tracks.get(index);
            int rowY = listContentTop + i * 18;
            boolean selected = track.equals(mod.config.selected);
            boolean hovered = mouseX >= listX + 1 && mouseX < listX + listW - 1
                    && mouseY >= rowY && mouseY < rowY + 18;
            if (selected) g.fill(listX + 1, rowY, listX + listW - 1, rowY + 18, 0xFF3F76C6);
            else if (hovered) g.fill(listX + 1, rowY, listX + listW - 1, rowY + 18, 0x33FFFFFF);
            String label = font.plainSubstrByWidth(track, listW - 10);
            g.text(font, label, listX + 4, rowY + 4, selected ? 0xFFFFFFFF : 0xFFE0E0E0);
        }
        g.disableScissor();
        int max = maxScroll();
        if (max > 0) {
            int trackHeight = listContentBottom - listContentTop;
            int barHeight = Math.max(12, trackHeight * rowsVisible / tracks.size());
            int barY = listContentTop + (trackHeight - barHeight) * scroll / max;
            g.fill(listX + listW - 3, barY, listX + listW - 1, barY + barHeight, 0xFFB0B0B0);
        }
    }

    @Override public boolean isPauseScreen() { return true; }
    @Override public void removed() {
        removed = true;
        dialogRequest++;
        mod.save();
        mod.stopPreview();
    }

    private final class VolumeSlider extends AbstractSliderButton {
        VolumeSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), mod.config.volume);
            updateMessage();
        }
        @Override protected void updateMessage() {
            setMessage(Component.translatable("screen.heartsong.volume", Math.round((float) value * 100)));
        }
        @Override protected void applyValue() {
            mod.config.volume = (float) value;
            mod.player.setVolume(mod.config.volume);
        }
        @Override public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            mod.save();
        }
    }

    private final class ThresholdSlider extends AbstractSliderButton {
        private final boolean start;
        ThresholdSlider(int x, int y, int w, int h, boolean start) {
            super(x, y, w, h, Component.empty(),
                    (start ? mod.config.startPercent : mod.config.stopPercent) / 100.0);
            this.start = start;
            updateMessage();
        }
        @Override protected void updateMessage() {
            int percent = Math.round((float) value * 100);
            setMessage(Component.translatable(start ? "screen.heartsong.threshold_start"
                    : "screen.heartsong.threshold_stop", percent));
        }
        @Override protected void applyValue() {
            int percent = Math.round((float) value * 100);
            if (start) mod.setStartPercent(percent); else mod.setStopPercent(percent);
            int actual = start ? mod.config.startPercent : mod.config.stopPercent;
            if (actual != percent) {
                value = actual / 100.0;
                updateMessage();
            }
        }
        @Override public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            mod.save();
        }
    }
}

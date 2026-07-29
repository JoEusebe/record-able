package dev.recordable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V1-0.06 Feature 4: Watermark / Branding presets.
 *
 * <p>Stores named presets (each a list of up to 4 {@link WatermarkSlot}s) in a small
 * JSON file under the config directory, so users can save and re-apply branding setups.
 * Also ships a couple of built-in presets.</p>
 */
public final class WatermarkManager {

    private static final String PRESET_FILE = "recordable_watermark_presets.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PRESET_MAP_TYPE =
            new TypeToken<LinkedHashMap<String, List<WatermarkSlot>>>() {}.getType();

    private WatermarkManager() {}

    private static Path presetPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(PRESET_FILE);
    }

    /** Load all saved presets (name -> slots). Never returns null. */
    public static Map<String, List<WatermarkSlot>> loadPresets() {
        Path path = presetPath();
        if (!Files.isRegularFile(path)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, List<WatermarkSlot>> map = GSON.fromJson(reader, PRESET_MAP_TYPE);
            if (map == null) return new LinkedHashMap<>();
            // Sanitize every loaded slot.
            for (List<WatermarkSlot> slots : map.values()) {
                if (slots != null) {
                    for (WatermarkSlot s : slots) {
                        if (s != null) s.sanitize();
                    }
                }
            }
            return map;
        } catch (Exception e) {
            RecordableMod.LOGGER.warn("WatermarkManager: failed to load presets: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static void savePresets(Map<String, List<WatermarkSlot>> presets) {
        try (Writer writer = Files.newBufferedWriter(presetPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(presets, PRESET_MAP_TYPE, writer);
        } catch (Exception e) {
            RecordableMod.LOGGER.warn("WatermarkManager: failed to save presets: {}", e.getMessage());
        }
    }

    /** Save (or overwrite) a preset from a deep copy of the given slots. */
    public static void savePreset(String name, List<WatermarkSlot> slots) {
        if (name == null || name.isBlank()) return;
        Map<String, List<WatermarkSlot>> presets = loadPresets();
        List<WatermarkSlot> copy = new ArrayList<>();
        if (slots != null) {
            for (WatermarkSlot s : slots) {
                if (s != null) copy.add(s.copy());
            }
        }
        presets.put(name.trim(), copy);
        savePresets(presets);
    }

    /** Delete a named preset. */
    public static void deletePreset(String name) {
        Map<String, List<WatermarkSlot>> presets = loadPresets();
        if (presets.remove(name) != null) {
            savePresets(presets);
        }
    }

    /** Return a deep copy of a preset's slots, or null if not found. */
    public static List<WatermarkSlot> getPreset(String name) {
        List<WatermarkSlot> slots = loadPresets().get(name);
        if (slots == null) return null;
        List<WatermarkSlot> copy = new ArrayList<>();
        for (WatermarkSlot s : slots) {
            if (s != null) copy.add(s.copy());
        }
        return copy;
    }

    /** Built-in starter preset: a simple bottom-right username + date stamp. */
    public static List<WatermarkSlot> builtinUsernameStamp() {
        List<WatermarkSlot> slots = new ArrayList<>();
        WatermarkSlot text = new WatermarkSlot("Username Stamp", WatermarkSlot.Kind.TEXT);
        text.enabled = true;
        text.text = "{username} \u2022 {date}";
        text.position = WatermarkSlot.Position.BOTTOM_RIGHT;
        text.opacity = 70;
        slots.add(text);
        return slots;
    }

    /** Built-in starter preset: large centered channel name. */
    public static List<WatermarkSlot> builtinChannelName() {
        List<WatermarkSlot> slots = new ArrayList<>();
        WatermarkSlot text = new WatermarkSlot("Channel Name", WatermarkSlot.Kind.TEXT);
        text.enabled = true;
        text.text = "Record-able";
        text.position = WatermarkSlot.Position.TOP_CENTER;
        text.scale = 130;
        text.opacity = 60;
        text.animation = WatermarkSlot.Animation.FADE;
        slots.add(text);
        return slots;
    }
}

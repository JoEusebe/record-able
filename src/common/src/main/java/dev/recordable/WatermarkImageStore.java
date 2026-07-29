package dev.recordable;

import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manages a dedicated on-disk store for user-supplied watermark images
 * (V1-0.06 Watermark feature).
 *
 * <p>Images live under {@code .minecraft/recordable/watermarks/}. The watermark
 * config stores only the <em>filename</em> (not an absolute path), so configs stay
 * portable and the editor shows a short, friendly name. Importing copies the
 * selected file into the store with a unique name if a collision occurs.</p>
 *
 * <p>This is shared (common) code; it performs no rendering and is safe to call
 * from any variant.</p>
 */
public final class WatermarkImageStore {

    private WatermarkImageStore() {}

    /** Returns (creating if needed) the watermark image directory. */
    public static Path getWatermarksDir() {
        Path dir = FabricLoader.getInstance().getGameDir()
                .resolve("recordable").resolve("watermarks");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            RecordableMod.LOGGER.warn("[Record-able] Could not create watermark dir {}: {}",
                    dir, e.getMessage());
        }
        return dir;
    }

    /** True if the given path/filename has a supported image extension. */
    public static boolean isSupported(String pathOrName) {
        if (pathOrName == null) return false;
        String p = pathOrName.toLowerCase(Locale.ROOT);
        return p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg");
    }

    /**
     * Resolve a stored filename to an absolute {@link Path} inside the watermark
     * directory. Only the file-name component is honoured (guards against any
     * path traversal in stored values). Returns {@code null} for blank input.
     */
    public static Path resolve(String filename) {
        if (filename == null || filename.isBlank()) return null;
        String name;
        try {
            name = Paths.get(filename).getFileName().toString();
        } catch (Exception e) {
            return null;
        }
        if (name.isBlank()) return null;
        return getWatermarksDir().resolve(name);
    }

    /**
     * Copy the selected source image into the watermark store.
     *
     * @param sourcePath absolute path to the user-selected PNG/JPG/JPEG
     * @return the stored filename to persist in config, or {@code null} on failure
     */
    public static String importImage(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) return null;
        try {
            Path src = Paths.get(sourcePath);
            if (!Files.isRegularFile(src)) {
                RecordableMod.LOGGER.warn("[Record-able] Watermark image not a file: {}", sourcePath);
                return null;
            }
            String base = src.getFileName().toString();
            if (!isSupported(base)) {
                RecordableMod.LOGGER.warn("[Record-able] Unsupported watermark image type: {}", base);
                return null;
            }
            Path dir = getWatermarksDir();
            // Split stem/extension for unique-name generation.
            String stem = base;
            String ext = "";
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                stem = base.substring(0, dot);
                ext = base.substring(dot);
            }
            Path dest = dir.resolve(base);
            // If a file with the same name already exists and is byte-identical,
            // reuse it; otherwise generate a unique name.
            if (Files.exists(dest)) {
                if (sameContent(src, dest)) {
                    return dest.getFileName().toString();
                }
                int i = 1;
                do {
                    dest = dir.resolve(stem + "_" + i + ext);
                    i++;
                } while (Files.exists(dest) && i < 10000);
            }
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            RecordableMod.LOGGER.info("[Record-able] Imported watermark image: {} -> {}",
                    sourcePath, dest.getFileName());
            return dest.getFileName().toString();
        } catch (Exception e) {
            RecordableMod.LOGGER.warn("[Record-able] Failed to import watermark image {}: {}",
                    sourcePath, e.getMessage());
            return null;
        }
    }

    /** List filenames of all stored watermark images. */
    public static List<String> listImages() {
        List<String> out = new ArrayList<>();
        Path dir = getWatermarksDir();
        try {
            if (Files.isDirectory(dir)) {
                Files.list(dir)
                        .filter(Files::isRegularFile)
                        .filter(p -> isSupported(p.getFileName().toString()))
                        .sorted()
                        .forEach(p -> out.add(p.getFileName().toString()));
            }
        } catch (IOException e) {
            RecordableMod.LOGGER.warn("[Record-able] Could not list watermark images: {}", e.getMessage());
        }
        return out;
    }

    /** First 8 bytes of every PNG file. */
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * Read a stored watermark image and return its bytes encoded as PNG.
     *
     * <p>Minecraft's {@code NativeImage.read()} only understands PNG and throws
     * {@code IOException: Bad PNG Signature} for JPG/JPEG files - which is exactly
     * what made user-selected {@code .jpg} watermarks silently fail to load (the
     * watermark file picker accepts {@code .png/.jpg/.jpeg}). This helper normalises
     * any supported format to PNG bytes so the variant-specific renderers can feed
     * the result straight into {@code NativeImage.read()} without depending on
     * version-specific pixel-buffer APIs.</p>
     *
     * <p>If the file is already a PNG its raw bytes are returned unchanged. Otherwise
     * it is decoded with {@link ImageIO} and re-encoded as PNG (alpha preserved).</p>
     *
     * @return PNG-encoded bytes, or {@code null} if the file is missing or undecodable.
     */
    public static byte[] readAsPngBytes(Path path) {
        if (path == null) return null;
        try {
            if (!Files.isRegularFile(path)) return null;
            byte[] raw = Files.readAllBytes(path);
            if (isPng(raw)) {
                return raw; // already PNG - NativeImage can read it directly
            }
            BufferedImage img;
            try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(raw)) {
                img = ImageIO.read(in);
            }
            if (img == null) {
                RecordableMod.LOGGER.warn("[Record-able] Watermark image not decodable: {}", path.getFileName());
                return null;
            }
            // Ensure an ARGB raster so transparency round-trips through PNG cleanly.
            if (img.getType() != BufferedImage.TYPE_INT_ARGB) {
                BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = argb.createGraphics();
                g.drawImage(img, 0, 0, null);
                g.dispose();
                img = argb;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "png", out)) {
                RecordableMod.LOGGER.warn("[Record-able] No PNG writer available for watermark: {}", path.getFileName());
                return null;
            }
            return out.toByteArray();
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("[Record-able] Failed to decode watermark image '{}': {}",
                    path.getFileName(), t.toString());
            return null;
        }
    }

    private static boolean isPng(byte[] data) {
        if (data == null || data.length < PNG_SIGNATURE.length) return false;
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) return false;
        }
        return true;
    }

    private static boolean sameContent(Path a, Path b) {
        try {
            if (Files.size(a) != Files.size(b)) return false;
            byte[] ba = Files.readAllBytes(a);
            byte[] bb = Files.readAllBytes(b);
            if (ba.length != bb.length) return false;
            for (int i = 0; i < ba.length; i++) {
                if (ba[i] != bb[i]) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

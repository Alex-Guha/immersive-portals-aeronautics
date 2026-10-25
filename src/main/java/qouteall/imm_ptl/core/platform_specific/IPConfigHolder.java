/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.platform_specific;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads and saves {@code config/immersive_portals.json}, replacing Cloth Config's AutoConfig (until 2.5.8), whose file
 * format it keeps: the same pretty-printed Gson output, missing fields keep their defaults, unknown fields are ignored,
 * and the file is written back right after loading. A file that cannot be read is kept as
 * {@code immersive_portals.json.broken} before the defaults replace it (Cloth overwrote it).
 */
public class IPConfigHolder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private IPConfig config;

    private IPConfigHolder(Path path) {
        this.path = path;
    }

    public static IPConfigHolder load() {
        IPConfigHolder holder = new IPConfigHolder(FMLPaths.CONFIGDIR.get().resolve("immersive_portals.json"));
        holder.config = holder.read();
        holder.save();
        return holder;
    }

    private IPConfig read() {
        if (!Files.exists(path)) {
            return new IPConfig();
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            IPConfig result = GSON.fromJson(reader, IPConfig.class);
            if (result != null) {
                return result;
            }
            LOGGER.warn("{} is empty, using the defaults", path);
        }
        catch (IOException | JsonParseException e) {
            Path broken = path.resolveSibling(path.getFileName() + ".broken");
            LOGGER.error("Failed to read {}, using the defaults; the file is kept as {}", path, broken, e);
            try {
                Files.copy(path, broken, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException copyFailure) {
                LOGGER.error("Failed to keep a copy of {}", path, copyFailure);
            }
        }
        return new IPConfig();
    }

    public IPConfig getConfig() {
        return config;
    }

    public void setConfig(IPConfig config) {
        this.config = config;
    }

    /**
     * Applies the config ({@link IPConfig#onConfigChanged()}, which also clamps values) and writes it.
     */
    public void save() {
        config.onConfigChanged();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(config, writer);
            }
        }
        catch (IOException e) {
            LOGGER.error("Failed to save {}", path, e);
        }
    }
}

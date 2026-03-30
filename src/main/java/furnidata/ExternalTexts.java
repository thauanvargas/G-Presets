package furnidata;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and parses external_flash_texts from Habbo's gamedata.
 * Used to resolve display names for items like posters where
 * furnidata doesn't contain the variant-specific name.
 *
 * Format: key=value pairs, one per line.
 * Poster names follow the pattern: poster_<state>_name=<display name>
 */
public class ExternalTexts {

    private final Map<String, String> texts = new HashMap<>();

    public ExternalTexts(String url) throws IOException {
        String content = IOUtils.toString(new URL(url).openStream(), StandardCharsets.UTF_8);
        for (String line : content.split("\\r?\\n")) {
            int eqIdx = line.indexOf('=');
            if (eqIdx > 0) {
                String key = line.substring(0, eqIdx).trim();
                String value = line.substring(eqIdx + 1).trim();
                texts.put(key, value);
            }
        }
    }

    /**
     * Get a text value by key.
     * @return the value, or null if not found
     */
    public String get(String key) {
        return texts.get(key);
    }

    /**
     * Get the display name of a wall item variant.
     * Looks up "{className}_{state}_name" in the texts.
     *
     * @param className the item classname (e.g. "poster")
     * @param state the item state/variant
     * @return the localized name, or null if not found
     */
    public String getWallItemName(String className, String state) {
        if (className == null || state == null || state.isEmpty()) return null;
        return texts.get(className + "_" + state + "_name");
    }

    public int size() {
        return texts.size();
    }
}

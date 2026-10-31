package kr.pokeevents;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What each player caught thanks to the events (world/pokeevents-stats.json), read by the achievements mod:
 * announced legendaries (sub / box tier), the outbreak species while its outbreak runs, shinies during a shiny event.
 */
public final class EventStats {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Record {
        public int legendSub;
        public int legendBox;
        public int outbreak;
        public int shinyEvent;
    }

    private static Map<UUID, Record> records = new LinkedHashMap<>();
    private static Path file;

    private EventStats() {
    }

    /** The counts of a player, or null when nothing was caught yet (also before the server started). */
    public static Record get(UUID player) {
        return records.get(player);
    }

    static void load(Path path) {
        file = path;
        records = new LinkedHashMap<>();

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Map<UUID, Record> read = GSON.fromJson(reader, new TypeToken<Map<UUID, Record>>() { }.getType());

                if (read != null) {
                    records.putAll(read);
                }
            } catch (Exception e) {
                PokeEvents.LOGGER.error("Could not read {}", path, e);
            }
        }
    }

    /**
     * Counts a catch. legend: the tier when it was the announced legendary, else null; outbreakSpecies: the species
     * of the running outbreak, else null; shinyEvent: whether a shiny event runs.
     */
    static void onCaught(UUID player, String species, boolean shiny, Legends.Tier legend, String outbreakSpecies, boolean shinyEvent) {
        Record record = records.computeIfAbsent(player, u -> new Record());
        boolean changed = false;

        if (legend != null) {
            if (legend == Legends.Tier.BOX) {
                record.legendBox++;
            } else {
                record.legendSub++;
            }

            changed = true;
        }

        if (outbreakSpecies != null && outbreakSpecies.equals(species)) {
            record.outbreak++;
            changed = true;
        }

        if (shinyEvent && shiny) {
            record.shinyEvent++;
            changed = true;
        }

        if (changed) {
            save();
        }
    }

    private static void save() {
        if (file == null) {
            return;
        }

        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(records, writer);
        } catch (Exception e) {
            PokeEvents.LOGGER.error("Could not save {}", file, e);
        }
    }
}

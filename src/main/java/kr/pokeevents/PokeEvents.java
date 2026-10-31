package kr.pokeevents;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import kotlin.Unit;
import kr.spawnfilter.SpawnFilter;
import kr.spawnfilter.SpawnRates;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Server events: a shiny chance boost and pokemon outbreaks (one species spawns everywhere much more often, through
 * spawn-filter). Started by OPs with /pokeevent or automatically by config/poke-events.json; shown to everyone with a
 * boss bar counting down. Running events survive a restart (world/pokeevents-active.json).
 * Messages are plain Korean text (species names are translated by the client's Cobblemon).
 */
public class PokeEvents implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("pokeevents");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Random RANDOM = new Random();

    public enum Type { SHINY, OUTBREAK }

    /** A running event, also the saved form. */
    public static final class Active {
        public Type type;
        public float multiplier;
        public String species = "";
        public long startMillis;
        public long endMillis;
        transient ServerBossBar bar;
    }

    private static final Map<Type, Active> ACTIVE = new EnumMap<>(Type.class);
    private static EventSchedule schedule = EventSchedule.defaults();
    private static String lastScheduledMinute = "";
    private static MinecraftServer server;

    @Override
    public void onInitialize() {
        CobblemonEvents.SHINY_CHANCE_CALCULATION.subscribe(Priority.NORMAL, event -> {
            Active shiny = ACTIVE.get(Type.SHINY);

            if (shiny != null) {
                float multiplier = shiny.multiplier;
                event.addModificationFunction((chance, player, pokemon) -> chance * multiplier);
            }

            return Unit.INSTANCE;
        });
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL, event -> {
            if (server != null) {
                Legends.Tier legend = Legends.onCaught(server, event.getPlayer(), event.getPokemon());
                Active outbreak = ACTIVE.get(Type.OUTBREAK);
                EventStats.onCaught(event.getPlayer().getUuid(), event.getPokemon().getSpecies().getResourceIdentifier().getPath(),
                    event.getPokemon().getShiny(), legend, outbreak != null ? outbreak.species : null, ACTIVE.containsKey(Type.SHINY));
            }

            return Unit.INSTANCE;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            server = started;
            schedule = loadSchedule();
            restore();
            EventStats.load(started.getSavePath(WorldSavePath.ROOT).resolve("pokeevents-stats.json"));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(stopping -> {
            save();
            ACTIVE.values().forEach(a -> a.bar.clearPlayers());
            ACTIVE.clear();
            SpawnRates.setEventBoosts(Map.of());
            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(ticking -> {
            if (ticking.getTicks() % 20 == 0) {
                tickSecond();
            }
        });
        Vanish.register();
        ServerPlayConnectionEvents.JOIN.register((handler, sender, joined) ->
            ACTIVE.values().forEach(a -> a.bar.addPlayer(handler.getPlayer())));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("pokeevent")
                .executes(ctx -> status(ctx.getSource()))
                .then(CommandManager.literal("shiny")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("multiplier", FloatArgumentType.floatArg(1f, 100f))
                        .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 1440))
                            .executes(ctx -> startShiny(ctx.getSource(), FloatArgumentType.getFloat(ctx, "multiplier"),
                                IntegerArgumentType.getInteger(ctx, "minutes"))))))
                .then(CommandManager.literal("outbreak")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("species", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(
                            PokemonSpecies.INSTANCE.getImplemented().stream().map(s -> s.getResourceIdentifier().getPath()), builder))
                        .then(CommandManager.argument("multiplier", FloatArgumentType.floatArg(1f, 100f))
                            .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .executes(PokeEvents::outbreakCommand)))))
                .then(CommandManager.literal("stop")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> stopCommand(ctx.getSource(), null))
                    .then(CommandManager.literal("shiny").executes(ctx -> stopCommand(ctx.getSource(), Type.SHINY)))
                    .then(CommandManager.literal("outbreak").executes(ctx -> stopCommand(ctx.getSource(), Type.OUTBREAK))))
                .then(CommandManager.literal("legend")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.literal("sub")
                        .executes(ctx -> legendCommand(ctx.getSource(), Legends.Tier.SUB, null))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(ctx -> legendCommand(ctx.getSource(), Legends.Tier.SUB, EntityArgumentType.getPlayer(ctx, "player")))))
                    .then(CommandManager.literal("box")
                        .executes(ctx -> legendCommand(ctx.getSource(), Legends.Tier.BOX, null))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(ctx -> legendCommand(ctx.getSource(), Legends.Tier.BOX, EntityArgumentType.getPlayer(ctx, "player"))))))
                .then(CommandManager.literal("settings")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> {
                        Settings.lines(schedule).forEach(line -> ctx.getSource().sendFeedback(() -> line, false));
                        return 1;
                    }))
                .then(CommandManager.literal("set")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(Settings.ALL.keySet(), builder))
                        .then(CommandManager.argument("value", StringArgumentType.greedyString())
                            .executes(ctx -> setCommand(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "value"))))))
                .then(listCommand("species", "대량발생 후보", s -> s.random.species, (s, list) -> s.random.species = list))
                .then(listCommand("exclude", "전설 제외 목록", s -> s.legends.exclude, (s, list) -> s.legends.exclude = list))
                .then(CommandManager.literal("reload")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> {
                        schedule = loadSchedule();
                        ctx.getSource().sendFeedback(() -> Text.literal("이벤트 일정을 다시 읽었습니다."), true);
                        return status(ctx.getSource());
                    }))));
    }

    // commands

    private static int status(ServerCommandSource source) {
        if (ACTIVE.isEmpty()) {
            source.sendFeedback(() -> Text.literal("진행 중인 이벤트가 없습니다.").formatted(Formatting.GRAY), false);
        }

        for (Active active : ACTIVE.values()) {
            source.sendFeedback(() -> describe(active).append(Text.literal(" - 남은 시간 " + remaining(active))
                .formatted(Formatting.GRAY)), false);
        }

        EventSchedule.Random random = schedule.random;

        if (random != null && random.enabled) {
            source.sendFeedback(() -> Text.literal("무작위 이벤트: " + random.everyMinutes + "분마다 이로치 확률 x"
                + format(random.shinyMultiplier) + " 또는 대량발생 x" + format(random.outbreakMultiplier) + " 중 하나, "
                + random.minutes + "분 동안").formatted(Formatting.GOLD), false);
        }

        EventSchedule.Legends legends = schedule.legends;

        if (legends != null && legends.enabled) {
            source.sendFeedback(() -> Text.literal("전설 출현: " + legends.everyMinutes + "분마다 준전설 "
                + percent(legends.subChance) + " / 초전설·환상 " + percent(legends.boxChance) + ", 한 번에 한 마리, "
                + legends.despawnMinutes + "분 뒤 사라짐").formatted(Formatting.LIGHT_PURPLE), false);
        }

        if (!schedule.schedule.isEmpty()) {
            source.sendFeedback(() -> Text.literal("정기 이벤트:").formatted(Formatting.GOLD), false);

            for (EventSchedule.Entry entry : schedule.schedule) {
                String days = entry.days.isEmpty() ? "매일" : String.join(", ", entry.days.stream().map(PokeEvents::dayName).toList());
                String what = "outbreak".equals(entry.type) ? "대량발생 x" + format(entry.multiplier) : "이로치 확률 x" + format(entry.multiplier);
                source.sendFeedback(() -> Text.literal(" " + days + " " + entry.time + " - " + what + ", " + entry.minutes + "분")
                    .formatted(Formatting.GRAY), false);
            }
        }

        return 1;
    }

    private static int setCommand(ServerCommandSource source, String name, String value) {
        String problem = Settings.set(schedule, name, value);

        if (problem != null) {
            source.sendError(Text.literal(problem));
            return 0;
        }

        saveSchedule();
        Settings.Setting setting = Settings.ALL.get(name);
        source.sendFeedback(() -> Text.literal("바꿨습니다: " + setting.label() + " = " + setting.get().apply(schedule))
            .formatted(Formatting.GREEN), true);
        return 1;
    }

    /** /pokeevent <literal> add|remove <species>: edits a species list of the settings and saves. */
    private static LiteralArgumentBuilder<ServerCommandSource> listCommand(String literal, String label,
                                                                          Function<EventSchedule, List<String>> get,
                                                                          BiConsumer<EventSchedule, List<String>> set) {
        SuggestionProvider<ServerCommandSource> allSpecies = (ctx, builder) -> CommandSource.suggestMatching(
            PokemonSpecies.INSTANCE.getImplemented().stream().map(s -> s.getResourceIdentifier().getPath()), builder);
        SuggestionProvider<ServerCommandSource> listed = (ctx, builder) -> CommandSource.suggestMatching(get.apply(schedule), builder);

        return CommandManager.literal(literal)
            .requires(source -> source.hasPermissionLevel(2))
            .then(CommandManager.literal("add")
                .then(CommandManager.argument("species", StringArgumentType.word()).suggests(allSpecies)
                    .executes(ctx -> editList(ctx.getSource(), label, get, set, StringArgumentType.getString(ctx, "species"), true))))
            .then(CommandManager.literal("remove")
                .then(CommandManager.argument("species", StringArgumentType.word()).suggests(listed)
                    .executes(ctx -> editList(ctx.getSource(), label, get, set, StringArgumentType.getString(ctx, "species"), false))));
    }

    private static int editList(ServerCommandSource source, String label, Function<EventSchedule, List<String>> get,
                                BiConsumer<EventSchedule, List<String>> set, String species, boolean add) {
        String id = species.toLowerCase(Locale.ROOT);
        List<String> list = new ArrayList<>(get.apply(schedule));

        if (add && PokemonSpecies.INSTANCE.getByName(id) == null) {
            source.sendError(Text.literal("알 수 없는 포켓몬입니다: " + species));
            return 0;
        }

        if (add ? list.contains(id) : !list.remove(id)) {
            source.sendError(Text.literal(add ? "이미 목록에 있습니다: " + id : "목록에 없습니다: " + id));
            return 0;
        }

        if (add) {
            list.add(id);
        }

        set.accept(schedule, list);
        saveSchedule();
        source.sendFeedback(() -> Text.literal("바꿨습니다: " + label + " = " + (list.isEmpty() ? "없음" : String.join(", ", list)))
            .formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int legendCommand(ServerCommandSource source, Legends.Tier tier, ServerPlayerEntity player) {
        String problem = Legends.spawn(source.getServer(), schedule.legends != null ? schedule.legends : new EventSchedule.Legends(),
            tier, player);

        if (problem != null) {
            source.sendError(Text.literal(problem));
            return 0;
        }

        return 1;
    }

    private static String percent(double chance) {
        return BigDecimal.valueOf(chance * 100).stripTrailingZeros().toPlainString() + "%";
    }

    private static int startShiny(ServerCommandSource source, float multiplier, int minutes) {
        start(Type.SHINY, multiplier, "", minutes);
        return 1;
    }

    private static int outbreakCommand(CommandContext<ServerCommandSource> ctx) {
        String species = StringArgumentType.getString(ctx, "species").toLowerCase(Locale.ROOT);
        String problem = outbreakProblem(species);

        if (problem != null) {
            ctx.getSource().sendError(Text.literal(problem));
            return 0;
        }

        start(Type.OUTBREAK, FloatArgumentType.getFloat(ctx, "multiplier"), species,
            IntegerArgumentType.getInteger(ctx, "minutes"));
        return 1;
    }

    /** Why a species cannot have an outbreak, or null. */
    private static String outbreakProblem(String species) {
        if (PokemonSpecies.INSTANCE.getByName(species) == null) {
            return "알 수 없는 포켓몬입니다: " + species;
        }

        if (!SpawnRates.hasSpawnData(species)) {
            return "자연 스폰 정보가 없는 포켓몬이라 대량발생을 할 수 없습니다: " + species;
        }

        return null;
    }

    private static int stopCommand(ServerCommandSource source, Type type) {
        if (type == null ? ACTIVE.isEmpty() : !ACTIVE.containsKey(type)) {
            source.sendError(Text.literal("진행 중인 해당 이벤트가 없습니다."));
            return 0;
        }

        for (Type t : Type.values()) {
            if (type == null || type == t) {
                end(t, true);
            }
        }

        return 1;
    }

    // running events

    private static void start(Type type, float multiplier, String species, int minutes) {
        if (ACTIVE.containsKey(type)) {
            end(type, false); // replaced by the new one
        }

        Active active = new Active();
        active.type = type;
        active.multiplier = multiplier;
        active.species = species;
        active.startMillis = System.currentTimeMillis();
        active.endMillis = active.startMillis + minutes * 60_000L;
        activate(active);
        save();
        broadcast(Text.literal("[이벤트 시작] ").formatted(Formatting.GOLD).append(describe(active))
            .append(Text.literal(" (" + minutes + "분)").formatted(Formatting.GRAY)));
        LOGGER.info("event started: {} x{} {} for {} min", type, multiplier, species, minutes);
    }

    /** Applies an event (new, or restored after a restart) and shows its boss bar. */
    private static void activate(Active active) {
        active.bar = new ServerBossBar(describe(active), active.type == Type.SHINY ? BossBar.Color.YELLOW : BossBar.Color.GREEN,
            BossBar.Style.PROGRESS);
        server.getPlayerManager().getPlayerList().forEach(active.bar::addPlayer);
        ACTIVE.put(active.type, active);

        if (active.type == Type.OUTBREAK) {
            SpawnRates.setEventBoosts(Map.of(active.species, active.multiplier));
            SpawnFilter.loadForced(server, () -> broadcast(Text.literal("대량발생 포켓몬을 불러오는 중입니다. 잠깐 렉이 있을 수 있어요.")
                .formatted(Formatting.YELLOW)));
        }
    }

    private static void end(Type type, boolean announce) {
        Active active = ACTIVE.remove(type);

        if (active == null) {
            return;
        }

        active.bar.clearPlayers();

        if (type == Type.OUTBREAK) {
            SpawnRates.setEventBoosts(Map.of());
        }

        save();

        if (announce) {
            broadcast(Text.literal("[이벤트 종료] ").formatted(Formatting.GOLD).append(describe(active)));
        }

        LOGGER.info("event ended: {}", type);
    }

    /** The running event of a type, or null. */
    static Active active(Type type) {
        return ACTIVE.get(type);
    }

    private static void tickSecond() {
        if (server == null) {
            return;
        }

        long now = System.currentTimeMillis();

        for (Type type : Type.values()) {
            Active active = ACTIVE.get(type);

            if (active == null) {
                continue;
            }

            if (now >= active.endMillis) {
                end(type, true);
            } else {
                long total = Math.max(1, active.endMillis - active.startMillis);
                active.bar.setPercent(Math.max(0f, Math.min(1f, (active.endMillis - now) / (float) total)));
                active.bar.setName(describe(active).append(Text.literal(" - " + remaining(active)).formatted(Formatting.WHITE)));
            }
        }

        runSchedule();
        Legends.tick(server, schedule.legends);
    }

    private static void runSchedule() {
        LocalDateTime now = LocalDateTime.now();
        String minute = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        if (minute.equals(lastScheduledMinute)) {
            return;
        }

        lastScheduledMinute = minute;

        if (server.getPlayerManager().getCurrentPlayerCount() == 0) {
            return; // nobody to enjoy it: scheduled and random events wait for players
        }

        String day = now.getDayOfWeek().name().substring(0, 3);
        String time = now.format(TIME);
        EventSchedule.Random random = schedule.random;
        int minuteOfDay = now.getHour() * 60 + now.getMinute();

        if (random != null && random.enabled && random.everyMinutes > 0 && minuteOfDay % random.everyMinutes == 0) {
            String species = RANDOM.nextBoolean() ? pickSpecies(random.species) : null;

            if (species != null) {
                start(Type.OUTBREAK, random.outbreakMultiplier, species, random.minutes);
            } else { // shiny, also when no outbreak species is usable
                start(Type.SHINY, random.shinyMultiplier, "", random.minutes);
            }
        }

        for (EventSchedule.Entry entry : schedule.schedule) {
            boolean today = entry.days.isEmpty() || entry.days.stream().anyMatch(d -> d.equalsIgnoreCase(day));

            if (!today || !time.equals(entry.time)) {
                continue;
            }

            if ("outbreak".equalsIgnoreCase(entry.type)) {
                String species = pickSpecies(entry.species);

                if (species == null) {
                    LOGGER.warn("scheduled outbreak at {} has no usable species", entry.time);
                    continue;
                }

                start(Type.OUTBREAK, entry.multiplier, species, entry.minutes);
            } else {
                start(Type.SHINY, entry.multiplier, "", entry.minutes);
            }
        }
    }

    /**
     * A random species of the list that can have an outbreak, or null. An empty list: any species with natural
     * spawns except legendaries and mythicals.
     */
    private static String pickSpecies(List<String> species) {
        List<String> candidates = species == null || species.isEmpty() ? allOutbreakSpecies()
            : species.stream().map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> outbreakProblem(s) == null).toList();
        return candidates.isEmpty() ? null : candidates.get(RANDOM.nextInt(candidates.size()));
    }

    static List<String> allOutbreakSpecies() {
        List<String> all = new ArrayList<>();

        for (Species species : PokemonSpecies.INSTANCE.getImplemented()) {
            var labels = species.getLabels();
            String name = species.getResourceIdentifier().getPath();

            if (!labels.contains("legendary") && !labels.contains("mythical") && !labels.contains("restricted")
                && SpawnRates.hasSpawnData(name)) {
                all.add(name);
            }
        }

        return all;
    }

    // text

    private static MutableText describe(Active active) {
        if (active.type == Type.SHINY) {
            return Text.literal("이로치 확률 " + format(active.multiplier) + "배!").formatted(Formatting.YELLOW);
        }

        Species species = PokemonSpecies.INSTANCE.getByName(active.species);
        Text name = species != null ? species.getTranslatedName() : Text.literal(active.species);
        return Text.empty().append(Text.literal("대량발생: ").formatted(Formatting.GREEN))
            .append(name.copy().formatted(Formatting.AQUA))
            .append(Text.literal(" (출현율 " + format(active.multiplier) + "배)").formatted(Formatting.GREEN));
    }

    private static String remaining(Active active) {
        long seconds = Math.max(0, (active.endMillis - System.currentTimeMillis()) / 1000);
        return seconds >= 3600 ? String.format("%d시간 %02d분", seconds / 3600, seconds % 3600 / 60)
            : String.format("%d분 %02d초", seconds / 60, seconds % 60);
    }

    private static String format(float value) {
        return value == Math.floor(value) ? Integer.toString((int) value) : Float.toString(value);
    }

    private static String dayName(String day) {
        return switch (day.toUpperCase(Locale.ROOT)) {
            case "MON" -> "월";
            case "TUE" -> "화";
            case "WED" -> "수";
            case "THU" -> "목";
            case "FRI" -> "금";
            case "SAT" -> "토";
            case "SUN" -> "일";
            default -> day;
        };
    }

    private static void broadcast(Text text) {
        server.getPlayerManager().broadcast(text, false);
    }

    // files

    private static Path scheduleFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("poke-events.json");
    }

    private static EventSchedule loadSchedule() {
        Path file = scheduleFile();

        try {
            if (Files.notExists(file)) {
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(EventSchedule.defaults(), writer);
                }
            }

            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                EventSchedule read = GSON.fromJson(reader, EventSchedule.class);

                // sections missing from an older file get their defaults
                read.random = read.random != null ? read.random : new EventSchedule.Random();
                read.legends = read.legends != null ? read.legends : new EventSchedule.Legends();
                read.schedule = read.schedule != null ? read.schedule : List.of();
                read.random.species = read.random.species != null ? read.random.species : List.of();
                read.legends.exclude = read.legends.exclude != null ? read.legends.exclude : List.of();
                LOGGER.info("Loaded {} scheduled events", read.schedule.size());
                return read;
            }
        } catch (Exception e) {
            LOGGER.error("Could not read {}, using the defaults", file, e);
            return EventSchedule.defaults();
        }
    }

    /** Writes the settings changed in game back to the file. */
    private static void saveSchedule() {
        try (Writer writer = Files.newBufferedWriter(scheduleFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(schedule, writer);
        } catch (Exception e) {
            LOGGER.error("Could not save {}", scheduleFile(), e);
        }
    }

    private static Path activeFile() {
        return server.getSavePath(WorldSavePath.ROOT).resolve("pokeevents-active.json");
    }

    private static void save() {
        try (Writer writer = Files.newBufferedWriter(activeFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(ACTIVE.values().toArray(new Active[0]), writer);
        } catch (Exception e) {
            LOGGER.error("Could not save the running events", e);
        }
    }

    /** Events still running from before a restart continue until their end time. */
    private static void restore() {
        Path file = activeFile();

        if (Files.notExists(file)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Active[] saved = GSON.fromJson(reader, Active[].class);

            for (Active active : saved == null ? new Active[0] : saved) {
                if (active.type != null && active.endMillis > System.currentTimeMillis()) {
                    activate(active);
                    LOGGER.info("event restored: {} until {}", active.type, active.endMillis);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Could not read the running events", e);
        }
    }
}

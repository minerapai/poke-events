package kr.pokeevents;

import com.cobblemon.mod.common.api.entity.Despawner;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Rare legendary encounters (see EventSchedule.Legends): one roll for the whole server every few minutes, one
 * legendary at a time near a random player, announced with its coordinates; it leaves after a while unless in battle.
 */
public final class Legends {
    private static final Random RANDOM = new Random();

    public enum Tier { SUB, BOX }

    /** The legendary that is out right now (its pokemon's uuid), or null. */
    private static UUID current;
    private static Tier currentTier;
    private static long lastRollMinute = -1;

    private Legends() {
    }

    /** Called every second. */
    public static void tick(MinecraftServer server, EventSchedule.Legends config) {
        if (config == null || !config.enabled || config.everyMinutes <= 0) {
            return;
        }

        if (current != null) {
            PokemonEntity found = find(server);

            if (found == null) {
                PokeEvents.LOGGER.info("[vanish] legendary {} no longer in any world", current);
                current = null; // caught, knocked out, or gone with its chunk
            } else if (server.getTicks() / 20 % 5 == 0) {
                Vanish.trace(found);
            }
        }

        long minute = System.currentTimeMillis() / 60_000L;

        if (minute == lastRollMinute || minute % config.everyMinutes != 0) {
            return;
        }

        lastRollMinute = minute;

        if (current != null) {
            return; // only one at a time
        }

        double roll = RANDOM.nextDouble();
        Tier tier = roll < config.boxChance ? Tier.BOX : roll < config.boxChance + config.subChance ? Tier.SUB : null;

        if (tier != null) {
            spawn(server, config, tier, null);
        }
    }

    /** Whether a pokemon (by its uuid) is the legendary that is out right now. */
    static boolean isCurrent(UUID pokemon) {
        return current != null && current.equals(pokemon);
    }

    /** Spawns one now (also /pokeevent legend). Returns a problem, or null when it appeared. */
    public static String spawn(MinecraftServer server, EventSchedule.Legends config, Tier tier, ServerPlayerEntity target) {
        if (current != null && find(server) != null) {
            return "이미 전설의 포켓몬이 나와 있습니다.";
        }

        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList().stream()
            .filter(p -> !p.isSpectator()).toList();

        if (target == null && players.isEmpty()) {
            return "접속 중인 플레이어가 없습니다.";
        }

        ServerPlayerEntity player = target != null ? target : players.get(RANDOM.nextInt(players.size()));
        List<Species> candidates = candidates(config, tier);

        if (candidates.isEmpty()) {
            return "나올 수 있는 포켓몬이 없습니다.";
        }

        ServerWorld world = player.getServerWorld();
        BlockPos pos = findSpot(world, player.getBlockPos(), config.minDistance, config.maxDistance);

        if (pos == null) {
            PokeEvents.LOGGER.info("no place for a legendary near {}", player.getGameProfile().getName());
            return player.getGameProfile().getName() + " 근처에 나올 자리가 없습니다.";
        }

        Species species = candidates.get(RANDOM.nextInt(candidates.size()));
        int level = Math.max(1, config.capToLevelCap ? Math.min(config.level, levelCap(player)) : config.level);
        PokemonEntity entity = PokemonProperties.Companion.parse("species=" + species.getResourceIdentifier() + " level=" + level)
            .createEntity(world);
        long leaveAt = System.currentTimeMillis() + config.despawnMinutes * 60_000L;
        entity.setDespawner(new Leave(server, leaveAt));
        entity.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, RANDOM.nextFloat() * 360f, 0f);

        if (!world.spawnEntity(entity)) {
            return "포켓몬을 소환하지 못했습니다.";
        }

        current = entity.getPokemon().getUuid();
        currentTier = tier;
        Pokemon pokemon = entity.getPokemon();
        PokeEvents.LOGGER.info("legendary {} lv{} ({}) near {} at {}", species.getName(), level, tier,
            player.getGameProfile().getName(), pos.toShortString());

        MutableText message = Text.literal(tier == Tier.BOX ? "[초전설 출현] " : "[전설 출현] ")
            .formatted(tier == Tier.BOX ? Formatting.LIGHT_PURPLE : Formatting.GOLD, Formatting.BOLD)
            .append(name(pokemon).copy().formatted(Formatting.AQUA))
            .append(Text.literal(" Lv." + level + (pokemon.getShiny() ? " (이로치!)" : "")).formatted(Formatting.AQUA))
            .append(Text.literal(" 이(가) " + player.getGameProfile().getName() + " 근처에 나타났습니다!").formatted(Formatting.YELLOW))
            .append(Text.literal("\n 좌표: " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (" + dimension(world)
                + ") - " + config.despawnMinutes + "분 뒤 사라집니다").formatted(Formatting.GRAY));
        server.getPlayerManager().broadcast(message, false);
        return null;
    }

    /** Called when a pokemon is caught: announce it if it is the current legendary. Returns its tier then, else null. */
    public static Tier onCaught(MinecraftServer server, ServerPlayerEntity player, Pokemon pokemon) {
        if (current == null || !current.equals(pokemon.getUuid())) {
            return null;
        }

        current = null;
        server.getPlayerManager().broadcast(Text.literal(player.getGameProfile().getName() + "님이 ")
            .formatted(Formatting.GOLD).append(name(pokemon).copy().formatted(Formatting.AQUA))
            .append(Text.literal("을(를) 잡았습니다!").formatted(Formatting.GOLD)), false);
        return currentTier;
    }

    private static List<Species> candidates(EventSchedule.Legends config, Tier tier) {
        List<String> exclude = config.exclude.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        return PokemonSpecies.INSTANCE.getImplemented().stream().filter(species -> {
            var labels = species.getLabels();
            boolean mythical = labels.contains("mythical");
            boolean box = labels.contains("restricted") || mythical && config.mythicalAsBox;
            boolean sub = labels.contains("legendary") && !labels.contains("restricted") && !mythical;
            return (tier == Tier.BOX ? box : sub) && !exclude.contains(species.getResourceIdentifier().getPath());
        }).toList();
    }

    private static int levelCap(ServerPlayerEntity player) {
        return FabricLoader.getInstance().isModLoaded("rctmod") ? RctLevelCap.of(player) : Integer.MAX_VALUE;
    }

    /** A free spot on the ground (two blocks of air above a solid top face) some distance from the player. */
    private static BlockPos findSpot(ServerWorld world, BlockPos center, int minDistance, int maxDistance) {
        for (int attempt = 0; attempt < 40; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            double distance = minDistance + RANDOM.nextDouble() * Math.max(0, maxDistance - minDistance);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);

            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }

            for (int dy = 8; dy >= -12; dy--) {
                BlockPos pos = new BlockPos(x, center.getY() + dy, z);
                BlockPos below = pos.down();

                if (world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)
                    && world.getBlockState(pos).isAir() && world.getBlockState(pos.up()).isAir()) {
                    return pos;
                }
            }
        }

        return null;
    }

    private static PokemonEntity find(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            for (Entity entity : world.iterateEntities()) {
                if (entity instanceof PokemonEntity pokemon && pokemon.isAlive() && current.equals(pokemon.getPokemon().getUuid())) {
                    return pokemon;
                }
            }
        }

        return null;
    }

    private static Text name(Pokemon pokemon) {
        return pokemon.getSpecies().getTranslatedName();
    }

    private static String dimension(ServerWorld world) {
        String id = world.getRegistryKey().getValue().toString();
        return switch (id) {
            case "minecraft:overworld" -> "오버월드";
            case "minecraft:the_nether" -> "네더";
            case "minecraft:the_end" -> "엔드";
            default -> id;
        };
    }

    /** Leaves after its time, but never in the middle of a battle. */
    private record Leave(MinecraftServer server, long leaveAt) implements Despawner<PokemonEntity> {
        @Override
        public void beginTracking(PokemonEntity entity) {
        }

        @Override
        public boolean shouldDespawn(PokemonEntity entity) {
            if (System.currentTimeMillis() < leaveAt || entity.isBattling() || entity.isBusy() || !entity.getPokemon().isWild()) {
                return false;
            }

            if (current != null && current.equals(entity.getPokemon().getUuid())) {
                current = null;
                server.getPlayerManager().broadcast(name(entity.getPokemon()).copy().formatted(Formatting.AQUA)
                    .append(Text.literal("이(가) 어디론가 사라졌습니다...").formatted(Formatting.GRAY)), false);
            }

            return true;
        }
    }
}

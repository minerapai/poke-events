package kr.pokeevents;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Diagnostics for wild event pokemon (the announced legendary, the outbreak species) that players see frozen in
 * place while the server no longer has them: logs why and from where each one leaves the world, and where the
 * legendary is every few seconds, as "[vanish]" lines in the server log.
 */
final class Vanish {
    private Vanish() {
    }

    static void register() {
        ServerEntityEvents.ENTITY_UNLOAD.register(Vanish::onUnload);
    }

    private static void onUnload(Entity entity, ServerWorld world) {
        if (!(entity instanceof PokemonEntity pokemon) || !pokemon.getPokemon().isWild() || !watched(pokemon)) {
            return;
        }

        String caller = Arrays.stream(Thread.currentThread().getStackTrace())
            .map(f -> f.getClassName() + "." + f.getMethodName())
            .filter(f -> !f.startsWith("java.") && !f.startsWith("jdk.") && !f.startsWith("kr.pokeevents.Vanish")
                && !f.startsWith("net.fabricmc.fabric.") && !f.startsWith("kotlin.") && !f.contains("invokeEntityUnloadEvent")
                && !f.startsWith("net.minecraft.class_3218$class_5526") && !f.startsWith("net.minecraft.class_5579")
                && !f.startsWith("dev.architectury.hooks.level.entity"))
            .limit(24)
            .collect(Collectors.joining(" < "));
        PokeEvents.LOGGER.info("[vanish] {} gone: reason={} {} age={}t {} | {}", pokemon.getPokemon().getSpecies().getName(),
            entity.getRemovalReason(), where(pokemon, world), entity.age, nearest(pokemon, world), caller);
    }

    /** Logs where the legendary is now (called every few seconds while it is out). */
    static void trace(PokemonEntity pokemon) {
        ServerWorld world = (ServerWorld) pokemon.getWorld();
        PokeEvents.LOGGER.info("[vanish] {} at {} age={}t battling={} {}", pokemon.getPokemon().getSpecies().getName(),
            where(pokemon, world), pokemon.age, pokemon.isBattling(), nearest(pokemon, world));
    }

    private static boolean watched(PokemonEntity pokemon) {
        PokeEvents.Active outbreak = PokeEvents.active(PokeEvents.Type.OUTBREAK);
        return Legends.isCurrent(pokemon.getPokemon().getUuid())
            || outbreak != null && outbreak.species.equals(pokemon.getPokemon().getSpecies().getResourceIdentifier().getPath());
    }

    private static String where(PokemonEntity pokemon, ServerWorld world) {
        ChunkPos chunk = pokemon.getChunkPos();
        return String.format("%s %.1f %.1f %.1f entityTicking=%s", world.getRegistryKey().getValue(), pokemon.getX(),
            pokemon.getY(), pokemon.getZ(), world.shouldTickEntity(pokemon.getBlockPos()) + "/" + world.isChunkLoaded(chunk.x, chunk.z));
    }

    private static String nearest(PokemonEntity pokemon, ServerWorld world) {
        ServerPlayerEntity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (ServerPlayerEntity player : world.getPlayers()) {
            double distance = player.squaredDistanceTo(pokemon);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }

        return best == null ? "no player in world"
            : String.format("nearest=%s %.0f blocks", best.getGameProfile().getName(), Math.sqrt(bestDistance));
    }
}

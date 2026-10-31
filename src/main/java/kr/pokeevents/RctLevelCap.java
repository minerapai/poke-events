package kr.pokeevents;

import com.gitlab.srcmc.rctmod.api.RCTMod;
import net.minecraft.server.network.ServerPlayerEntity;

/** Only loaded when RCT is installed (see Legends.levelCap). */
final class RctLevelCap {
    private RctLevelCap() {
    }

    static int of(ServerPlayerEntity player) {
        return RCTMod.getInstance().getTrainerManager().getData(player).getLevelCap();
    }
}

package cn.contribution.industry;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

public final class ShearingContext {
    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private ShearingContext() {
    }

    public static void enter(ServerPlayer player, LivingEntity target) {
        CURRENT.set(new Actor(player, target.getUUID()));
    }

    public static void leave() {
        CURRENT.remove();
    }

    public static ServerPlayer actorFor(LivingEntity target) {
        Actor actor = CURRENT.get();
        return actor != null && actor.target.equals(target.getUUID()) ? actor.player : null;
    }

    private record Actor(ServerPlayer player, UUID target) { }
}

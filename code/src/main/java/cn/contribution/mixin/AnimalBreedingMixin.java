package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Animal.class)
public abstract class AnimalBreedingMixin {
    @Unique private static final ThreadLocal<ServerPlayer> contribution$breeder = new ThreadLocal<>();

    @Inject(method = "spawnChildFromBreeding", at = @At("HEAD"))
    private void contribution$captureBreeder(ServerLevel level, Animal mate, CallbackInfo callback) {
        Animal parent = (Animal) (Object) this;
        ServerPlayer breeder = parent.getLoveCause();
        contribution$breeder.set(breeder != null ? breeder : mate.getLoveCause());
    }

    @Inject(method = "spawnChildFromBreeding", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V",
            shift = At.Shift.AFTER))
    private void contribution$afterOffspringSpawn(ServerLevel level, Animal mate, CallbackInfo callback) {
        ServerPlayer breeder = contribution$breeder.get();
        contribution$breeder.remove();
        if (ContributionRuntime.statistics() != null) {
            ContributionRuntime.statistics().gameEvent(level.getServer(), "contribution:entity/breed", 1, breeder);
        }
    }
}

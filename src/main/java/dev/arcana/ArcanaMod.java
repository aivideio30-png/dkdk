package dev.arcana;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ArcanaMod implements ModInitializer {
    public static final String ID = "particle_arcana";
    static final Logger LOG = LoggerFactory.getLogger(ID);
    static final List<Ability> ACTIVE = new ArrayList<>();
    private static boolean flip;

    @Override
    public void onInitialize() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp))
                return InteractionResult.PASS;
            ItemStack s = player.getItemInHand(hand);

            if (s.is(ItemTags.SWORDS)) {
                if (player.getCooldowns().isOnCooldown(s)) return InteractionResult.PASS;
                flip = !flip;
                ACTIVE.add(new Slash(sp, flip));
                player.getCooldowns().addCooldown(s, 15);
                return InteractionResult.SUCCESS;
            }
            if (s.is(Items.AMETHYST_SHARD)) {
                Telekinesis running = find(sp.getUUID(), Telekinesis.class);
                if (running != null) { running.launchNow(); return InteractionResult.SUCCESS; }
                if (player.getCooldowns().isOnCooldown(s)) return InteractionResult.PASS;
                ACTIVE.add(new Telekinesis(sp));
                player.getCooldowns().addCooldown(s, 100);
                return InteractionResult.SUCCESS;
            }
            if (s.is(Items.CLOCK)) {
                if (player.getCooldowns().isOnCooldown(s) || find(sp.getUUID(), TimeStop.class) != null)
                    return InteractionResult.PASS;
                ACTIVE.add(new TimeStop(sp));
                player.getCooldowns().addCooldown(s, 400);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Ability> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Ability a = it.next();
                try {
                    if (a.tick()) it.remove();
                } catch (Exception ex) {
                    LOG.error("ability error", ex);
                    try { a.cancel(); } catch (Exception ignored) {}
                    it.remove();
                }
            }
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
            return !TimeStop.absorb(entity, amount);
        });

        LOG.info("Particle Arcana loaded: sword=Slash, amethyst shard=Telekinesis, clock=TimeStop");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Ability> T find(UUID id, Class<T> c) {
        for (Ability a : ACTIVE) if (c.isInstance(a) && a.ownerId().equals(id)) return (T) a;
        return null;
    }
}

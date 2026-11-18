package dev.arcana;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 자수정 조각 우클릭: 블록/몬스터를 띄워 회전시키다 시선 방향으로 발사. 다시 우클릭하면 즉시 발사. */
final class Telekinesis implements Ability {
    private static final int LIFT = 25, ORBIT = 90, FLIGHT = 40;

    private static final class Body {
        final Entity e;
        final double ang, rad, hgt;
        final boolean noGrav;
        Vec3 last;
        Body(Entity e, Random r, boolean block) {
            this.e = e;
            this.noGrav = e.isNoGravity();
            this.ang = r.nextDouble() * Fx.TAU;
            this.rad = block ? 1.8 + r.nextDouble() * 3.0 : 2.5 + r.nextDouble() * 2.5;
            this.hgt = (r.nextDouble() - 0.5) * 3.5;
        }
    }

    private final ServerLevel lv;
    private final ServerPlayer owner;
    private final Random rnd = new Random();
    private final List<Body> bodies = new ArrayList<>();
    private final Vec3 anchor;
    private boolean flying;
    private int age, flyAge;

    Telekinesis(ServerPlayer p) {
        owner = p;
        lv = (ServerLevel) p.level();
        HitResult hit = p.pick(24.0, 1.0f, false);
        Vec3 target = hit.getLocation();
        anchor = target.add(0, 3.2, 0);

        // 몬스터(플레이어 제외)
        List<LivingEntity> ents = lv.getEntitiesOfClass(LivingEntity.class,
                new AABB(target, target).inflate(8),
                e -> e != owner && !(e instanceof Player) && e.isAlive());
        for (LivingEntity e : ents) {
            if (bodies.size() >= 20) break;
            bodies.add(new Body(e, rnd, false));
            e.setNoGravity(true);
        }

        // 블록: 노출된 블록만, 최대 36개
        BlockPos center = BlockPos.containing(target.add(p.getLookAngle().scale(0.1)));
        List<BlockPos> cand = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++)
            for (int dy = -4; dy <= 4; dy++)
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx * dx + dy * dy + dz * dz > 17) continue;
                    BlockPos bp = center.offset(dx, dy, dz);
                    BlockState st = lv.getBlockState(bp);
                    if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                    if (st.getDestroySpeed(lv, bp) < 0 || st.hasBlockEntity()) continue;
                    boolean exposed = false;
                    for (Direction d : Direction.values())
                        if (lv.getBlockState(bp.relative(d)).isAir()) { exposed = true; break; }
                    if (exposed) cand.add(bp.immutable());
                }
        Collections.shuffle(cand, rnd);
        for (int i = 0; i < Math.min(36, cand.size()); i++) {
            BlockPos bp = cand.get(i);
            BlockState st = lv.getBlockState(bp);
            if (st.isAir()) continue;
            FallingBlockEntity f = FallingBlockEntity.fall(lv, bp, st);
            f.setNoGravity(true);
            bodies.add(new Body(f, rnd, true));
        }
        Fx.sound(lv, anchor, SoundEvents.AMETHYST_BLOCK_RESONATE, 2.0f, 0.6f);
    }

    boolean launchNow() {
        if (flying) return false;
        launch();
        return true;
    }

    @Override
    public boolean tick() {
        if (!owner.isAlive() || owner.level() != lv) { cancel(); return true; }
        age++;
        if (!flying) {
            if (age >= ORBIT) launch(); else { hold(); return false; }
        }
        return fly();
    }

    private void hold() {
        double ease = Math.min(1.0, age / (double) LIFT);
        ease = ease * ease * (3 - 2 * ease);
        double spin = 0.05 * age + 0.0014 * age * age; // 점점 빨라지는 회전

        for (Body b : bodies) {
            if (!b.e.isAlive()) continue;
            double a = b.ang + spin;
            Vec3 want = anchor.add(
                    Math.cos(a) * b.rad * ease,
                    b.hgt * ease + Math.sin(age * 0.2 + b.ang) * 0.3 * ease,
                    Math.sin(a) * b.rad * ease);
            Vec3 v = want.subtract(b.e.position()).scale(0.35);
            if (v.length() > 1.4) v = v.normalize().scale(1.4);
            b.e.setDeltaMovement(v);
            b.e.hurtMarked = true;
            b.e.resetFallDistance();
            if (b.e instanceof FallingBlockEntity f) f.time = 1; // 수명 만료 방지

            Vec3 mid = b.e.position().add(0, b.e.getBbHeight() / 2, 0);
            Fx.p(lv, ParticleTypes.END_ROD, mid, 1, 0.1, 0);
            if (age % 3 == 0) Fx.p(lv, ParticleTypes.ENCHANT, mid, 2, 0.3, 0.1);
        }

        // 중력장 소용돌이
        for (int arm = 0; arm < 3; arm++)
            for (int j = 0; j < 6; j++) {
                double rr = 1 + j * 1.1;
                double a = age * 0.3 - j * 0.5 + arm * Fx.TAU / 3;
                Fx.p(lv, ParticleTypes.REVERSE_PORTAL,
                        anchor.add(Math.cos(a) * rr, Math.sin(a * 2) * 0.3, Math.sin(a) * rr), 1, 0, 0);
            }
        Fx.ring(lv, ParticleTypes.PORTAL, anchor, 1.5 + ease * 3, 20, age * 0.2);
    }

    private void launch() {
        flying = true;
        flyAge = 0;
        Vec3 dir = owner.getLookAngle().normalize();
        for (Body b : bodies) {
            if (!b.e.isAlive()) continue;
            b.e.setNoGravity(b.noGrav);
            Vec3 v = dir.scale(2.0 + rnd.nextDouble() * 0.6)
                    .add((rnd.nextDouble() - 0.5) * 0.25, (rnd.nextDouble() - 0.5) * 0.2, (rnd.nextDouble() - 0.5) * 0.25);
            b.e.setDeltaMovement(v);
            b.e.hurtMarked = true;
            if (b.e instanceof FallingBlockEntity f) f.setHurtsEntities(3.0f, 60);
        }
        Fx.p(lv, ParticleTypes.EXPLOSION_EMITTER, anchor, 1, 0, 0);
        Fx.sound(lv, anchor, SoundEvents.TRIDENT_THUNDER.value(), 1.5f, 1.2f);
    }

    private boolean fly() {
        flyAge++;
        Iterator<Body> it = bodies.iterator();
        while (it.hasNext()) {
            Body b = it.next();
            if (!b.e.isAlive()) { burst(b.last); it.remove(); continue; }
            b.last = b.e.position();
            Vec3 mid = b.last.add(0, b.e.getBbHeight() / 2, 0);
            Fx.p(lv, ParticleTypes.SOUL_FIRE_FLAME, mid, 2, 0.15, 0.02);
            Fx.p(lv, ParticleTypes.END_ROD, mid, 1, 0.05, 0);
            if (b.e instanceof LivingEntity && flyAge > 3 && (b.e.onGround() || b.e.horizontalCollision)) {
                burst(b.e.position());
                it.remove();
            }
        }
        if (flyAge >= FLIGHT || bodies.isEmpty()) { cancel(); return true; }
        return false;
    }

    private void burst(Vec3 p) {
        if (p == null) return;
        Fx.p(lv, ParticleTypes.EXPLOSION, p, 1, 0, 0);
        Fx.p(lv, ParticleTypes.CRIT, p, 25, 0.6, 0.4);
        Fx.p(lv, ParticleTypes.END_ROD, p, 15, 0.5, 0.2);
        Fx.sound(lv, p, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f, 1.6f);
        for (LivingEntity e : lv.getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(3),
                x -> x != owner && x.isAlive())) {
            Fx.hurt(lv, owner, e, 6.0f);
            Fx.knock(e, e.position().subtract(p), 0.8, 0.4);
        }
    }

    @Override
    public void cancel() {
        for (Body b : bodies) if (b.e.isAlive()) b.e.setNoGravity(b.noGrav);
        bodies.clear();
    }

    @Override public UUID ownerId() { return owner.getUUID(); }
}

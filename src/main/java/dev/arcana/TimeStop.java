package dev.arcana;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 시계 우클릭: 반경 내 몬스터/투사체 정지. 정지 중 받은 피해는 저장되었다가 해제 순간 한꺼번에 폭발. */
final class TimeStop implements Ability {
    private static final double R = 14.0;
    private static final int DURATION = 100;

    private static final class Frozen {
        final Entity e;
        final Vec3 vel;
        final boolean noGrav, noAi;
        float stored;
        Frozen(Entity e) {
            this.e = e;
            this.vel = e.getDeltaMovement();
            this.noGrav = e.isNoGravity();
            this.noAi = e instanceof Mob m && m.isNoAi();
        }
    }

    private final ServerLevel lv;
    private final ServerPlayer owner;
    private final Vec3 center;
    private final Map<Integer, Frozen> frozen = new HashMap<>();
    private boolean released;
    private int age;

    TimeStop(ServerPlayer p) {
        owner = p;
        lv = (ServerLevel) p.level();
        center = p.position();
        Fx.sound(lv, center, SoundEvents.BEACON_ACTIVATE, 2.0f, 0.5f);
        Fx.sound(lv, center, SoundEvents.AMETHYST_BLOCK_RESONATE, 2.0f, 0.5f);
        capture();
    }

    /** 정지 중 받은 피해를 가로채 저장. true면 피해 취소. */
    static boolean absorb(Entity victim, float amount) {
        for (Ability a : ArcanaMod.ACTIVE) {
            if (a instanceof TimeStop ts && !ts.released) {
                Frozen f = ts.frozen.get(victim.getId());
                if (f != null && victim instanceof LivingEntity) {
                    f.stored += amount;
                    Fx.p(ts.lv, ParticleTypes.CRIT, victim.position().add(0, victim.getBbHeight() / 2, 0), 8, 0.4, 0.3);
                    return true;
                }
            }
        }
        return false;
    }

    private void capture() {
        AABB box = new AABB(center, center).inflate(R);
        for (Entity e : lv.getEntitiesOfClass(Entity.class, box,
                x -> x != owner && !(x instanceof Player) && (x instanceof Mob || x instanceof Projectile))) {
            if (frozen.containsKey(e.getId()) || e.distanceToSqr(center) > R * R) continue;
            Frozen f = new Frozen(e);
            frozen.put(e.getId(), f);
            if (e instanceof Mob m) m.setNoAi(true);
            e.setNoGravity(true);
        }
    }

    @Override
    public boolean tick() {
        if (!owner.isAlive() || owner.level() != lv || age >= DURATION) { release(); return true; }
        age++;
        if (age % 2 == 0) capture();

        frozen.values().removeIf(f -> f.e.isRemoved());
        for (Frozen f : frozen.values()) {
            f.e.setDeltaMovement(Vec3.ZERO);
            if (age % 4 == 0)
                Fx.p(lv, ParticleTypes.ELECTRIC_SPARK, f.e.position().add(0, f.e.getBbHeight() / 2, 0), 2, 0.35, 0.02);
        }
        visuals();
        return false;
    }

    private void visuals() {
        Vec3 g = center.add(0, 0.1, 0);

        // 시작 충격파: 8틱 동안 퍼지는 링 + 돔
        if (age <= 8) {
            double rr = R * age / 8.0;
            Fx.ring(lv, ParticleTypes.END_ROD, g, rr, 80, 0);
            for (int i = 0; i < 40; i++) {
                double th = Fx.TAU * lv.random.nextDouble();
                double ph = Math.acos(lv.random.nextDouble());
                Fx.p(lv, ParticleTypes.END_ROD, center.add(
                        Math.sin(ph) * Math.cos(th) * rr, Math.cos(ph) * rr, Math.sin(ph) * Math.sin(th) * rr), 1, 0, 0);
            }
        }

        // 시계 문자판 + 12개 눈금
        if (age % 4 == 0) {
            Fx.ring(lv, ParticleTypes.END_ROD, g, R, 56, 0);
            for (int i = 0; i < 12; i++) {
                double a = Fx.TAU * i / 12;
                for (int k = 0; k < 3; k++)
                    Fx.p(lv, ParticleTypes.END_ROD,
                            g.add(Math.cos(a) * (R - 0.4 - k * 0.5), 0, Math.sin(a) * (R - 0.4 - k * 0.5)), 1, 0, 0);
            }
        }

        // 초침(빠름) / 시침(느림)
        double sec = -Fx.TAU * age / 20.0;
        double hr = -Fx.TAU * age / (DURATION * 2.0);
        for (double d = 1; d < R; d += 0.8)
            Fx.p(lv, ParticleTypes.ELECTRIC_SPARK, g.add(Math.cos(sec) * d, 0, Math.sin(sec) * d), 1, 0, 0);
        for (double d = 1; d < R * 0.55; d += 0.7)
            Fx.p(lv, ParticleTypes.SOUL_FIRE_FLAME, g.add(Math.cos(hr) * d, 0, Math.sin(hr) * d), 1, 0, 0);

        // 돔 위로 떠다니는 입자
        for (int i = 0; i < 4; i++) {
            double th = Fx.TAU * lv.random.nextDouble();
            double ph = Math.acos(lv.random.nextDouble());
            Fx.p(lv, ParticleTypes.ENCHANT, center.add(
                    Math.sin(ph) * Math.cos(th) * R, Math.cos(ph) * R, Math.sin(ph) * Math.sin(th) * R), 1, 0, 0.1);
        }
    }

    private void release() {
        if (released) return;
        released = true;

        for (Frozen f : frozen.values()) {
            Entity e = f.e;
            if (!e.isAlive()) continue;
            e.setNoGravity(f.noGrav);
            if (e instanceof Mob m) m.setNoAi(f.noAi);

            Vec3 pos = e.position().add(0, e.getBbHeight() / 2, 0);
            Fx.p(lv, ParticleTypes.EXPLOSION, pos, 1, 0, 0);
            Fx.p(lv, ParticleTypes.CRIT, pos, 30, 0.5, 0.5);
            Fx.p(lv, ParticleTypes.END_ROD, pos, 20, 0.4, 0.3);

            if (e instanceof LivingEntity le) {
                Fx.hurt(lv, owner, le, 4.0f + f.stored * 1.5f); // 저장된 피해 x1.5 + 기본 4
                Fx.knock(le, pos.subtract(center), 0.9, 0.5);
            } else {
                e.setDeltaMovement(f.vel.scale(2.5));
                e.hurtMarked = true;
            }
        }
        for (double r = 2; r <= R; r += 2) Fx.ring(lv, ParticleTypes.EXPLOSION, center.add(0, 0.5, 0), r, (int) (r * 5), 0);
        Fx.p(lv, ParticleTypes.EXPLOSION_EMITTER, center, 1, 0, 0);
        Fx.sound(lv, center, SoundEvents.BEACON_DEACTIVATE, 2.0f, 0.6f);
        Fx.sound(lv, center, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.5f, 0.8f);
        frozen.clear();
    }

    @Override public void cancel() { release(); }
    @Override public UUID ownerId() { return owner.getUUID(); }
}

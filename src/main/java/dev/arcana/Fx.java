package dev.arcana;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 파티클/사운드/데미지 보조 함수. */
final class Fx {
    static final double TAU = Math.PI * 2.0;

    private Fx() {}

    static void p(ServerLevel l, ParticleOptions o, Vec3 v, int n, double spread, double speed) {
        l.sendParticles(o, v.x, v.y, v.z, n, spread, spread, spread, speed);
    }

    static void ring(ServerLevel l, ParticleOptions o, Vec3 c, double r, int pts, double phase) {
        for (int i = 0; i < pts; i++) {
            double a = phase + TAU * i / pts;
            p(l, o, c.add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0);
        }
    }

    static void sound(ServerLevel l, Vec3 v, SoundEvent s, float vol, float pitch) {
        l.playSound(null, v.x, v.y, v.z, s, SoundSource.PLAYERS, vol, pitch);
    }

    static void hurt(ServerLevel l, ServerPlayer owner, LivingEntity t, float amt) {
        t.hurtServer(l, l.damageSources().playerAttack(owner), amt);
    }

    static void knock(LivingEntity t, Vec3 dir, double power, double lift) {
        Vec3 d = dir.lengthSqr() < 1e-6 ? new Vec3(0, 0, 0) : dir.normalize();
        t.setDeltaMovement(d.x * power, lift, d.z * power);
        t.hurtMarked = true;
    }
}

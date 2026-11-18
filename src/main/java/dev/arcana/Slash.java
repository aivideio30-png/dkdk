package dev.arcana;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 검 우클릭: 초승달 모양 곡선 검기가 앞으로 날아가며 나선 궤적을 남긴다. */
final class Slash implements Ability {
    private static final int LIFE = 14;
    private static final double BASE_R = 3.0;
    private static final double HALF_ARC = Math.toRadians(70);

    private final ServerLevel lv;
    private final ServerPlayer owner;
    private final Vec3 origin, fwd, side, up;
    private final Set<Integer> hit = new HashSet<>();
    private int age;

    Slash(ServerPlayer p, boolean flip) {
        owner = p;
        lv = (ServerLevel) p.level();
        fwd = p.getLookAngle().normalize();
        origin = p.getEyePosition().add(fwd.scale(0.8)).add(0, -0.3, 0);

        Vec3 s0 = fwd.cross(new Vec3(0, 1, 0));
        if (s0.lengthSqr() < 1e-4) s0 = new Vec3(1, 0, 0);
        s0 = s0.normalize();
        Vec3 u0 = s0.cross(fwd).normalize();

        // 휘두를 때마다 좌/우 대각선으로 기울기(roll)를 바꾼다
        double roll = Math.toRadians((flip ? -1 : 1) * (20 + p.getRandom().nextInt(30)));
        side = s0.scale(Math.cos(roll)).add(u0.scale(Math.sin(roll)));
        up = u0.scale(Math.cos(roll)).subtract(s0.scale(Math.sin(roll)));

        Fx.sound(lv, origin, SoundEvents.PLAYER_ATTACK_SWEEP, 1.2f, 0.8f);
    }

    @Override
    public boolean tick() {
        if (!owner.isAlive() || owner.level() != lv || age >= LIFE) return true;

        double d = 1.2 + 1.7 * age;
        double r = BASE_R * (0.7 + 0.04 * age);
        Vec3 c = origin.add(fwd.scale(d));

        BlockPos bp = BlockPos.containing(c);
        if (!lv.getBlockState(bp).getCollisionShape(lv, bp).isEmpty()) {
            Fx.p(lv, ParticleTypes.EXPLOSION, c, 1, 0, 0);
            Fx.p(lv, ParticleTypes.CRIT, c, 12, 0.6, 0.3);
            return true;
        }

        // 초승달: 바깥 호 + 안쪽 호
        int i = 0;
        for (double a = -HALF_ARC; a <= HALF_ARC; a += 0.1, i++) {
            double lat = Math.sin(a) * r;
            double fw = Math.cos(a) * r * 0.4 - r * 0.4;
            Vec3 outer = c.add(side.scale(lat)).add(fwd.scale(fw));
            Fx.p(lv, ParticleTypes.END_ROD, outer, 1, 0, 0);
            if (i % 2 == 0) {
                Vec3 inner = c.add(side.scale(lat * 0.95)).add(fwd.scale(fw - r * 0.22 * Math.cos(a)));
                Fx.p(lv, ParticleTypes.CRIT, inner, 1, 0, 0);
            }
        }
        if (age % 2 == 0) lv.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 0, 0, 0, 0, 0);

        // 뒤따르는 이중 나선 잔상
        for (int k = 0; k < 9; k++) {
            double phi = age * 0.9 + k * 0.7;
            double rad = 0.35 + 0.05 * k;
            Vec3 tc = origin.add(fwd.scale(d - k * 0.5));
            for (int s = 0; s < 2; s++) {
                double ph = phi + s * Math.PI;
                Vec3 off = side.scale(Math.cos(ph) * rad).add(up.scale(Math.sin(ph) * rad));
                Fx.p(lv, ParticleTypes.ELECTRIC_SPARK, tc.add(off), 1, 0, 0);
            }
        }

        // 판정: 초승달 평면 안에 들어온 대상
        AABB box = new AABB(c, c).inflate(r + 1.0);
        for (LivingEntity e : lv.getEntitiesOfClass(LivingEntity.class, box, x -> x != owner && x.isAlive())) {
            if (hit.contains(e.getId())) continue;
            Vec3 rel = e.getBoundingBox().getCenter().subtract(c);
            double x = rel.dot(side), y = rel.dot(up), z = rel.dot(fwd);
            if (Math.abs(x) <= r && Math.abs(y) < 1.3 && Math.abs(z) < 1.8) {
                hit.add(e.getId());
                Fx.hurt(lv, owner, e, 8.0f);
                e.setDeltaMovement(e.getDeltaMovement().add(fwd.scale(0.9)).add(0, 0.35, 0));
                e.hurtMarked = true;
                Fx.p(lv, ParticleTypes.CRIT, e.getBoundingBox().getCenter(), 20, 0.4, 0.4);
            }
        }
        age++;
        return false;
    }

    @Override public void cancel() {}
    @Override public UUID ownerId() { return owner.getUUID(); }
}

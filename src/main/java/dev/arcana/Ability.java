package dev.arcana;

import java.util.UUID;

/** 서버 틱마다 갱신되는 능력 인스턴스. tick()이 true를 반환하면 종료. */
interface Ability {
    boolean tick();
    void cancel();
    UUID ownerId();
}

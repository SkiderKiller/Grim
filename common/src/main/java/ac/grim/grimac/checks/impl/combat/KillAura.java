// This file was designed and is an original check for GrimAC
// Copyright (C) 2021 DefineOutside
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.checks.type.RotationCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HeadRotation;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import java.util.*;

@CheckData(name = "KillAura", decay = 0.05)
public class KillAura extends Check implements PacketCheck, RotationCheck, PostPredictionCheck {

    private static final int MAX_ATTACK_HISTORY = 100;
    private static final int MAX_ROTATION_HISTORY = 50;
    private static final double SNAP_ROTATION_THRESHOLD = 20.0;
    private static final double PERFECT_ARC_THRESHOLD = 0.98;
    private static final long MIN_HUMAN_REACTION_TIME = 100;

    private final List<AttackData> attackHistory = new ArrayList<>();
    private final Deque<RotationData> rotationHistory = new ArrayDeque<>();
    private final LongList attackIntervals = new LongArrayList();
    private final Map<Integer, EntityAttackStats> entityAttackStats = new HashMap<>();

    private long lastAttackTime = 0;
    private long lastRotationTime = 0;
    private HeadRotation lastRotation = null;
    private HeadRotation attackRotation = null;
    private Vector3d attackPosition = null;

    private double hitRateThreshold;
    private double rotationConsistencyThreshold;
    private double intervalRegularityThreshold;
    private boolean checkCooldownViolations;
    private boolean checkSnapRotations;
    private boolean checkMechanicalRotations;
    private boolean checkAttackPatterns;

    public KillAura(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);

            if (interact.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                return;
            }

            if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR) {
                return;
            }

            PacketEntity entity = player.compensatedEntities.entityMap.get(interact.getEntityId());
            if (entity == null || entity.isDead) {
                return;
            }

            long currentTime = System.currentTimeMillis();
            long interval = 0;

            if (lastAttackTime > 0) {
                interval = currentTime - lastAttackTime;
                attackIntervals.add(interval);
                if (attackIntervals.size() > MAX_ATTACK_HISTORY) {
                    attackIntervals.removeElements(0, attackIntervals.size() - MAX_ATTACK_HISTORY);
                }
            }

            AttackData attackData = new AttackData(
                currentTime,
                interact.getEntityId(),
                new Vector3d(player.x, player.y, player.z),
                new HeadRotation(player.yaw, player.pitch),
                entity.trackedServerPosition.getPos(),
                calculateDistanceToTarget(entity)
            );

            attackHistory.add(attackData);
            if (attackHistory.size() > MAX_ATTACK_HISTORY) {
                attackHistory.remove(0);
            }

            EntityAttackStats stats = entityAttackStats.computeIfAbsent(
                interact.getEntityId(),
                id -> new EntityAttackStats()
            );
            stats.attacks++;

            boolean isValidHit = isValidHit(entity, attackData);
            if (isValidHit) {
                stats.hits++;
            }

            attackRotation = new HeadRotation(player.yaw, player.pitch);
            attackPosition = new Vector3d(player.x, player.y, player.z);

            if (checkCooldownViolations && shouldCheckCooldown() && lastAttackTime > 0) {
                checkCooldownViolation(interval);
            }

            lastAttackTime = currentTime;

            if (checkAttackPatterns && attackHistory.size() >= 10) {
                checkAttackTimingPatterns(attackData, entity);
            }
        }
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        if (player.packetStateData.lastPacketWasTeleport ||
            player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        HeadRotation currentRotation = rotationUpdate.getTo();

        if (lastRotation != null) {
            float deltaYaw = Math.abs(currentRotation.yaw() - lastRotation.yaw());
            float deltaPitch = Math.abs(currentRotation.pitch() - lastRotation.pitch());

            if (deltaYaw > 180) {
                deltaYaw = 360 - deltaYaw;
            }

            double rotationDelta = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
            long timeDelta = currentTime - lastRotationTime;

            RotationData rotationData = new RotationData(
                currentTime,
                currentRotation,
                rotationDelta,
                timeDelta
            );

            rotationHistory.add(rotationData);
            if (rotationHistory.size() > MAX_ROTATION_HISTORY) {
                rotationHistory.removeFirst();
            }

            if (checkSnapRotations && rotationDelta > SNAP_ROTATION_THRESHOLD && timeDelta < 50) {
                if (lastAttackTime > 0 && Math.abs(currentTime - lastAttackTime) < 500) {
                    flagAndAlert("type=snap_rotation, delta=" + String.format("%.2f", rotationDelta) +
                                 ", time=" + timeDelta + "ms");
                }
            }

            if (checkMechanicalRotations && rotationHistory.size() >= 10) {
                checkMechanicalRotationPattern();
            }
        }

        lastRotation = currentRotation;
        lastRotationTime = currentTime;
    }

    @Override
    public void onPredictionComplete(final PredictionComplete complete) {
        if (attackHistory.size() < 20) {
            return;
        }

        long currentTime = System.currentTimeMillis();

        for (EntityAttackStats stats : entityAttackStats.values()) {
            if (stats.attacks >= 10) {
                double hitRate = (double) stats.hits / stats.attacks;

                if (hitRate > hitRateThreshold && hitRate > 0.95) {
                    flagAndAlert("type=high_hit_rate, rate=" + String.format("%.2f", hitRate) +
                                 ", attacks=" + stats.attacks);
                    stats.hits = 0;
                    stats.attacks = 0;
                }
            }
        }

        if (attackIntervals.size() >= 10) {
            double intervalRegularity = calculateIntervalRegularity();

            if (intervalRegularity > intervalRegularityThreshold) {
                flagAndAlert("type=regular_intervals, regularity=" +
                             String.format("%.3f", intervalRegularity));
            }
        }

        entityAttackStats.entrySet().removeIf(entry -> {
            long lastAttackAge = currentTime - lastAttackTime;
            return lastAttackAge > 10000;
        });
    }

    private boolean isValidHit(PacketEntity entity, AttackData attackData) {
        SimpleCollisionBox targetBox = entity.getPossibleCollisionBoxes();

        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
            targetBox.expand(0.1f);
        }

        double maxReach = player.compensatedEntities.self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);

        double[] eyeHeights = player.getPossibleEyeHeights();
        for (double eyeHeight : eyeHeights) {
            Vector3dm eyePos = new Vector3dm(
                attackData.playerPosition.getX(),
                attackData.playerPosition.getY() + eyeHeight,
                attackData.playerPosition.getZ()
            );

            Vector3dm lookVec = ReachUtils.getLook(player, attackData.rotation.yaw(), attackData.rotation.pitch());
            Vector3dm endPos = eyePos.clone().add(
                lookVec.getX() * (maxReach + 3),
                lookVec.getY() * (maxReach + 3),
                lookVec.getZ() * (maxReach + 3)
            );

            Vector3dm intercept = ReachUtils.calculateIntercept(targetBox, eyePos, endPos).first();

            if (intercept != null && eyePos.distance(intercept) <= maxReach) {
                return true;
            }
        }

        return false;
    }

    private double calculateDistanceToTarget(PacketEntity entity) {
        double dx = entity.trackedServerPosition.getPos().getX() - player.x;
        double dy = entity.trackedServerPosition.getPos().getY() - player.y;
        double dz = entity.trackedServerPosition.getPos().getZ() - player.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void checkCooldownViolation(long interval) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
            return;
        }

        double attackSpeed = player.compensatedEntities.self.getAttributeValue(Attributes.ATTACK_SPEED);
        double minCooldown = (1.0 / attackSpeed) * 1000.0;

        if (interval < minCooldown * 0.8) {
            flagAndAlert("type=cooldown_violation, interval=" + interval + "ms, expected=" +
                         String.format("%.0f", minCooldown) + "ms");
        }
    }

    private void checkAttackTimingPatterns(AttackData currentAttack, PacketEntity entity) {
        if (attackHistory.size() < 2) {
            return;
        }

        List<AttackData> recentAttacks = attackHistory.subList(
            Math.max(0, attackHistory.size() - 10),
            attackHistory.size()
        );

        int attacksOnMovingTarget = 0;
        int attacksOnStationaryTarget = 0;

        for (int i = 1; i < recentAttacks.size(); i++) {
            AttackData prevAttack = recentAttacks.get(i - 1);
            AttackData currAttack = recentAttacks.get(i);

            if (prevAttack.entityId == currAttack.entityId) {
                double dx = currAttack.targetPosition.getX() - prevAttack.targetPosition.getX();
                double dy = currAttack.targetPosition.getY() - prevAttack.targetPosition.getY();
                double dz = currAttack.targetPosition.getZ() - prevAttack.targetPosition.getZ();
                double distanceMoved = Math.sqrt(dx * dx + dy * dy + dz * dz);

                if (distanceMoved > 0.1) {
                    attacksOnMovingTarget++;
                } else {
                    attacksOnStationaryTarget++;
                }
            }
        }

        if (attacksOnStationaryTarget >= 7 && attacksOnMovingTarget <= 2) {
            flagAndAlert("type=stationary_preference, stationary=" + attacksOnStationaryTarget +
                         ", moving=" + attacksOnMovingTarget);
        }
    }

    private void checkMechanicalRotationPattern() {
        if (rotationHistory.size() < 10) {
            return;
        }

        List<RotationData> recentRotations = new ArrayList<>(rotationHistory);
        if (recentRotations.size() > 20) {
            recentRotations = recentRotations.subList(recentRotations.size() - 20, recentRotations.size());
        }

        double totalDelta = 0;
        double maxDeviation = 0;
        int validRotations = 0;

        for (RotationData rotation : recentRotations) {
            if (rotation.delta > 0.1) {
                totalDelta += rotation.delta;
                validRotations++;
            }
        }

        if (validRotations < 5) {
            return;
        }

        double avgDelta = totalDelta / validRotations;

        for (RotationData rotation : recentRotations) {
            if (rotation.delta > 0.1) {
                double deviation = Math.abs(rotation.delta - avgDelta);
                maxDeviation = Math.max(maxDeviation, deviation);
            }
        }

        double consistency = 1.0 - (maxDeviation / avgDelta);

        if (consistency > rotationConsistencyThreshold) {
            List<Double> accelerations = calculateRotationAccelerations(recentRotations);
            double avgAcceleration = accelerations.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);

            if (Math.abs(avgAcceleration) < 0.1) {
                flagAndAlert("type=mechanical_rotation, consistency=" +
                             String.format("%.3f", consistency) +
                             ", avg_accel=" + String.format("%.3f", avgAcceleration));
            }
        }
    }

    private List<Double> calculateRotationAccelerations(List<RotationData> rotations) {
        List<Double> accelerations = new ArrayList<>();

        for (int i = 2; i < rotations.size(); i++) {
            RotationData prev = rotations.get(i - 1);
            RotationData curr = rotations.get(i);

            if (prev.timeDelta > 0 && curr.timeDelta > 0) {
                double prevVelocity = prev.delta / prev.timeDelta;
                double currVelocity = curr.delta / curr.timeDelta;
                double acceleration = currVelocity - prevVelocity;
                accelerations.add(acceleration);
            }
        }

        return accelerations;
    }

    private double calculateIntervalRegularity() {
        if (attackIntervals.size() < 5) {
            return 0;
        }

        List<Long> recentIntervals = new ArrayList<>();
        for (int i = Math.max(0, attackIntervals.size() - 20); i < attackIntervals.size(); i++) {
            recentIntervals.add(attackIntervals.getLong(i));
        }

        double mean = recentIntervals.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0);

        double variance = recentIntervals.stream()
            .mapToDouble(interval -> Math.pow(interval - mean, 2))
            .average()
            .orElse(0);

        double stdDev = Math.sqrt(variance);

        if (mean < 10) {
            return 0;
        }

        double coefficientOfVariation = stdDev / mean;

        return 1.0 - coefficientOfVariation;
    }

    private boolean shouldCheckCooldown() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);
    }

    @Override
    public void onReload(ConfigManager config) {
        this.hitRateThreshold = config.getDoubleElse("KillAura.hit-rate-threshold", 0.90);
        this.rotationConsistencyThreshold = config.getDoubleElse("KillAura.rotation-consistency-threshold", 0.95);
        this.intervalRegularityThreshold = config.getDoubleElse("KillAura.interval-regularity-threshold", 0.85);
        this.checkCooldownViolations = config.getBooleanElse("KillAura.check-cooldown-violations", true);
        this.checkSnapRotations = config.getBooleanElse("KillAura.check-snap-rotations", true);
        this.checkMechanicalRotations = config.getBooleanElse("KillAura.check-mechanical-rotations", true);
        this.checkAttackPatterns = config.getBooleanElse("KillAura.check-attack-patterns", true);
    }

    private static class AttackData {
        final long timestamp;
        final int entityId;
        final Vector3d playerPosition;
        final HeadRotation rotation;
        final Vector3d targetPosition;
        final double distance;

        AttackData(long timestamp, int entityId, Vector3d playerPosition,
                   HeadRotation rotation, Vector3d targetPosition, double distance) {
            this.timestamp = timestamp;
            this.entityId = entityId;
            this.playerPosition = playerPosition;
            this.rotation = rotation;
            this.targetPosition = targetPosition;
            this.distance = distance;
        }
    }

    private static class RotationData {
        final long timestamp;
        final HeadRotation rotation;
        final double delta;
        final long timeDelta;

        RotationData(long timestamp, HeadRotation rotation, double delta, long timeDelta) {
            this.timestamp = timestamp;
            this.rotation = rotation;
            this.delta = delta;
            this.timeDelta = timeDelta;
        }
    }

    private static class EntityAttackStats {
        int attacks = 0;
        int hits = 0;
    }
}

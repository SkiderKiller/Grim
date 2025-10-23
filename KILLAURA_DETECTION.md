# KillAura Detection System

## Overview

This comprehensive KillAura detection check has been added to GrimAC to detect various forms of combat automation and assistance. The check analyzes multiple aspects of player combat behavior including attack patterns, rotation mechanics, timing consistency, and hit accuracy.

## Features

### 1. Long-term Hit Rate Tracking
- Monitors the ratio of successful hits to total attacks per entity
- Detects abnormally high accuracy that exceeds human capability
- Configurable threshold (default: 90%)

### 2. Attack Timing Pattern Analysis
- Analyzes correlation between attacks and target movement patterns
- Detects if players only attack stationary targets (predictable behavior)
- Identifies suspicious timing that suggests automated target selection

### 3. Snap Rotation Detection
- Identifies instant turns to target (rotation delta > 20° in < 50ms)
- Detects rotations that occur suspiciously close to attack events
- Catches "silent aim" or "aim assist" that snaps to targets

### 4. Mechanical Rotation Pattern Detection
- Analyzes rotation consistency over time
- Detects perfect arcs and constant rotation speeds (inhuman patterns)
- Calculates rotation acceleration to identify mechanical movement
- Configurable consistency threshold (default: 95%)

### 5. Attack Interval Regularity Monitoring
- Tracks time intervals between attacks
- Calculates coefficient of variation to detect robotic patterns
- Identifies automated clicking with regular intervals
- Configurable regularity threshold (default: 85%)

### 6. Attack Cooldown Validation (1.9+)
- Verifies attacks respect the attack speed attribute
- Detects violations where attacks occur faster than cooldown allows
- Only active on 1.9+ clients where attack cooldown exists

### 7. View Direction Validation
- Validates that player's yaw/pitch actually points toward attacked entity
- Uses raycasting to verify line of sight to target
- Accounts for hitbox expansion and client version differences
- Considers latency and position interpolation

### 8. Rotation Acceleration Analysis
- Calculates velocity changes in rotation over time
- Detects unnatural acceleration patterns
- Identifies constant acceleration (bot characteristic)

## Configuration

All detection methods can be individually configured in `config.yml`:

```yaml
KillAura:
    # Threshold for hit rate detection (0.0-1.0)
    hit-rate-threshold: 0.90
    
    # Threshold for rotation consistency detection (0.0-1.0)
    rotation-consistency-threshold: 0.95
    
    # Threshold for attack interval regularity (0.0-1.0)
    interval-regularity-threshold: 0.85
    
    # Check for violations of attack cooldown timing (1.9+ versions only)
    check-cooldown-violations: true
    
    # Check for snap rotations (instant turns to target)
    check-snap-rotations: true
    
    # Check for mechanical rotation patterns (perfect arcs, constant speeds)
    check-mechanical-rotations: true
    
    # Check for suspicious attack timing patterns
    check-attack-patterns: true
```

## Implementation Details

### Architecture
- Extends `Check` base class
- Implements `PacketCheck` to monitor INTERACT_ENTITY packets
- Implements `RotationCheck` to process rotation updates
- Implements `PostPredictionCheck` for periodic analysis

### Data Structures
- `AttackData`: Records timestamp, positions, rotations, and distances for each attack
- `RotationData`: Tracks rotation changes, deltas, and timing
- `EntityAttackStats`: Maintains hit/miss statistics per entity
- Limited history sizes to prevent memory issues (100 attacks, 50 rotations)

### Detection Methods

#### Hit Rate Analysis
```
hitRate = hits / totalAttacks
if hitRate > threshold && totalAttacks >= 10:
    flag()
```

#### Rotation Consistency
```
consistency = 1.0 - (maxDeviation / avgDelta)
if consistency > threshold && avgAcceleration ≈ 0:
    flag()
```

#### Interval Regularity
```
coefficientOfVariation = stdDev / mean
regularity = 1.0 - coefficientOfVariation
if regularity > threshold:
    flag()
```

## Alert Types

The check generates detailed verbose information for each detection:

- `type=high_hit_rate`: Player has abnormally high hit accuracy
- `type=snap_rotation`: Instant rotation to target detected
- `type=mechanical_rotation`: Consistent/automated rotation pattern
- `type=regular_intervals`: Attack timing is too regular
- `type=cooldown_violation`: Attack occurred before cooldown expired
- `type=stationary_preference`: Player only attacks non-moving targets

## Punishment Configuration

KillAura is integrated into the Combat punishment group in `punishments.yml`:

```yaml
Combat:
    remove-violations-after: 300
    checks:
      - "Interact"
      - "Aim"
      - "KillAura"
    commands:
      - "20:40 [alert]"
      - "20:40 [log]"
```

## Performance Considerations

- Efficient data structures (FastUtil collections)
- Limited history sizes to cap memory usage
- Analysis only triggered when sufficient data is available
- Old data automatically expired after 10 seconds of inactivity

## False Positive Mitigation

- Exempts spectators and creative mode players
- Ignores dead entities
- Accounts for client version differences
- Considers network latency and position interpolation
- Uses statistical analysis with configurable thresholds
- Requires multiple violations before alerting

## Testing Recommendations

1. Test with various client versions (1.7, 1.8, 1.9+)
2. Verify legitimate PvP doesn't trigger false positives
3. Test with different ping ranges (0-200ms)
4. Adjust thresholds based on your server's player skill level
5. Monitor alerts for the first few days and tune thresholds

## Future Enhancements

Potential improvements for future versions:
- Machine learning-based pattern recognition
- Cross-reference with other combat checks (Reach, Aim)
- Adaptive thresholds based on player history
- Heat map visualization of attack patterns
- Integration with replay/spectate systems

## Credits

Designed and implemented for GrimAC under GPLv3 license.
Copyright © 2025 DefineOutside and contributors.

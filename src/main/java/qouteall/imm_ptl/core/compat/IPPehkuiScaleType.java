/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import virtuoel.pehkui.api.ScaleData;
import virtuoel.pehkui.api.ScaleModifier;
import virtuoel.pehkui.api.ScaleModifiers;
import virtuoel.pehkui.api.ScaleRegistries;
import virtuoel.pehkui.api.ScaleType;
import virtuoel.pehkui.api.ScaleTypes;
import virtuoel.pehkui.api.TypedScaleModifier;

/**
 * The Pehkui side of {@link IPPehkuiScaling}: our scale type {@code iportal:scaling} and the modifier of the same id
 * that multiplies it into {@code pehkui:base} (Pehkui 3.8.3's API, read with javap and a decompile). Loaded only when
 * Pehkui is installed.
 */
final class IPPehkuiScaleType {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("iportal", "scaling");

    private static ScaleType type;

    private IPPehkuiScaleType() {
    }

    static void register() {
        // registered before it goes into any set: a modifier's order among equal priorities is by its registry id
        ScaleModifier modifier = ScaleRegistries.register(
            ScaleRegistries.SCALE_MODIFIERS, ID, new TypedScaleModifier(() -> type)
        );
        ScaleType.Builder builder = ScaleType.Builder.create()
            // a change refreshes the entity's dimensions at once, so a crossing's new eye height is there for the
            // eye-position fix that follows (ScaleUtils.doScalingForEntity)
            .affectsDimensions()
            // not kept on death, as vanilla keeps only base attribute values (ServerPlayer.restoreFrom): a player
            // who dies small respawns at normal size. Saved with the world either way.
            .defaultPersistence(false)
            .addDependentModifier(modifier);
        // A change invalidates the cached values of every type built on these, not only BASE's: the server caches a
        // non-player's scales (and a player's that do not affect dimensions) until the next tick, which would leave a
        // mob's new hitbox, or a player's speed and reach, a tick behind the crossing
        for (ScaleModifier dependent : new ScaleModifier[]{
            ScaleModifiers.BASE_MULTIPLIER, ScaleModifiers.BASE_DIVISOR,
            ScaleModifiers.WIDTH_MULTIPLIER, ScaleModifiers.HEIGHT_MULTIPLIER,
            ScaleModifiers.MOTION_MULTIPLIER, ScaleModifiers.MOTION_DIVISOR,
            ScaleModifiers.REACH_MULTIPLIER
        }) {
            builder.addDependentModifier(dependent);
        }
        ScaleType built = ScaleRegistries.register(ScaleRegistries.SCALE_TYPES, ID, builder.build());
        // fired from setBaseScale, before setScale sets the target: the base value is the new one
        built.getScaleChangedEvent().add(data -> IPPehkuiScaling.mirror(data.getEntity(), data.getBaseScale()));
        type = built;
        // before any entity exists: a ScaleData copies its type's default modifiers when it is created
        ScaleTypes.BASE.getDefaultBaseValueModifiers().add(modifier);
    }

    static double get(Entity entity) {
        // the target: when someone eases this scale (/scale set ... with a delay), a crossing multiplies where it ends
        return type.getScaleData(entity).getTargetScale();
    }

    static void set(Entity entity, double scale) {
        ScaleData data = type.getScaleData(entity);
        data.setScale((float) scale);
        // a second time, as Pym does: the first call leaves the easing's start at the old scale, so a frame drawn
        // before the next tick would interpolate from it
        data.setTargetScale((float) scale);
    }

    static double base(Entity entity) {
        return ScaleTypes.BASE.getScaleData(entity).getScale();
    }

    static double thirdPerson(Entity entity) {
        return ScaleTypes.THIRD_PERSON.getScaleData(entity).getScale();
    }

    static double blockReach(Entity entity) {
        return ScaleTypes.BLOCK_REACH.getScaleData(entity).getScale();
    }

    static double motion(Entity entity) {
        return ScaleTypes.MOTION.getScaleData(entity).getScale();
    }
}

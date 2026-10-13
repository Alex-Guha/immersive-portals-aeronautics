/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.entity.LevelEntityGetter;
import qouteall.imm_ptl.core.mixin.common.mc_util.IELevelEntityGetterAdapter;

/**
 * Walks a {@link LevelEntityGetter} wrapper chain (any field of a {@code LevelEntityGetter} type, superclasses
 * included) until it finds one implementing IP's {@link IELevelEntityGetterAdapter}, so the cast in
 * {@code McHelper.getEntitiesNearby} survives another mod wrapping the level's entity getter. Returns the
 * original getter when no adapter is found. The candidate fields of each concrete class are discovered by
 * reflection once and cached; only the field reads run per query.
 */
public final class McHelperPatchHelper {
    private static final Field[] NO_FIELDS = new Field[0];
    /** Per concrete class: the accessible {@code LevelEntityGetter}-typed fields, own class first, then supers. */
    private static final Map<Class<?>, Field[]> CANDIDATE_FIELDS = new ConcurrentHashMap<>();

    private McHelperPatchHelper() {
    }

    public static LevelEntityGetter unwrapGetter(LevelEntityGetter getter) {
        Object current = getter;
        HashSet<Object> visited = new HashSet<>();

        while (current != null && visited.add(current)) {
            if (current instanceof IELevelEntityGetterAdapter) {
                return (LevelEntityGetter) current;
            }

            current = firstWrappedGetter(current);
        }

        return getter;
    }

    /** The first non-null {@code LevelEntityGetter} field value of {@code current} other than itself, or null. */
    private static Object firstWrappedGetter(Object current) {
        for (Field field : candidateFieldsOf(current.getClass())) {
            try {
                Object value = field.get(current);
                if (value != null && value != current) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static Field[] candidateFieldsOf(Class<?> concreteClass) {
        Field[] cached = CANDIDATE_FIELDS.get(concreteClass);
        if (cached != null) {
            return cached;
        }

        ArrayList<Field> fields = new ArrayList<>();

        for (Class<?> cls = concreteClass; cls != null; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (LevelEntityGetter.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        fields.add(field);
                    } catch (Throwable ignored) {
                        // inaccessible field: skipped, exactly as the uncached walk did
                    }
                }
            }
        }

        Field[] result = fields.isEmpty() ? NO_FIELDS : fields.toArray(new Field[0]);
        CANDIDATE_FIELDS.put(concreteClass, result);
        return result;
    }
}

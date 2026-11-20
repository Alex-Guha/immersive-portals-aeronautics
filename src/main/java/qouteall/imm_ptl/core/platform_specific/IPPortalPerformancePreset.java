/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.platform_specific;

import qouteall.imm_ptl.core.IPGlobal;

/**
 * The client option {@code portalPerformancePreset}: sets the six portal view options together. While it is not
 * {@link #custom}, {@link IPConfig#onConfigChanged} writes its values over the options (so every reader of them sees
 * the preset), and the config screen greys them out. Custom's own values are kept only while the screen is open
 * ({@code IPConfigScreen.customValues}); otherwise it starts from {@link #defaultFor}.
 * <p>
 * The draw distances keep the order of the presets under a shader pack too: high's 0 is 100 blocks there
 * ({@code IPPortalDrawDistance.SHADER_PACK_DEFAULT}), so medium stays under it at 6 chunks.
 */
public enum IPPortalPerformancePreset {
    custom(0, 0, 0, 0, false, IPGlobal.PortalViewShadows.full),
    low(1, 4, 4, 120, true, IPGlobal.PortalViewShadows.off),
    medium(2, 6, 8, 90, true, IPGlobal.PortalViewShadows.terrainOnly),
    high(5, 0, 0, 60, false, IPGlobal.PortalViewShadows.full),
    unlimited(10, 64, 32, 0, false, IPGlobal.PortalViewShadows.full);

    /** The options a preset sets, as the config's field names. */
    public static final java.util.Set<String> FIELDS = java.util.Set.of(
        "maxPortalLayer", "portalDrawDistance", "portalViewDistance", "portalFrameRateTarget",
        "simpleNestedPortalViews", "portalViewShadows"
    );

    public final int maxPortalLayer;
    public final int portalDrawDistance;
    public final int portalViewDistance;
    public final int portalFrameRateTarget;
    public final boolean simpleNestedPortalViews;
    public final IPGlobal.PortalViewShadows portalViewShadows;

    IPPortalPerformancePreset(
        int maxPortalLayer, int portalDrawDistance, int portalViewDistance, int portalFrameRateTarget,
        boolean simpleNestedPortalViews, IPGlobal.PortalViewShadows portalViewShadows
    ) {
        this.maxPortalLayer = maxPortalLayer;
        this.portalDrawDistance = portalDrawDistance;
        this.portalViewDistance = portalViewDistance;
        this.portalFrameRateTarget = portalFrameRateTarget;
        this.simpleNestedPortalViews = simpleNestedPortalViews;
        this.portalViewShadows = portalViewShadows;
    }

    /** The preset's value for one of {@link #FIELDS}. */
    public Object valueFor(String field) {
        return switch (field) {
            case "maxPortalLayer" -> maxPortalLayer;
            case "portalDrawDistance" -> portalDrawDistance;
            case "portalViewDistance" -> portalViewDistance;
            case "portalFrameRateTarget" -> portalFrameRateTarget;
            case "simpleNestedPortalViews" -> simpleNestedPortalViews;
            case "portalViewShadows" -> portalViewShadows;
            default -> throw new IllegalArgumentException(field);
        };
    }

    /** A new config's value for one of {@link #FIELDS}: what Custom starts from. */
    public static Object defaultFor(String field) {
        IPConfig defaults = new IPConfig();
        return switch (field) {
            case "maxPortalLayer" -> defaults.maxPortalLayer;
            case "portalDrawDistance" -> defaults.portalDrawDistance;
            case "portalViewDistance" -> defaults.portalViewDistance;
            case "portalFrameRateTarget" -> defaults.portalFrameRateTarget;
            case "simpleNestedPortalViews" -> defaults.simpleNestedPortalViews;
            case "portalViewShadows" -> defaults.portalViewShadows;
            default -> throw new IllegalArgumentException(field);
        };
    }

    /** Writes the preset's values over the options; {@link #custom} leaves them as they are. */
    public void applyTo(IPConfig config) {
        if (this == custom) {
            return;
        }
        config.maxPortalLayer = maxPortalLayer;
        config.portalDrawDistance = portalDrawDistance;
        config.portalViewDistance = portalViewDistance;
        config.portalFrameRateTarget = portalFrameRateTarget;
        config.simpleNestedPortalViews = simpleNestedPortalViews;
        config.portalViewShadows = portalViewShadows;
    }
}

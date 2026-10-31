/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.platform_specific;

import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import qouteall.imm_ptl.core.IPGlobal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The config screen behind the Config button of our Mods list entry (replaces Cloth Config's generated screen, which
 * only {@code /imm_ptl_client_debug config} opened). It shows the options Cloth showed, with their bounds and the
 * {@code text.autoconfig.immersive_portals.*} lang keys. Changes apply on Done (or Escape) and are saved to
 * {@code config/immersive_portals.json}. Every option applies at once except those in {@link #ON_WORLD_LOAD}, which
 * are marked; changing one inside a world shows a toast asking to rejoin.
 */
public class IPConfigScreen extends OptionsSubScreen {
    private static final String KEY = "text.autoconfig.immersive_portals.";

    /** Options the game reads only when a world loads or a server is joined. */
    private static final Set<String> ON_WORLD_LOAD = Set.of(
        "clientTolerantVersionMismatchWithServer", // the version check on joining (ImmPtlNetworkConfig)
        "enableDatapackPortalGen" // read when the server starts (CustomPortalGenManager)
    );

    private final List<Edit<?>> edits = new ArrayList<>();

    public IPConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable(KEY + "title"));
    }

    /**
     * One option on the screen: the widget starts at the config's value, clamped into the widget's range, and the
     * value goes back into the config on Done only if the player changed it.
     */
    private record Edit<T>(String field, OptionInstance<T> instance, T shown, BiConsumer<IPConfig, T> setter) {
        boolean changed() {
            return !Objects.equals(instance.get(), shown);
        }
    }

    /** {@link OptionsList} with full-width text rows for the headings. */
    private static class Rows extends OptionsList {
        private final OptionsSubScreen screen;

        Rows(Minecraft minecraft, int width, OptionsSubScreen screen) {
            super(minecraft, width, screen);
            this.screen = screen;
        }

        void addText(AbstractWidget widget) {
            addEntry(Entry.big(List.of(widget), screen));
        }
    }

    @Override
    protected void addContents() {
        list = layout.addToContents(new Rows(minecraft, width, this));
        addOptions();
    }

    @Override
    protected void addOptions() {
        IPConfig c = IPConfig.getConfig();

        heading("performance");
        range("maxPortalLayer", 0, 10, c.maxPortalLayer, (o, v) -> o.maxPortalLayer = v);
        range("portalViewDistance", 0, 32, c.portalViewDistance, (o, v) -> o.portalViewDistance = v);
        range("dimensionStackViewDistance", 0, 256, c.dimensionStackViewDistance,
            (o, v) -> o.dimensionStackViewDistance = v);
        bool("reducedPortalRendering", c.reducedPortalRendering, (o, v) -> o.reducedPortalRendering = v);
        bool("lagAttackProof", c.lagAttackProof, (o, v) -> o.lagAttackProof = v);
        bool("enableClientPerformanceAdjustment", c.enableClientPerformanceAdjustment,
            (o, v) -> o.enableClientPerformanceAdjustment = v);
        worldNote();
        bool("enableServerPerformanceAdjustment", c.enableServerPerformanceAdjustment,
            (o, v) -> o.enableServerPerformanceAdjustment = v);
        range("indirectLoadingRadiusCap", 1, 32, c.indirectLoadingRadiusCap, (o, v) -> o.indirectLoadingRadiusCap = v);

        heading("visuals");
        bool("renderYourselfInPortal", c.renderYourselfInPortal, (o, v) -> o.renderYourselfInPortal = v);
        bool("correctCrossPortalEntityRendering", c.correctCrossPortalEntityRendering,
            (o, v) -> o.correctCrossPortalEntityRendering = v);
        bool("pureMirror", c.pureMirror, (o, v) -> o.pureMirror = v);
        bool("netherPortalOverlay", c.netherPortalOverlay, (o, v) -> o.netherPortalOverlay = v);
        bool("enableNetherPortalEffect", c.enableNetherPortalEffect, (o, v) -> o.enableNetherPortalEffect = v);
        bool("enableCrossPortalSound", c.enableCrossPortalSound, (o, v) -> o.enableCrossPortalSound = v);

        heading("portals");
        worldNote();
        choice("netherPortalMode", IPGlobal.NetherPortalMode.class, c.netherPortalMode,
            (o, v) -> o.netherPortalMode = v);
        choice("endPortalMode", IPGlobal.EndPortalMode.class, c.endPortalMode, (o, v) -> o.endPortalMode = v);
        bool("lightVanillaNetherPortalWhenCrouching", c.lightVanillaNetherPortalWhenCrouching,
            (o, v) -> o.lightVanillaNetherPortalWhenCrouching = v);
        range("regularPortalLengthLimit", 3, 64, c.regularPortalLengthLimit, (o, v) -> o.regularPortalLengthLimit = v);
        // the range onConfigChanged clamps to (Cloth's slider started at 4)
        range("scaleLimit", 8, 128, c.scaleLimit, (o, v) -> o.scaleLimit = v);
        bool("enableMirrorCreation", c.enableMirrorCreation, (o, v) -> o.enableMirrorCreation = v);
        bool("portalsChangeGravityByDefault", c.portalsChangeGravityByDefault,
            (o, v) -> o.portalsChangeGravityByDefault = v);
        choice("shipPortalMode", IPGlobal.ShipPortalMode.class, c.shipPortalMode, (o, v) -> o.shipPortalMode = v);
        range("shipPortalRangeChunks", IPConfig.SHIP_PORTAL_RANGE_MIN, IPConfig.SHIP_PORTAL_RANGE_MAX, c.shipPortalRangeChunks,
            (o, v) -> o.shipPortalRangeChunks = v);
        bool("enableDatapackPortalGen", c.enableDatapackPortalGen, (o, v) -> o.enableDatapackPortalGen = v);

        heading("permissions");
        worldNote();
        bool("easeCreativePermission", c.easeCreativePermission, (o, v) -> o.easeCreativePermission = v);
        bool("easeCommandStickPermission", c.easeCommandStickPermission, (o, v) -> o.easeCommandStickPermission = v);
        bool("portalWandUsableOnSurvivalMode", c.portalWandUsableOnSurvivalMode,
            (o, v) -> o.portalWandUsableOnSurvivalMode = v);

        heading("compatibility");
        bool("compatibilityRenderMode", c.compatibilityRenderMode, (o, v) -> o.compatibilityRenderMode = v);
        bool("clientTolerantVersionMismatchWithServer", c.clientTolerantVersionMismatchWithServer,
            (o, v) -> o.clientTolerantVersionMismatchWithServer = v);
        // read on both sides: the client's warnings and the message a server sends
        bool("enableWarning", c.enableWarning, (o, v) -> o.enableWarning = v);

        ((Rows) list).addText(new StringWidget(
            310, 20, Component.translatable(KEY + "onWorldLoad").withStyle(ChatFormatting.GOLD), font
        ));
    }

    private void heading(String category) {
        ((Rows) list).addText(new StringWidget(
            310, 20,
            Component.translatable(KEY + "category." + category).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
            font
        ));
    }

    /** Goes above rows that are the integrated server's options; a dedicated server reads its own file. */
    private void worldNote() {
        MultiLineTextWidget note = new MultiLineTextWidget(Component.translatable(KEY + "worldNote"), font)
            .setMaxWidth(310).setCentered(true);
        note.setColor(ChatFormatting.GRAY.getColor());
        ((Rows) list).addText(note);
    }

    @Override
    public void onClose() {
        if (list != null) {
            list.applyUnsavedChanges();
        }
        applyChanges();
        minecraft.setScreen(lastScreen);
    }

    private void applyChanges() {
        List<Edit<?>> changed = edits.stream().filter(Edit::changed).toList();
        if (changed.isEmpty()) {
            return;
        }
        IPConfig config = IPConfig.getConfig();
        changed.forEach(edit -> apply(edit, config));
        config.saveConfigFile();

        List<Component> needWorldLoad = changed.stream()
            .filter(edit -> ON_WORLD_LOAD.contains(edit.field()))
            .map(edit -> (Component) Component.translatable(optionKey(edit.field())))
            .toList();
        if (!needWorldLoad.isEmpty() && minecraft.level != null) {
            minecraft.getToasts().addToast(SystemToast.multiline(
                minecraft, new SystemToast.SystemToastId(8000L),
                Component.translatable(KEY + "onWorldLoad.toast.title"),
                Component.translatable(KEY + "onWorldLoad.toast", ComponentUtils.formatList(needWorldLoad, Component.literal(", ")))
            ));
        }
    }

    private static <T> void apply(Edit<T> edit, IPConfig config) {
        edit.setter().accept(config, edit.instance().get());
    }

    private static String optionKey(String field) {
        return KEY + "option." + field;
    }

    private <T> void add(String field, OptionInstance<T> instance, BiConsumer<IPConfig, T> setter) {
        edits.add(new Edit<>(field, instance, instance.get(), setter));
        list.addBig(instance);
    }

    /** The lang file's tooltip for the option, if it has one, then the world-load note for a marked option. */
    private static <T> OptionInstance.TooltipSupplier<T> tooltip(String field) {
        String tooltipKey = optionKey(field) + ".@Tooltip";
        MutableComponent text = I18n.exists(tooltipKey) ? Component.translatable(tooltipKey) : null;
        if (ON_WORLD_LOAD.contains(field)) {
            MutableComponent note = Component.translatable(KEY + "onWorldLoad").withStyle(ChatFormatting.GOLD);
            text = text == null ? note : text.append("\n\n").append(note);
        }
        Tooltip result = text == null ? null : Tooltip.create(text);
        return value -> result;
    }

    /** The row's label, with a gold * at its end if the option only applies when a world loads. */
    private static Component marked(String field, Component label) {
        return ON_WORLD_LOAD.contains(field)
            ? label.copy().append(Component.literal("*").withStyle(ChatFormatting.GOLD))
            : label;
    }

    // bool and choice make cycle buttons, which put the option's name before this label themselves; a slider shows
    // its label as it is
    private void bool(String field, boolean value, BiConsumer<IPConfig, Boolean> setter) {
        add(field, new OptionInstance<>(
            optionKey(field), tooltip(field),
            (caption, v) -> marked(field, CommonComponents.optionStatus(v)),
            OptionInstance.BOOLEAN_VALUES, value, v -> {}
        ), setter);
    }

    private void range(String field, int min, int max, int value, BiConsumer<IPConfig, Integer> setter) {
        add(field, new OptionInstance<>(
            optionKey(field), tooltip(field),
            (caption, v) -> marked(field, Options.genericValueLabel(caption, v)),
            new OptionInstance.IntRange(min, max), Mth.clamp(value, min, max), v -> {}
        ), setter);
    }

    private <E extends Enum<E>> void choice(String field, Class<E> type, E value, BiConsumer<IPConfig, E> setter) {
        List<E> values = Arrays.asList(type.getEnumConstants());
        Codec<E> codec = Codec.STRING.xmap(name -> Enum.valueOf(type, name), Enum::name);
        add(field, new OptionInstance<>(
            optionKey(field), tooltip(field),
            (caption, v) -> marked(
                field, Component.translatableWithFallback(optionKey(field) + "." + v.name(), v.name())
            ),
            new OptionInstance.Enum<>(values, codec), value != null ? value : values.get(0), v -> {}
        ), setter);
    }
}

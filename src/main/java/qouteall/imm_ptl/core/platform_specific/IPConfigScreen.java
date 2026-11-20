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
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import qouteall.imm_ptl.core.IPGlobal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The config screen behind the Config button of our Mods list entry (replaces Cloth Config's generated screen, which
 * only {@code /imm_ptl_client_debug config} opened). It shows the options Cloth showed, with their bounds and the
 * {@code text.autoconfig.immersive_portals.*} lang keys. Changes apply on Done (or Escape) and are saved to
 * {@code config/immersive_portals.json}. Every option applies at once except those in {@link #ON_WORLD_LOAD}, which
 * are marked; changing one inside a world shows a toast asking to rejoin.
 * <p>
 * While the Portal Performance Preset is not Custom, the options it sets are greyed out and show its values. A widget
 * does not follow its {@link OptionInstance} once built, so choosing a preset opens a fresh copy of the screen with
 * the values not yet saved ({@link #pending}) and the same scroll position; nothing is saved until Done. Leaving
 * Custom keeps its values ({@link #customValues}) for coming back to it while the screen is open; a screen opened
 * on another preset starts Custom from the defaults.
 */
public class IPConfigScreen extends OptionsSubScreen {
    private static final String KEY = "text.autoconfig.immersive_portals.";

    /** Options the game reads only when a world loads or a server is joined. */
    private static final Set<String> ON_WORLD_LOAD = Set.of(
        "clientTolerantVersionMismatchWithServer", // the version check on joining (ImmPtlNetworkConfig)
        "enableDatapackPortalGen" // read when the server starts (CustomPortalGenManager)
    );

    /** The widest our tooltips wrap (vanilla's 170 made the preset's taller than the screen at common GUI scales). */
    private static final int TOOLTIP_WIDTH = 320;
    /**
     * Below the cursor, moved up only as far as the screen needs, never above the row: vanilla's positioner flips a
     * tooltip that does not fit below to above the row, where a tall one is cut off at the top.
     */
    private static final ClientTooltipPositioner BELOW_CURSOR = (screenWidth, screenHeight, mouseX, mouseY, w, h) -> {
        int x = Mth.clamp(mouseX + 12, 6, Math.max(6, screenWidth - 6 - w));
        int y = Math.max(6, Math.min(mouseY + 16, screenHeight - 6 - h));
        return new Vector2i(x, y);
    };

    private final List<Edit<?>> edits = new ArrayList<>();
    /** The text of each tooltip this screen made, to wrap it wider than {@link Tooltip} does. */
    private final Map<Tooltip, Component> tooltipTexts = new HashMap<>();
    /** By field, the values a previous copy of this screen showed and did not save yet. */
    private final Map<String, Object> pending;
    /** The preset's options as they were when Custom was last left on this screen; null if it was not. */
    private final @Nullable Map<String, Object> customValues;
    private final double scroll;

    public IPConfigScreen(Screen parent) {
        this(parent, Map.of(), null, 0);
    }

    private IPConfigScreen(
        Screen parent, Map<String, Object> pending, @Nullable Map<String, Object> customValues, double scroll
    ) {
        super(parent, Minecraft.getInstance().options, Component.translatable(KEY + "title"));
        this.pending = pending;
        this.customValues = customValues;
        this.scroll = scroll;
    }

    /**
     * One option on the screen: the widget starts at the config's value, clamped into the widget's range (or at the
     * value a previous copy of the screen showed), and the value goes back into the config on Done only if it differs
     * from the config's ({@code shown}).
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
    protected void init() {
        super.init();
        if (scroll > 0 && list != null) {
            list.setScrollAmount(scroll);
        }
    }

    @Override
    protected void addContents() {
        list = layout.addToContents(new Rows(minecraft, width, this));
        addOptions();
        if (preset() != IPPortalPerformancePreset.custom) {
            for (Edit<?> edit : edits) {
                if (IPPortalPerformancePreset.FIELDS.contains(edit.field())) {
                    AbstractWidget widget = list.findOption(edit.instance());
                    if (widget != null) {
                        widget.active = false;
                    }
                }
            }
        }
    }

    @Override
    public void setTooltipForNextRenderPass(Tooltip tooltip, ClientTooltipPositioner positioner, boolean override) {
        Component text = tooltipTexts.get(tooltip);
        if (text == null) {
            super.setTooltipForNextRenderPass(tooltip, positioner, override);
            return;
        }
        int wrap = Math.max(170, Math.min(TOOLTIP_WIDTH, width - 32));
        super.setTooltipForNextRenderPass(font.split(text, wrap), BELOW_CURSOR, override);
    }

    /** The preset the screen shows: the one chosen on a previous copy of it, else the config's. */
    private IPPortalPerformancePreset preset() {
        Object chosen = pending.get("portalPerformancePreset");
        if (chosen instanceof IPPortalPerformancePreset preset) {
            return preset;
        }
        IPPortalPerformancePreset configured = IPConfig.getConfig().portalPerformancePreset;
        return configured != null ? configured : IPPortalPerformancePreset.custom;
    }

    /**
     * A preset was chosen: open a fresh copy of the screen with its values and everything else not yet saved. Custom
     * gets back the values it had when it was left, or the defaults.
     */
    private void presetChanged(IPPortalPerformancePreset preset) {
        list.applyUnsavedChanges();
        Map<String, Object> next = new HashMap<>();
        for (Edit<?> edit : edits) {
            next.put(edit.field(), edit.instance().get());
        }
        Map<String, Object> custom = customValues;
        if (preset() == IPPortalPerformancePreset.custom) {
            custom = new HashMap<>();
            for (String field : IPPortalPerformancePreset.FIELDS) {
                custom.put(field, next.get(field));
            }
        }
        next.put("portalPerformancePreset", preset);
        for (String field : IPPortalPerformancePreset.FIELDS) {
            next.put(field, preset != IPPortalPerformancePreset.custom ? preset.valueFor(field)
                : custom != null ? custom.get(field) : IPPortalPerformancePreset.defaultFor(field));
        }
        minecraft.setScreen(new IPConfigScreen(lastScreen, next, custom, list.getScrollAmount()));
    }

    @Override
    protected void addOptions() {
        IPConfig c = IPConfig.getConfig();

        heading("performance");
        choice("portalPerformancePreset", IPPortalPerformancePreset.class, c.portalPerformancePreset,
            (o, v) -> o.portalPerformancePreset = v, this::presetChanged);
        range("maxPortalLayer", 0, 10, c.maxPortalLayer, (o, v) -> o.maxPortalLayer = v);
        range("portalDrawDistance", 0, 64, c.portalDrawDistance, (o, v) -> o.portalDrawDistance = v);
        range("portalViewDistance", 0, 32, c.portalViewDistance, (o, v) -> o.portalViewDistance = v);
        range("portalFrameRateTarget", 0, 240, c.portalFrameRateTarget, (o, v) -> o.portalFrameRateTarget = v);
        bool("simpleNestedPortalViews", c.simpleNestedPortalViews, (o, v) -> o.simpleNestedPortalViews = v);
        choice("portalViewShadows", IPGlobal.PortalViewShadows.class, c.portalViewShadows,
            (o, v) -> o.portalViewShadows = v);
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

    private <T> void add(String field, OptionInstance<T> instance, T shown, BiConsumer<IPConfig, T> setter) {
        edits.add(new Edit<>(field, instance, shown, setter));
        list.addBig(instance);
    }

    /** The value the widget starts at: what a previous copy of the screen showed, else {@code configured}. */
    @SuppressWarnings("unchecked")
    private <T> T initial(String field, T configured) {
        Object value = pending.get(field);
        return value != null && configured != null && configured.getClass().isInstance(value) ? (T) value : configured;
    }

    /**
     * The lang file's tooltip for the option, if it has one, then the note of an option the preset sets, then the
     * world-load note for a marked option.
     */
    private <T> OptionInstance.TooltipSupplier<T> tooltip(String field) {
        String tooltipKey = optionKey(field) + ".@Tooltip";
        MutableComponent text = I18n.exists(tooltipKey) ? Component.translatable(tooltipKey) : null;
        if (IPPortalPerformancePreset.FIELDS.contains(field) && preset() != IPPortalPerformancePreset.custom) {
            MutableComponent note = Component.translatable(KEY + "presetSets").withStyle(ChatFormatting.GOLD);
            text = text == null ? note : text.append("\n\n").append(note);
        }
        if (ON_WORLD_LOAD.contains(field)) {
            MutableComponent note = Component.translatable(KEY + "onWorldLoad").withStyle(ChatFormatting.GOLD);
            text = text == null ? note : text.append("\n\n").append(note);
        }
        Tooltip result = text == null ? null : Tooltip.create(text);
        if (result != null) {
            tooltipTexts.put(result, text);
        }
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
            OptionInstance.BOOLEAN_VALUES, initial(field, value), v -> {}
        ), value, setter);
    }

    private void range(String field, int min, int max, int value, BiConsumer<IPConfig, Integer> setter) {
        int shown = Mth.clamp(value, min, max);
        add(field, new OptionInstance<>(
            optionKey(field), tooltip(field),
            (caption, v) -> marked(field, Options.genericValueLabel(caption, v)),
            new OptionInstance.IntRange(min, max), Mth.clamp(initial(field, shown), min, max), v -> {}
        ), shown, setter);
    }

    private <E extends Enum<E>> void choice(String field, Class<E> type, E value, BiConsumer<IPConfig, E> setter) {
        choice(field, type, value, setter, v -> {});
    }

    /** {@code onChange} runs when the player picks another value. */
    private <E extends Enum<E>> void choice(
        String field, Class<E> type, E value, BiConsumer<IPConfig, E> setter, Consumer<E> onChange
    ) {
        List<E> values = Arrays.asList(type.getEnumConstants());
        Codec<E> codec = Codec.STRING.xmap(name -> Enum.valueOf(type, name), Enum::name);
        E shown = value != null ? value : values.get(0);
        add(field, new OptionInstance<>(
            optionKey(field), tooltip(field),
            (caption, v) -> marked(field, valueLabel(field, v)),
            new OptionInstance.Enum<>(values, codec), initial(field, shown), onChange
        ), shown, setter);
    }

    /** An enum value's label; the Unlimited preset's in rainbow colours. */
    private static Component valueLabel(String field, Enum<?> v) {
        MutableComponent label = Component.translatableWithFallback(optionKey(field) + "." + v.name(), v.name());
        if (v != IPPortalPerformancePreset.unlimited) {
            return label;
        }
        String text = label.getString();
        int[] codePoints = text.codePoints().toArray();
        MutableComponent rainbow = Component.empty();
        for (int i = 0; i < codePoints.length; i++) {
            // red to violet across the word
            float hue = codePoints.length > 1 ? 0.8f * i / (codePoints.length - 1) : 0;
            rainbow.append(Component.literal(new String(codePoints, i, 1)).withColor(Mth.hsvToRgb(hue, 0.75f, 1f)));
        }
        return rainbow;
    }
}

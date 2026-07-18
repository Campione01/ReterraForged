package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.client.gui.Tooltips;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.client.gui.widget.Slider;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public class CaveSettingsPage extends PresetEditorPage {
	private Slider entranceCaveProbability;
	private Slider cheeseCaveDepthOffset;
	private Slider cheeseCaveProbability;
	private Slider spaghettiProbability;
	private Slider noodleCaveProbability;
	private Slider carverCaveProbability;
	private Slider deepCarverCaveProbability;
	private Slider ravineProbability;
	private CycleButton<Boolean> largeOreVeins;
	private CycleButton<Boolean> legacyCarverDistribution;
	private CycleButton<CaveSettings.DensityAlgorithm> densityAlgorithm;
	private CycleButton<CaveSettings.CompatibilityMode> compatibilityMode;
	
	public CaveSettingsPage(PresetConfigScreen screen, PresetEntry preset) {
		super(screen, preset);
	}

	@Override
	public Component title() {
		return Component.translatable(RTFTranslationKeys.GUI_CAVE_SETTINGS_TITLE);
	}

	@Override
	public void init() {
		super.init();

		Preset preset = this.preset.getPreset();
		CaveSettings caves = preset.caves();

		this.entranceCaveProbability = PresetWidgets.createFloatSlider(caves.entranceCaveProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_ENTRANCE_CAVE_PROBABILITY, (slider, value) -> {
			caves.entranceCaveProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.cheeseCaveDepthOffset = PresetWidgets.createFloatSlider(caves.cheeseCaveDepthOffset, 1.5625F, 10.0F, RTFTranslationKeys.GUI_SLIDER_CHEESE_CAVE_DEPTH_OFFSET, (slider, value) -> {
			caves.cheeseCaveDepthOffset = (float) slider.scaleValue(value);
			return value;
		});
		this.cheeseCaveProbability = PresetWidgets.createFloatSlider(caves.cheeseCaveProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_CHEESE_CAVE_PROBABILITY, (slider, value) -> {
			caves.cheeseCaveProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.spaghettiProbability = PresetWidgets.createFloatSlider(caves.spaghettiCaveProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_SPAGHETTI_CAVE_PROBABILITY, (slider, value) -> {
			caves.spaghettiCaveProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.noodleCaveProbability = PresetWidgets.createFloatSlider(caves.noodleCaveProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_NOODLE_CAVE_PROBABILITY, (slider, value) -> {
			caves.noodleCaveProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.carverCaveProbability = PresetWidgets.createFloatSlider(caves.caveCarverProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_CAVE_CARVER_PROBABILITY, (slider, value) -> {
			caves.caveCarverProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.deepCarverCaveProbability = PresetWidgets.createFloatSlider(caves.deepCaveCarverProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_DEEP_CAVE_CARVER_PROBABILITY, (slider, value) -> {
			caves.deepCaveCarverProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.ravineProbability = PresetWidgets.createFloatSlider(caves.ravineCarverProbability, 0.0F, 1.0F, RTFTranslationKeys.GUI_SLIDER_RAVINE_CARVER_PROBABILITY, (slider, value) -> {
			caves.ravineCarverProbability = (float) slider.scaleValue(value);
			return value;
		});
		this.largeOreVeins = PresetWidgets.createToggle(caves.largeOreVeins, RTFTranslationKeys.GUI_BUTTON_LARGE_ORE_VEINS, (button, value) -> {
			caves.largeOreVeins = value;
		});
		this.legacyCarverDistribution = PresetWidgets.createToggle(caves.legacyCarverDistribution, RTFTranslationKeys.GUI_BUTTON_LEGACY_CARVER_DISTRIBUTION, (button, value) -> {
			caves.legacyCarverDistribution = value;
		});
		this.densityAlgorithm = CycleButton.<CaveSettings.DensityAlgorithm>builder(CaveSettingsPage::densityAlgorithmName)
			.withInitialValue(caves.densityAlgorithm)
			.withValues(Arrays.asList(CaveSettings.DensityAlgorithm.values()))
			.create(-1, -1, -1, -1, Component.translatable(RTFTranslationKeys.GUI_BUTTON_CAVE_DENSITY_ALGORITHM), (button, value) -> {
				caves.densityAlgorithm = value;
			});
		this.densityAlgorithm.setTooltip(Tooltips.create(Tooltips.translationKey(RTFTranslationKeys.GUI_BUTTON_CAVE_DENSITY_ALGORITHM)));
		this.compatibilityMode = CycleButton.<CaveSettings.CompatibilityMode>builder(CaveSettingsPage::compatibilityModeName)
			.withInitialValue(caves.compatibilityMode)
			.withValues(Arrays.asList(CaveSettings.CompatibilityMode.values()))
			.create(-1, -1, -1, -1, Component.translatable(RTFTranslationKeys.GUI_BUTTON_CAVE_COMPATIBILITY_MODE), (button, value) -> {
				caves.compatibilityMode = value;
			});
		this.compatibilityMode.setTooltip(Tooltips.create(Tooltips.translationKey(RTFTranslationKeys.GUI_BUTTON_CAVE_COMPATIBILITY_MODE)));

		this.left.addWidget(PresetWidgets.createLabel(RTFTranslationKeys.GUI_LABEL_NOISE_CAVES));
		this.left.addWidget(this.densityAlgorithm);
		this.left.addWidget(this.entranceCaveProbability);
		this.left.addWidget(this.cheeseCaveDepthOffset);
		this.left.addWidget(this.cheeseCaveProbability);
		this.left.addWidget(this.spaghettiProbability);
		this.left.addWidget(this.noodleCaveProbability);

		this.left.addWidget(PresetWidgets.createLabel(RTFTranslationKeys.GUI_LABEL_CARVERS));
		this.left.addWidget(this.carverCaveProbability);
		this.left.addWidget(this.deepCarverCaveProbability);
		this.left.addWidget(this.ravineProbability);
		this.left.addWidget(this.largeOreVeins);
		this.left.addWidget(this.legacyCarverDistribution);
		this.left.addWidget(this.compatibilityMode);
	}

	private static Component compatibilityModeName(CaveSettings.CompatibilityMode mode) {
		return Component.translatable(RTFTranslationKeys.GUI_VALUE_CAVE_COMPATIBILITY_MODE + "." + mode.getSerializedName().toLowerCase(Locale.ROOT));
	}

	private static Component densityAlgorithmName(CaveSettings.DensityAlgorithm algorithm) {
		return Component.translatable(RTFTranslationKeys.GUI_VALUE_CAVE_DENSITY_ALGORITHM + "." + algorithm.getSerializedName().toLowerCase(Locale.ROOT));
	}
	
	@Override
	public Optional<Page> previous() {
		return Optional.of(new SurfaceSettingsPage(this.screen, this.preset));
	}

	@Override
	public Optional<Page> next() {
		return Optional.of(new ClimateSettingsPage(this.screen, this.preset));
	}
}

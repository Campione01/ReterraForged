package raccoonman.reterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.StringRepresentable;

public class CaveSettings {
	public static final DensityAlgorithm DEFAULT_DENSITY_ALGORITHM = DensityAlgorithm.LEGACY_V2;

	public static final Codec<CaveSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.FLOAT.fieldOf("entranceCaveProbability").forGetter((o) -> o.entranceCaveProbability),
		Codec.FLOAT.fieldOf("cheeseCaveDepthOffset").forGetter((o) -> o.cheeseCaveDepthOffset),
		Codec.FLOAT.fieldOf("cheeseCaveProbability").forGetter((o) -> o.cheeseCaveProbability),
		Codec.FLOAT.fieldOf("spaghettiCaveProbability").forGetter((o) -> o.spaghettiCaveProbability),
		Codec.FLOAT.fieldOf("noodleCaveProbability").forGetter((o) -> o.noodleCaveProbability),
		Codec.FLOAT.fieldOf("caveCarverProbability").forGetter((o) -> o.caveCarverProbability),
		Codec.FLOAT.fieldOf("deepCaveCarverProbability").forGetter((o) -> o.deepCaveCarverProbability),
		Codec.FLOAT.fieldOf("ravineCarverProbability").forGetter((o) -> o.ravineCarverProbability),
		Codec.BOOL.fieldOf("largeOreVeins").forGetter((o) -> o.largeOreVeins),
		Codec.BOOL.fieldOf("legacyCarverDistribution").forGetter((o) -> o.legacyCarverDistribution),
		DensityAlgorithm.CODEC.optionalFieldOf("densityAlgorithm", DEFAULT_DENSITY_ALGORITHM).forGetter((o) -> o.densityAlgorithm),
		CompatibilityMode.CODEC.optionalFieldOf("compatibilityMode", CompatibilityMode.AUTO).forGetter((o) -> o.compatibilityMode)
	).apply(instance, CaveSettings::new));

	public float entranceCaveProbability;
	public float cheeseCaveDepthOffset;
	public float cheeseCaveProbability;
	public float spaghettiCaveProbability;
	public float noodleCaveProbability;
	public float caveCarverProbability;
	public float deepCaveCarverProbability;
	public float ravineCarverProbability;
	public boolean largeOreVeins;
	public boolean legacyCarverDistribution;
	public DensityAlgorithm densityAlgorithm;
	public CompatibilityMode compatibilityMode;
	
	//TODO
	public boolean minCaveBiomeDepth;

	public CaveSettings(float entranceCaveProbability, float cheeseCaveDepthOffset, float cheeseCaveProbability, float spaghettiCaveProbability, float noodleCaveProbability, float caveCarverProbability, float deepCaveCarverProbability, float ravineProbability, boolean largeOreVeins, boolean legacyCarverDistribution) {
		this(entranceCaveProbability, cheeseCaveDepthOffset, cheeseCaveProbability, spaghettiCaveProbability, noodleCaveProbability, caveCarverProbability, deepCaveCarverProbability, ravineProbability, largeOreVeins, legacyCarverDistribution, DEFAULT_DENSITY_ALGORITHM, CompatibilityMode.AUTO);
	}

	public CaveSettings(float entranceCaveProbability, float cheeseCaveDepthOffset, float cheeseCaveProbability, float spaghettiCaveProbability, float noodleCaveProbability, float caveCarverProbability, float deepCaveCarverProbability, float ravineProbability, boolean largeOreVeins, boolean legacyCarverDistribution, CompatibilityMode compatibilityMode) {
		this(entranceCaveProbability, cheeseCaveDepthOffset, cheeseCaveProbability, spaghettiCaveProbability, noodleCaveProbability, caveCarverProbability, deepCaveCarverProbability, ravineProbability, largeOreVeins, legacyCarverDistribution, DEFAULT_DENSITY_ALGORITHM, compatibilityMode);
	}

	public CaveSettings(float entranceCaveProbability, float cheeseCaveDepthOffset, float cheeseCaveProbability, float spaghettiCaveProbability, float noodleCaveProbability, float caveCarverProbability, float deepCaveCarverProbability, float ravineProbability, boolean largeOreVeins, boolean legacyCarverDistribution, DensityAlgorithm densityAlgorithm, CompatibilityMode compatibilityMode) {
		this.entranceCaveProbability = entranceCaveProbability;
		this.cheeseCaveDepthOffset = cheeseCaveDepthOffset;
		this.cheeseCaveProbability = cheeseCaveProbability;
		this.spaghettiCaveProbability = spaghettiCaveProbability;
		this.noodleCaveProbability = noodleCaveProbability;
		this.caveCarverProbability = caveCarverProbability;
		this.deepCaveCarverProbability = deepCaveCarverProbability;
		this.ravineCarverProbability = ravineProbability;
		this.largeOreVeins = largeOreVeins;
		this.legacyCarverDistribution = legacyCarverDistribution;
		this.densityAlgorithm = densityAlgorithm != null ? densityAlgorithm : DEFAULT_DENSITY_ALGORITHM;
		this.compatibilityMode = compatibilityMode != null ? compatibilityMode : CompatibilityMode.AUTO;
	}
	
	public CaveSettings copy() {
		return new CaveSettings(this.entranceCaveProbability, this.cheeseCaveDepthOffset, this.cheeseCaveProbability, this.spaghettiCaveProbability, this.noodleCaveProbability, this.caveCarverProbability, this.deepCaveCarverProbability, this.ravineCarverProbability, this.largeOreVeins, this.legacyCarverDistribution, this.densityAlgorithm, this.compatibilityMode);
	}

	public enum DensityAlgorithm implements StringRepresentable {
		LEGACY("LEGACY"),
		LEGACY_V2("LEGACY_V2"),
		QUICK_V1("QUICK_V1");

		public static final Codec<DensityAlgorithm> CODEC = StringRepresentable.fromEnum(DensityAlgorithm::values);

		private final String name;

		DensityAlgorithm(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return this.name;
		}
	}

	public enum CompatibilityMode implements StringRepresentable {
		AUTO("AUTO"),
		RTF("RTF"),
		VANILLA("VANILLA");

		public static final Codec<CompatibilityMode> CODEC = StringRepresentable.fromEnum(CompatibilityMode::values);

		private String name;

		private CompatibilityMode(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return this.name;
		}
	}
}

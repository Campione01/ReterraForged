package raccoonman.reterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class FakeWaterBiomeSettings {
	public static final Codec<FakeWaterBiomeSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.BOOL.fieldOf("enableFakeWaterBiomes").forGetter((o) -> o.enableFakeWaterBiomes),
		Codec.BOOL.fieldOf("steppeBelowSeaLevelRiverBiomes").forGetter((o) -> o.steppeBelowSeaLevelRiverBiomes),
		Codec.BOOL.fieldOf("badlandsBelowSeaLevelOceanBiomes").forGetter((o) -> o.badlandsBelowSeaLevelOceanBiomes),
		Codec.FLOAT.fieldOf("heightOffset").forGetter((o) -> o.heightOffset)
	).apply(instance, FakeWaterBiomeSettings::new));
	
	public boolean enableFakeWaterBiomes;
	public boolean steppeBelowSeaLevelRiverBiomes;
	public boolean badlandsBelowSeaLevelOceanBiomes;
	public float heightOffset;
	
	public FakeWaterBiomeSettings(boolean enableFakeWaterBiomes, boolean steppeBelowSeaLevelRiverBiomes, boolean badlandsBelowSeaLevelOceanBiomes, float heightOffset) {
		this.enableFakeWaterBiomes = enableFakeWaterBiomes;
		this.steppeBelowSeaLevelRiverBiomes = steppeBelowSeaLevelRiverBiomes;
		this.badlandsBelowSeaLevelOceanBiomes = badlandsBelowSeaLevelOceanBiomes;
		this.heightOffset = heightOffset;
	}
	
	public FakeWaterBiomeSettings copy() {
		return new FakeWaterBiomeSettings(this.enableFakeWaterBiomes, this.steppeBelowSeaLevelRiverBiomes, this.badlandsBelowSeaLevelOceanBiomes, this.heightOffset);
	}
	
	public static FakeWaterBiomeSettings makeDefault() {
		return new FakeWaterBiomeSettings(false, true, true, 0.0F);
	}
}

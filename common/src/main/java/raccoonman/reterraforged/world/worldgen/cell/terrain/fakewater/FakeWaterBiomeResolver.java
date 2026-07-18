package raccoonman.reterraforged.world.worldgen.cell.terrain.fakewater;

import raccoonman.reterraforged.data.worldgen.preset.settings.FakeWaterBiomeSettings;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;

public class FakeWaterBiomeResolver {
	private FakeWaterBiomeSettings settings;
	private Levels levels;
	
	public FakeWaterBiomeResolver(FakeWaterBiomeSettings settings, Levels levels) {
		this.settings = settings;
		this.levels = levels;
	}
	
	public void apply(Cell cell) {
		cell.fakeWaterBiome = this.resolve(cell);
	}
	
	private FakeWaterBiomeTarget resolve(Cell cell) {
		float waterline = this.levels.water - Math.max(0.0F, this.settings.heightOffset);
		if (!this.settings.enableFakeWaterBiomes || cell.height >= waterline) {
			return FakeWaterBiomeTarget.NONE;
		}
		Terrain terrain = cell.terrain;
		if (this.settings.steppeBelowSeaLevelRiverBiomes && isSteppe(terrain)) {
			return FakeWaterBiomeTarget.RIVER;
		}
		if (this.settings.badlandsBelowSeaLevelOceanBiomes && isBadlands(terrain)) {
			return FakeWaterBiomeTarget.RIVER;
		}
		return FakeWaterBiomeTarget.NONE;
	}
	
	public static boolean isSteppe(Terrain terrain) {
		return terrain == TerrainType.STEPPE || terrain.getName().contains("steppe");
	}
	
	public static boolean isBadlands(Terrain terrain) {
		return terrain == TerrainType.BADLANDS || terrain.getName().contains("badlands");
	}
}

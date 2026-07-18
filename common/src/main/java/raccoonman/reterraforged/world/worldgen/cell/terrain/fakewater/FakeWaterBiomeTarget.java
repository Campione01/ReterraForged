package raccoonman.reterraforged.world.worldgen.cell.terrain.fakewater;

public enum FakeWaterBiomeTarget {
	NONE,
	RIVER,
	OCEAN;
	
	public boolean isRiver() {
		return this == RIVER;
	}
	
	public boolean isOcean() {
		return this == OCEAN;
	}
}

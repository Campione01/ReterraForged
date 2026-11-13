package raccoonman.reterraforged.world.worldgen.cell.continent.advanced;

import raccoonman.reterraforged.world.worldgen.cell.continent.Continent;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;

/**
 * Tells whether a world position lies on a skipped continent cell. It repeats the cell search of
 * {@link AdvancedContinentGenerator#apply} and asks {@link AbstractContinent#shouldSkip}, so tests do not depend on the
 * outcome of the code they check. Only the advanced generator skips cells.
 */
public final class SkippedCells {
	private SkippedCells() {}

	public static boolean canSkip(Continent continent) {
		return continent instanceof AdvancedContinentGenerator advanced && advanced.hasSkipping;
	}

	public static boolean isSkipped(Continent continent, float x, float y) {
		if (!(continent instanceof AdvancedContinentGenerator advanced)) {
			return false;
		}
		float px = advanced.warp.getX(x, y, 0) * advanced.frequency;
		float py = advanced.warp.getZ(x, y, 0) * advanced.frequency;
		int xi = NoiseUtil.floor(px);
		int yi = NoiseUtil.floor(py);
		int cellX = xi;
		int cellY = yi;
		float nearest = Float.MAX_VALUE;
		for (int cy = yi - 1; cy <= yi + 1; ++cy) {
			for (int cx = xi - 1; cx <= xi + 1; ++cx) {
				Vec2f vec = NoiseUtil.cell(advanced.seed, cx, cy);
				float dist2 = Line.distSq(px, py, cx + vec.x() * advanced.jitter, cy + vec.y() * advanced.jitter);
				if (dist2 < nearest) {
					cellX = cx;
					cellY = cy;
					nearest = dist2;
				}
			}
		}
		return advanced.shouldSkip(cellX, cellY);
	}
}

package raccoonman.reterraforged.world.worldgen.cell.continent.advanced;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.TestContexts;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.continent.Continent;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

/**
 * A skipped continent cell is ocean only: no river, lake or wetland may be carved into it, and the river climate override
 * (riverMask below 1) must not reach it.
 */
class SkippedContinentRiversTest {
	/** Half the width of the probe window around a continent centre. Main rivers start 400 or more blocks from the centre. */
	private static final int PROBE_RADIUS = 1500;
	private static final int PROBE_STEP = 25;
	private static final int SKIPPED_CENTERS = 4;
	private static final int KEPT_CENTERS = 3;

	@BeforeAll
	static void bootstrapMinecraft() {
		TestContexts.bootstrapMinecraft();
	}

	@Test
	void skippedContinentsHaveNoRiversLakesOrWetlands() {
		int skippedProbed = 0;
		for (Case c : cases()) {
			GeneratorContext context = c.context();
			try {
				Heightmap heightmap = context.generator.getHeightmap();
				Continent continent = heightmap.continent();
				Centers centers = scan(heightmap, continent, c.scale());
				assertTrue(centers.skipped.size() >= SKIPPED_CENTERS, c.name() + ": only " + centers.skipped.size() + " skipped continents in the scan");
				List<String> violations = new ArrayList<>();
				int probed = 0;
				int terrainViolations = 0;
				for (long center : first(centers.skipped, SKIPPED_CENTERS)) {
					Cell cell = new Cell();
					for (int dz = -PROBE_RADIUS; dz <= PROBE_RADIUS; dz += PROBE_STEP) {
						for (int dx = -PROBE_RADIUS; dx <= PROBE_RADIUS; dx += PROBE_STEP) {
							float x = PosUtil.unpackLeft(center) + dx;
							float z = PosUtil.unpackRight(center) + dz;
							if (!SkippedCells.isSkipped(continent, x, z)) {
								continue;
							}
							probed++;
							heightmap.apply(cell.reset(), x, z, true);
							boolean featureTerrain = cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland();
							if (featureTerrain) {
								terrainViolations++;
							}
							if (cell.riverMask != 1.0F || featureTerrain) {
								violations.add("(" + x + ", " + z + ") riverMask=" + cell.riverMask + " terrain=" + cell.terrain.getName());
							}
						}
					}
				}
				assertTrue(probed > 0, c.name() + ": no probe point on a skipped continent");
				assertTrue(violations.isEmpty(), c.name() + ": " + violations.size() + " of " + probed + " points on skipped continents carry river features (" + terrainViolations + " with river, lake or wetland terrain), first: "
					+ violations.subList(0, Math.min(5, violations.size())));
				skippedProbed += probed;
			} finally {
				context.close();
			}
		}
		assertTrue(skippedProbed > 0);
	}

	/** The same probe on continents that are kept must find rivers, otherwise the probe above proves nothing. */
	@Test
	void keptContinentsStillHaveRiversInTheSameProbe() {
		for (Case c : cases()) {
			GeneratorContext context = c.context();
			try {
				Heightmap heightmap = context.generator.getHeightmap();
				Continent continent = heightmap.continent();
				Centers centers = scan(heightmap, continent, c.scale());
				int rivers = 0;
				int lakes = 0;
				for (long center : first(centers.kept, KEPT_CENTERS)) {
					Cell cell = new Cell();
					for (int dz = -PROBE_RADIUS; dz <= PROBE_RADIUS; dz += PROBE_STEP) {
						for (int dx = -PROBE_RADIUS; dx <= PROBE_RADIUS; dx += PROBE_STEP) {
							float x = PosUtil.unpackLeft(center) + dx;
							float z = PosUtil.unpackRight(center) + dz;
							if (SkippedCells.isSkipped(continent, x, z)) {
								continue;
							}
							heightmap.apply(cell.reset(), x, z, true);
							if (cell.terrain.isRiver()) {
								rivers++;
							}
							if (cell.terrain.isLake()) {
								lakes++;
							}
						}
					}
				}
				assertTrue(rivers > 0, c.name() + ": no river terrain around the kept continents");
				assertTrue(rivers + lakes > 0, c.name());
			} finally {
				context.close();
			}
		}
	}

	/** The cell found from a stored continent centre must be the cell that apply used. */
	@Test
	void theCellOfAContinentCenterIsFoundFromTheCenter() {
		for (Case c : cases()) {
			GeneratorContext context = c.context();
			try {
				Heightmap heightmap = context.generator.getHeightmap();
				AdvancedContinentGenerator continent = (AdvancedContinentGenerator) heightmap.continent();
				int step = c.scale() / 2;
				Cell cell = new Cell();
				int skipped = 0;
				int kept = 0;
				for (int z = -48; z <= 48; z++) {
					for (int x = -48; x <= 48; x++) {
						float worldX = x * step + 11;
						float worldZ = z * step + 5;
						continent.apply(cell.reset(), worldX, worldZ);
						boolean skip = SkippedCells.isSkipped(continent, worldX, worldZ);
						assertEquals(skip, continent.isSkippedCenter(cell.continentX, cell.continentZ), c.name() + " at (" + worldX + ", " + worldZ + ")");
						// A skipped cell leaves the continent id at its default.
						assertEquals(skip, cell.continentId == 0.0F, c.name() + " at (" + worldX + ", " + worldZ + ")");
						if (skip) {
							skipped++;
						} else {
							kept++;
						}
					}
				}
				assertTrue(skipped > 0 && kept > 0, c.name() + ": skipped=" + skipped + " kept=" + kept);
			} finally {
				context.close();
			}
		}
	}

	@Test
	void theDefaultContinentAndPresetsWithoutSkippingAreNeverSkipped() {
		Preset preset = Presets.makeRTFDefault();
		preset.world().continent.continentSkipping = 0.0F;
		GeneratorContext context = GeneratorContext.makeUncached(preset, TestContexts.noise(preset), 0, 1, 1, 1);
		try {
			AdvancedContinentGenerator continent = (AdvancedContinentGenerator) context.generator.getHeightmap().continent();
			assertFalse(SkippedCells.canSkip(continent));
			Cell cell = new Cell();
			for (int z = -20; z <= 20; z++) {
				for (int x = -20; x <= 20; x++) {
					continent.apply(cell.reset(), x * 3000 + 1, z * 3000 + 1);
					assertFalse(continent.isSkippedCenter(cell.continentX, cell.continentZ));
				}
			}
		} finally {
			context.close();
		}
	}

	private static List<Case> cases() {
		List<Case> cases = new ArrayList<>();
		for (int seed : new int[] { 0, 42, -1640531527 }) {
			cases.add(new Case("rtf_default/" + seed, Presets::makeRTFDefault, seed, 0.25F));
		}
		cases.add(new Case("legacy_default/0", Presets::makeLegacyDefault, 0, 0.25F));
		cases.add(new Case("rtf_default/7 skipping 0.38", Presets::makeRTFDefault, 7, 0.38F));
		return cases;
	}

	/** Coarse scan over five tectonic cells in every direction; the first skipped and kept continent centres found, in scan order. */
	private static Centers scan(Heightmap heightmap, Continent continent, int scale) {
		Centers centers = new Centers();
		Cell cell = new Cell();
		for (int z = -20; z <= 20; z++) {
			for (int x = -20; x <= 20; x++) {
				float worldX = x * scale + 7;
				float worldZ = z * scale + 3;
				continent.apply(cell.reset(), worldX, worldZ);
				long center = PosUtil.pack(cell.continentX, cell.continentZ);
				if (SkippedCells.isSkipped(continent, worldX, worldZ)) {
					centers.skipped.add(center);
				} else {
					centers.kept.add(center);
				}
			}
		}
		return centers;
	}

	private static List<Long> first(Set<Long> centers, int count) {
		return centers.stream().limit(count).toList();
	}

	private static final class Centers {
		final Set<Long> skipped = new LinkedHashSet<>();
		final Set<Long> kept = new LinkedHashSet<>();
	}

	private record Case(String name, Supplier<Preset> preset, int seed, float skipping) {
		int scale() {
			return preset().get().world().continent.continentScale;
		}

		GeneratorContext context() {
			Preset preset = preset().get();
			preset.world().continent.continentSkipping = skipping();
			return GeneratorContext.makeUncached(preset, TestContexts.noise(preset), seed(), 1, 1, 1);
		}
	}
}

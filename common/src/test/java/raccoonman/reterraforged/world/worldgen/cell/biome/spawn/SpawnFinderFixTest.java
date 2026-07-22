package raccoonman.reterraforged.world.worldgen.cell.biome.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Climate;

class SpawnFinderFixTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void climateSearchDistanceIsRelativeToTheConfiguredCenter() {
		BlockPos center = new BlockPos(12000, 0, -9000);
		SpawnFinderFix finder = new SpawnFinderFix(
			List.of(Climate.parameters(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F)),
			Climate.empty(),
			center
		);

		assertEquals(center, finder.result.location());
	}

	@Test
	void anEmptyTargetListSelectsTheConfiguredCenterExactly() {
		BlockPos center = new BlockPos(-384, 0, 640);
		SpawnFinderFix finder = new SpawnFinderFix(List.of(), Climate.empty(), center);

		assertEquals(center, finder.result.location());
	}
}

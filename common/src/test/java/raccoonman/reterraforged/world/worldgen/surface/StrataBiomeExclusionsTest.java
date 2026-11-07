package raccoonman.reterraforged.world.worldgen.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StrataBiomeExclusionsTest {
	private static final ChunkPos POS = new ChunkPos(-2, 3);
	private static MappedRegistry<Biome> biomes;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		var vanilla = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
		biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
		var tagged = biomes.register(Biomes.FOREST, vanilla.getOrThrow(Biomes.FOREST).value(), RegistrationInfo.BUILT_IN);
		biomes.register(Biomes.PLAINS, vanilla.getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
		biomes.freeze();
		biomes.bindTags(Map.of(StrataBiomeExclusions.TAG, List.of(tagged)));
	}

	@Test
	void retainsEveryTaggedVanillaFuzzyLookupIncludingAcrossChunkEdges() {
		var tagged = biomes.getHolderOrThrow(Biomes.FOREST);
		var ordinary = biomes.getHolderOrThrow(Biomes.PLAINS);
		var manager = new BiomeManager((x, y, z) -> y >= -9 && y <= 11 && ((x ^ y ^ z) & 3) == 0 ? tagged : ordinary,
			BiomeManager.obfuscateSeed(-987654321L));
		var filter = new StrataBiomeExclusions(manager, chunk());
		var pos = new BlockPos.MutableBlockPos();
		int matches = 0;
		int ambiguous = 0;
		int negatives = 0;
		for (int x = POS.getMinBlockX(); x <= POS.getMaxBlockX(); x++) {
			for (int z = POS.getMinBlockZ(); z <= POS.getMaxBlockZ(); z++) {
				for (int y = -64; y < 320; y++) {
					boolean exact = manager.getBiome(pos.set(x, y, z)).is(StrataBiomeExclusions.TAG);
					boolean possible = filter.mightExclude(x, y, z);
					assertFalse(exact && !possible, () -> "Missed fuzzy biome at " + pos);
					if (exact) matches++;
					else if (possible) ambiguous++;
					else negatives++;
				}
			}
		}
		assertTrue(matches > 0 && ambiguous > 0 && negatives > 0);
		assertTrue(filter.mightExclude(POS.getMinBlockX() - 4, 0, POS.getMinBlockZ()));
	}

	@Test
	void samplesEachRawCornerAtMostOnceForAWholeChunk() {
		var reads = new AtomicInteger();
		var ordinary = biomes.getHolderOrThrow(Biomes.PLAINS);
		var manager = new BiomeManager((x, y, z) -> {
			reads.incrementAndGet();
			return ordinary;
		}, 42L);
		var filter = new StrataBiomeExclusions(manager, chunk());
		for (int x = POS.getMinBlockX(); x <= POS.getMaxBlockX(); x++) {
			for (int z = POS.getMinBlockZ(); z <= POS.getMaxBlockZ(); z++) {
				for (int y = -64; y < 320; y++) assertFalse(filter.mightExclude(x, y, z));
			}
		}
		assertEquals(6 * 6 * 98, reads.get());
	}

	private static ProtoChunk chunk() {
		return new ProtoChunk(POS, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), biomes, null);
	}
}

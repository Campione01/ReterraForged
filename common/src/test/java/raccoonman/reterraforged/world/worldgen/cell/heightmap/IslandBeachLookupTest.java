package raccoonman.reterraforged.world.worldgen.cell.heightmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseData;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;

class IslandBeachLookupTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void retainsTheIslandClimateInDirectAndStagedLookup() {
        for (int seed : new int[] {42, -987654321}) {
            Preset preset = Presets.makeRTFDefault();
            preset.island().enableArchipelago = true;
            var heightmap = GeneratorContext.makeUncached(preset, noise(preset), seed, 1, 1, 1)
                    .generator.getHeightmap();
            Sample shore = null;
            Sample shelf = null;
            scan:
            for (int x = -4096; x <= 4096; x += 32) {
                for (int z = -4096; z <= 4096; z += 32) {
                    Cell cell = new Cell();
                    heightmap.applyTerrain(cell, x, z);
                    if (cell.terrain != TerrainType.ISLAND_BEACH) {
                        continue;
                    }
                    heightmap.applyRivers(cell, x, z, Rivermap.get(cell, null, heightmap));
                    heightmap.applyClimate(cell, x, z, true);
                    if (cell.terrain != TerrainType.ISLAND_BEACH) {
                        continue;
                    }
                    if (cell.height > heightmap.levels().water && cell.height <= heightmap.levels().water(5)) {
                        if (shore == null) shore = new Sample(x, z, cell);
                    } else if (cell.height <= heightmap.levels().water) {
                        if (shelf == null) shelf = new Sample(x, z, cell);
                    }
                    if (shore != null && shelf != null) break scan;
                }
            }
            assertNotNull(shore, "No generated island shoreline for " + seed);
            assertNotNull(shelf, "No generated island shelf for " + seed);
            assertSameClimate(heightmap, seed, shore);
            assertSameClimate(heightmap, seed, shelf);
        }
    }

    private static void assertSameClimate(Heightmap heightmap, int seed, Sample sample) {
        Cell actual = new Cell();
        heightmap.apply(actual, sample.x(), sample.z(), true);
        Cell expected = sample.cell();
        String where = " seed=" + seed + " x=" + sample.x() + " z=" + sample.z();
        assertEquals(expected.temperature, actual.temperature, "temperature" + where);
        assertEquals(expected.moisture, actual.moisture, "moisture" + where);
        assertEquals(expected.height, actual.height, "height" + where);
        assertEquals(expected.erosion, actual.erosion, "erosion" + where);
        assertEquals(expected.weirdness, actual.weirdness, "weirdness" + where);
        assertSame(expected.terrain, actual.terrain, "terrain" + where);
        System.out.println("Island beach climate matched" + where + " height=" + actual.height
                + " temperature=" + actual.temperature + " moisture=" + actual.moisture);
    }

    private static HolderGetter<Noise> noise(Preset preset) {
        var registry = new MappedRegistry<>(RTFRegistries.NOISE, Lifecycle.stable());
        var lookup = registry.createRegistrationLookup();
        PresetNoiseData.bootstrap(preset, new BootstrapContext<Noise>() {
            @Override
            public Holder.Reference<Noise> register(ResourceKey<Noise> key, Noise noise, Lifecycle lifecycle) {
                return registry.register(key, noise, RegistrationInfo.BUILT_IN);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> HolderGetter<T> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                if (!RTFRegistries.NOISE.equals(key)) {
                    throw new IllegalArgumentException("Unexpected noise dependency " + key);
                }
                return (HolderGetter<T>) lookup;
            }
        });
        registry.freeze();
        return registry.asLookup();
    }

    private record Sample(int x, int z, Cell cell) {}
}

package raccoonman.reterraforged.world.worldgen.cell.terrain.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.TerrainSettings;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Populators;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.cell.terrain.populator.TerrainPopulator;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.util.Seed;

class TerrainSettingsRuntimeTest {
    private static final float[][] SAMPLES = {
        { 13.0F, 29.0F },
        { 257.0F, -91.0F },
        { 1021.0F, 733.0F },
        { -2403.0F, 1789.0F }
    };

    @Test
    void everyTerrainHorizontalScaleChangesItsCompleteRuntimeShape() {
        List<Factory> factories = List.of(
            settings -> Populators.makeSteppe(new Seed(31), Noises.constant(0.2F), settings),
            settings -> Populators.makePlains(new Seed(31), Noises.constant(0.2F), settings, 0.96F),
            settings -> Populators.makeHills1(new Seed(31), Noises.constant(0.2F), settings, 0.96F),
            settings -> Populators.makeHills2(new Seed(31), Noises.constant(0.2F), settings, 0.96F),
            settings -> Populators.makeDales(new Seed(31), Noises.constant(0.2F), settings),
            settings -> Populators.makePlateau(new Seed(31), Noises.constant(0.2F), settings, 0.96F),
            settings -> Populators.makeBadlands(new Seed(31), Noises.constant(0.2F), settings),
            settings -> Populators.makeTorridonian(new Seed(31), Noises.constant(0.2F), settings),
            settings -> Populators.makeMountains(new Seed(31), Noises.constant(0.2F), settings, settings.horizontalScale, 0.96F, true, true),
            settings -> Populators.makeMountains2(new Seed(31), Noises.constant(0.2F), settings, 0.96F, true, true),
            settings -> Populators.makeMountains3(new Seed(31), Noises.constant(0.2F), settings, 0.96F, true, true)
        );

        for(int i = 0; i < factories.size(); i++) {
            TerrainPopulator identity = factories.get(i).create(settings(1.0F, 1.0F, 1.0F));
            TerrainPopulator stretched = factories.get(i).create(settings(1.0F, 1.0F, 2.0F));
            assertTrue(differs(identity, stretched), "horizontal scale had no runtime effect for terrain factory " + i);
        }
    }

    @Test
    void compositeTerrainRetainsBothInputScaleSettings() {
        TerrainPopulator first = new TerrainPopulator(
            TerrainType.FLATS,
            Noises.constant(0.2F),
            Noises.constant(0.1F),
            Noises.constant(0.2F),
            Noises.constant(0.3F),
            2.0F,
            3.0F,
            1.0F
        );
        TerrainPopulator second = new TerrainPopulator(
            TerrainType.HILLS,
            Noises.constant(0.4F),
            Noises.constant(0.2F),
            Noises.constant(0.4F),
            Noises.constant(0.5F),
            4.0F,
            5.0F,
            1.0F
        );
        TerrainPopulator composite = TerrainProvider.combine(first, second, new Seed(77), new Levels(384, 63), 600);

        Cell cell = sample(composite, 31.0F, 47.0F);
        assertTrue(cell.height >= 0.7F, "composite discarded its inputs' base/vertical scales");
    }

    @Test
    void mountainChainAppliesTheIndividualVerticalScaleOnlyOnce() {
        TerrainPopulator reference = Populators.makeMountainChain(
            new Seed(91),
            Noises.constant(0.2F),
            settings(1.0F, 1.0F, 1.0F),
            1.0F,
            1.0F,
            true,
            false
        );
        TerrainPopulator compensated = Populators.makeMountainChain(
            new Seed(91),
            Noises.constant(0.2F),
            settings(1.0F, 2.0F, 1.0F),
            1.0F,
            0.5F,
            true,
            false
        );

        for(float[] point : SAMPLES) {
            Cell expected = sample(reference, point[0], point[1]);
            Cell actual = sample(compensated, point[0], point[1]);
            assertEquals(expected.height, actual.height, 1.0E-6F);
        }
    }

    @Test
    void identityCompositeKeepsTheLegacyGroundAndHeightPath() {
        Levels levels = new Levels(384, 63);
        TerrainPopulator first = new TerrainPopulator(TerrainType.FLATS, Noises.constant(0.1F), Noises.constant(0.05F), Noises.zero(), Noises.zero(), 1.0F);
        TerrainPopulator second = new TerrainPopulator(TerrainType.HILLS, Noises.constant(0.9F), Noises.constant(0.15F), Noises.zero(), Noises.zero(), 1.0F);
        TerrainPopulator composite = TerrainProvider.combine(first, second, new Seed(17), levels, 600);

        Cell cell = sample(composite, 12.0F, 18.0F);
        assertEquals(Float.floatToRawIntBits(levels.ground), Float.floatToRawIntBits(composite.base().computeRoot(12.0F, 18.0F, 0)));
        assertNotEquals(Float.floatToRawIntBits(0.1F), Float.floatToRawIntBits(cell.height));
    }

    private static boolean differs(TerrainPopulator first, TerrainPopulator second) {
        for(float[] point : SAMPLES) {
            Cell left = sample(first, point[0], point[1]);
            Cell right = sample(second, point[0], point[1]);
            if(Float.floatToRawIntBits(left.height) != Float.floatToRawIntBits(right.height)
                || Float.floatToRawIntBits(left.erosion) != Float.floatToRawIntBits(right.erosion)
                || Float.floatToRawIntBits(left.weirdness) != Float.floatToRawIntBits(right.weirdness)) {
                return true;
            }
        }
        return false;
    }

    private static Cell sample(TerrainPopulator populator, float x, float z) {
        Cell cell = new Cell();
        populator.apply(cell, x, z);
        return cell;
    }

    private static TerrainSettings.Terrain settings(float baseScale, float verticalScale, float horizontalScale) {
        return new TerrainSettings.Terrain(1.0F, baseScale, verticalScale, horizontalScale);
    }

    @FunctionalInterface
    private interface Factory {
        TerrainPopulator create(TerrainSettings.Terrain settings);
    }
}

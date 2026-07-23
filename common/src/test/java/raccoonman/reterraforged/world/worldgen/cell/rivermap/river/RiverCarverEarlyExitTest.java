package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Random;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.fade.RiverTerrainFade;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunctions;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;

class RiverCarverEarlyExitTest {
    private static final int SAMPLE_COUNT = 250_000;

    @Test
    void distanceFirstExitPreservesOriginalCarverBits() {
        Levels levels = new Levels(1184, 62);
        RiverConfig config = RiverConfig.builder(levels)
            .main(true)
            .order(0)
            .bedWidth(9)
            .bankWidth(31)
            .bedDepth(7)
            .bankHeight(2, 9)
            .fade(0.37F)
            .length(5000)
            .build();
        River river = new River(-1750.25F, 920.5F, 3240.75F, -2810.125F);
        RiverCarver.Settings settings = new RiverCarver.Settings();
        settings.valleySize = 371.25F;
        settings.fadeIn = 0.37F;
        settings.connecting = false;
        settings.valleyCurve = CurveFunctions.scurve(3.0F, 0.25F);
        RiverCarver carver = new RiverCarver(river, RiverWarp.NONE, config, settings, levels);
        LegacyCarver reference = new LegacyCarver(river, config, settings, levels);
        Random random = new Random(0x4C65676163795632L);

        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            Cell expected = randomCell(random);
            Cell actual = new Cell();
            actual.copyFrom(expected);
            float px = randomCoordinate(random);
            float pz = randomCoordinate(random);
            float x = randomCoordinate(random);
            float z = randomCoordinate(random);
            float pt = randomT(random);
            float t = randomT(random);

            reference.carve(expected, px, pz, pt, x, z, t);
            carver.carve(actual, px, pz, pt, x, z, t);
            assertCellEquals(expected, actual, sample);
        }
    }

    private static Cell randomCell(Random random) {
        Cell cell = new Cell();
        cell.height = random.nextFloat() * 1.4F - 0.2F;
        cell.heightErosion = random.nextFloat() * 0.25F - 0.125F;
        cell.sediment = random.nextFloat() * 0.25F;
        cell.continentEdge = random.nextFloat() * 1.5F - 0.25F;
        cell.terrainRegionEdge = random.nextFloat() * 1.5F - 0.25F;
        cell.riverMask = random.nextFloat() * 1.5F - 0.25F;
        cell.erosionMask = random.nextBoolean();
        cell.terrain = switch (random.nextInt(4)) {
            case 0 -> TerrainType.FLATS;
            case 1 -> TerrainType.BADLANDS;
            case 2 -> TerrainType.MOUNTAINS_1;
            default -> TerrainType.ISLAND_MOUNTAINS;
        };
        return cell;
    }

    private static float randomCoordinate(Random random) {
        return random.nextFloat() * 14_000.0F - 7_000.0F;
    }

    private static float randomT(Random random) {
        return random.nextFloat() * 1.6F - 0.3F;
    }

    private static void assertCellEquals(Cell expected, Cell actual, int sample) {
        String context = "sample " + sample;
        assertBitsEqual(expected.height, actual.height, "height " + context);
        assertBitsEqual(expected.heightErosion, actual.heightErosion, "heightErosion " + context);
        assertBitsEqual(expected.sediment, actual.sediment, "sediment " + context);
        assertBitsEqual(expected.riverMask, actual.riverMask, "riverMask " + context);
        assertEquals(expected.erosionMask, actual.erosionMask, "erosionMask " + context);
        assertSame(expected.terrain, actual.terrain, "terrain " + context);
    }

    private static void assertBitsEqual(float expected, float actual, String context) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), context);
    }

    private static final class LegacyCarver {
        private final River river;
        private final float fade;
        private final float fadeInv;
        private final Range bedWidth;
        private final Range banksWidth;
        private final Range valleyWidth;
        private final Range bedDepth;
        private final Range banksDepth;
        private final float waterLine;
        private final float fadeStartHeight;
        private final float fadeEndHeight;
        private final CurveFunction valleyCurve;

        private LegacyCarver(River river, RiverConfig config, RiverCarver.Settings settings, Levels levels) {
            this.river = river;
            this.fade = settings.fadeIn;
            this.fadeInv = 1.0F / settings.fadeIn;
            this.bedWidth = new Range(0.25F, config.bedWidth * config.bedWidth);
            this.banksWidth = new Range(1.5625F, config.bankWidth * config.bankWidth);
            this.valleyWidth = new Range(settings.valleySize * settings.valleySize, settings.valleySize * settings.valleySize);
            this.waterLine = levels.water;
            this.bedDepth = new Range(levels.water, config.bedHeight);
            this.banksDepth = new Range(config.minBankHeight, config.maxBankHeight);
            this.valleyCurve = settings.valleyCurve;
            this.fadeStartHeight = levels.scale(300);
            this.fadeEndHeight = levels.scale(360);
        }

        private void carve(Cell cell, float px, float pz, float pt, float x, float z, float t) {
            float d2 = this.getDistance2(x, z, t);
            float pd2 = this.getDistance2(px, pz, pt);
            float heightFade = RiverTerrainFade.heightFade(cell.height, this.fadeStartHeight, this.fadeEndHeight);
            float valleyFade = RiverTerrainFade.valleyFade(cell, heightFade);
            float banksFade = RiverTerrainFade.banksFade(cell, heightFade);
            float bedFade = RiverTerrainFade.bedFade(cell, heightFade);

            float valleyAlpha = this.getDistanceAlpha(pt, Math.min(d2, pd2), this.valleyWidth);
            valleyAlpha *= valleyFade;
            if (valleyAlpha == 0.0F) {
                return;
            }
            float bankHeight = this.getScaledSize(t, this.banksDepth);
            valleyAlpha = this.valleyCurve.apply(valleyAlpha);
            cell.riverMask = Math.min(cell.riverMask, 1.0F - valleyAlpha);
            cell.height = Math.min(NoiseUtil.lerp(cell.height, bankHeight, valleyAlpha), cell.height);
            float mouthModifier = getMouthModifier(cell);
            float bedHeight = this.getScaledSize(t, this.bedDepth);
            float banksAlpha = this.getDistanceAlpha(t, d2 * mouthModifier, this.banksWidth);
            banksAlpha *= banksFade;

            boolean canTagRiver = RiverTerrainFade.canTagRiver(banksFade, bedFade);
            if (banksAlpha != 0.0F && cell.height > bedHeight) {
                cell.height = Math.min(NoiseUtil.lerp(cell.height, bedHeight, banksAlpha), cell.height);
                if (canTagRiver) {
                    this.tag(cell, bedHeight);
                }
            }
            float bedAlpha = this.getDistanceAlpha(t, d2, this.bedWidth);
            bedAlpha *= bedFade;

            if (bedAlpha != 0.0F && cell.height > bedHeight) {
                cell.height = NoiseUtil.lerp(cell.height, bedHeight, bedAlpha);
                if (canTagRiver) {
                    this.tag(cell, bedHeight);
                }
            }
        }

        private float getDistance2(float x, float z, float t) {
            if (t <= 0.0F) {
                return Line.distSq(x, z, this.river.x1, this.river.z1);
            }
            if (t >= 1.0F) {
                return Line.distSq(x, z, this.river.x2, this.river.z2);
            }
            float px = this.river.x1 + t * this.river.dx;
            float pz = this.river.z1 + t * this.river.dz;
            return Line.distSq(x, z, px, pz);
        }

        private float getDistanceAlpha(float t, float distance2, Range range) {
            float size2 = this.getScaledSize(t, range);
            if (distance2 >= size2) {
                return 0.0F;
            }
            return 1.0F - distance2 / size2;
        }

        private float getScaledSize(float t, Range range) {
            if (t < 0.0F) {
                return range.min();
            }
            if (t > 1.0F) {
                return range.max();
            }
            if (range.min() == range.max()) {
                return range.min();
            }
            if (t >= this.fade) {
                return range.max();
            }
            return NoiseUtil.lerp(range.min(), range.max(), t * this.fadeInv);
        }

        private void tag(Cell cell, float bedHeight) {
            if (cell.terrain.overridesRiver() && (cell.height < bedHeight || cell.height > this.waterLine)) {
                return;
            }
            cell.erosionMask = true;
            if (cell.height <= this.waterLine) {
                cell.terrain = TerrainType.RIVER;
            }
        }

        private static float getMouthModifier(Cell cell) {
            float modifier = NoiseUtil.map(cell.continentEdge, 0.0F, 0.5F, 0.5F);
            modifier *= modifier;
            return modifier;
        }
    }
}

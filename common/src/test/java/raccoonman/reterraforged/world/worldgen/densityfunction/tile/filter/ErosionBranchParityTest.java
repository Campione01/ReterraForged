package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.Random;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.FilterSettings;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.util.FastRandom;

class ErosionBranchParityTest {
    @Test
    void cachedAndDynamicStrengthBranchesMatchByRawBits() {
        Size size = Size.blocks(3, 2);
        Cell[] initial = cells(size, 0x5EEDCAFE);
        Cell[] dynamicCells = copy(initial);
        Cell[] cachedCells = copy(initial);
        FilterSettings.Erosion settings = new FilterSettings.Erosion(250, 32, 0.20017183F, 0.1F, 1.0F, 0.0F);
        Modifier modifier = Modifier.range(0.164F, 0.203F);

        Erosion dynamic = new Erosion(12768 + 1234, size.total(), settings, modifier, false);
        Erosion cached = new Erosion(12768 + 1234, size.total(), settings, modifier, true);
        LegacyReferenceErosion legacy = new LegacyReferenceErosion(
            12768 + 1234,
            size.total(),
            settings,
            0.164F,
            0.203F
        );
        Cell[] legacyCells = copy(initial);
        dynamic.apply(new Map(size, dynamicCells), 0, 0, settings.dropletsPerChunk);
        cached.apply(new Map(size, cachedCells), 0, 0, settings.dropletsPerChunk);
        legacy.apply(new Map(size, legacyCells), settings.dropletsPerChunk);

        for(int index = 0; index < dynamicCells.length; index++) {
            assertFloatBits("legacy/dynamic height", index, legacyCells[index].height, dynamicCells[index].height);
            assertFloatBits("legacy/dynamic heightErosion", index, legacyCells[index].heightErosion, dynamicCells[index].heightErosion);
            assertFloatBits("legacy/dynamic sediment", index, legacyCells[index].sediment, dynamicCells[index].sediment);
            assertFloatBits("height", index, dynamicCells[index].height, cachedCells[index].height);
            assertFloatBits("heightErosion", index, dynamicCells[index].heightErosion, cachedCells[index].heightErosion);
            assertFloatBits("sediment", index, dynamicCells[index].sediment, cachedCells[index].sediment);
        }
    }

    private static Cell[] cells(Size size, int seed) {
        Random random = new Random(seed);
        Terrain[] terrains = {
            TerrainType.HILLS,
            TerrainType.MOUNTAINS_1,
            TerrainType.BADLANDS,
            TerrainType.RIVER,
            TerrainType.WETLAND
        };
        Cell[] cells = new Cell[size.arraySize()];
        for(int z = 0; z < size.total(); z++) {
            for(int x = 0; x < size.total(); x++) {
                int index = size.indexOf(x, z);
                Cell cell = new Cell();
                float smooth = 0.31F
                    + 0.13F * (float)Math.sin(x * 0.071)
                    + 0.11F * (float)Math.cos(z * 0.053)
                    + 0.03F * (float)Math.sin((x + z) * 0.19);
                cell.height = smooth + (random.nextFloat() - 0.5F) * 0.012F;
                cell.heightErosion = (random.nextFloat() - 0.5F) * 0.01F;
                cell.sediment = random.nextFloat() * 0.01F;
                cell.terrainRegionEdge = random.nextFloat();
                cell.riverMask = random.nextInt(4) == 0 ? random.nextFloat() * 0.12F : 0.1F + random.nextFloat() * 0.9F;
                cell.erosionMask = random.nextInt(17) == 0;
                cell.terrain = terrains[random.nextInt(terrains.length)];
                cells[index] = cell;
            }
        }
        return cells;
    }

    private static Cell[] copy(Cell[] source) {
        Cell[] copy = new Cell[source.length];
        for(int index = 0; index < source.length; index++) {
            copy[index] = new Cell();
            copy[index].copyFrom(source[index]);
        }
        return copy;
    }

    private static void assertFloatBits(String field, int index, float expected, float actual) {
        int expectedBits = Float.floatToRawIntBits(expected);
        int actualBits = Float.floatToRawIntBits(actual);
        if(expectedBits != actualBits) {
            fail(field + " differs at index " + index
                + ": dynamic=" + expected + " (0x" + Integer.toHexString(expectedBits) + ")"
                + ", cached=" + actual + " (0x" + Integer.toHexString(actualBits) + ")");
        }
    }

    private static final class LegacyReferenceErosion {
        private final float erodeSpeed;
        private final float depositSpeed;
        private final float initialSpeed;
        private final float initialWaterVolume;
        private final int maxDropletLifetime;
        private final int[][] erosionBrushIndices;
        private final float[][] erosionBrushWeights;
        private final int seed;
        private final float modifierMin;
        private final float modifierMax;
        private final float modifierRange;

        private LegacyReferenceErosion(
            int seed,
            int mapSize,
            FilterSettings.Erosion settings,
            float modifierMin,
            float modifierMax
        ) {
            this.seed = seed;
            this.erodeSpeed = settings.erosionRate;
            this.depositSpeed = settings.depositeRate;
            this.initialSpeed = settings.dropletVelocity;
            this.initialWaterVolume = settings.dropletVolume;
            this.maxDropletLifetime = settings.dropletLifetime;
            this.modifierMin = modifierMin;
            this.modifierMax = modifierMax;
            this.modifierRange = modifierMax - modifierMin;
            this.erosionBrushIndices = new int[mapSize * mapSize][];
            this.erosionBrushWeights = new float[mapSize * mapSize][];
            this.initBrushes(mapSize, 4);
        }

        private void apply(Filterable map, int iterationsPerChunk) {
            int chunkX = map.getBlockX() >> 4;
            int chunkZ = map.getBlockZ() >> 4;
            int lengthChunks = map.getBlockSize().total() >> 4;
            int borderChunks = map.getBlockSize().border() >> 4;
            int mapSize = map.getBlockSize().total();
            float maxPos = mapSize - 2;
            Cell[] cells = map.getBacking();
            LegacyTerrainPos gradient1 = new LegacyTerrainPos();
            LegacyTerrainPos gradient2 = new LegacyTerrainPos();
            FastRandom random = new FastRandom();
            for(int i = 0; i < iterationsPerChunk; i++) {
                long iterationSeed = NoiseUtil.seed(this.seed, i);
                for(int cz = 0; cz < lengthChunks; cz++) {
                    int relZ = cz << 4;
                    int seedZ = chunkZ + cz - borderChunks;
                    for(int cx = 0; cx < lengthChunks; cx++) {
                        int relX = cx << 4;
                        int seedX = chunkX + cx - borderChunks;
                        long chunkSeed = NoiseUtil.seed(seedX, seedZ);
                        random.seed(chunkSeed, iterationSeed);
                        float posX = relX + random.nextInt(16);
                        float posZ = relZ + random.nextInt(16);
                        posX = NoiseUtil.clamp(posX, 1.0F, maxPos);
                        posZ = NoiseUtil.clamp(posZ, 1.0F, maxPos);
                        this.applyDrop(posX, posZ, cells, mapSize, gradient1, gradient2);
                    }
                }
            }
        }

        private void applyDrop(
            float posX,
            float posY,
            Cell[] cells,
            int mapSize,
            LegacyTerrainPos gradient1,
            LegacyTerrainPos gradient2
        ) {
            float dirX = 0.0F;
            float dirY = 0.0F;
            float sediment = 0.0F;
            float speed = this.initialSpeed;
            float water = this.initialWaterVolume;
            gradient1.reset();
            gradient2.reset();
            for(int lifetime = 0; lifetime < this.maxDropletLifetime; lifetime++) {
                int nodeX = (int)posX;
                int nodeY = (int)posY;
                int dropletIndex = nodeY * mapSize + nodeX;
                float cellOffsetX = posX - nodeX;
                float cellOffsetY = posY - nodeY;
                gradient1.at(cells, mapSize, posX, posY);
                dirX = dirX * 0.05F - gradient1.gradientX * 0.95F;
                dirY = dirY * 0.05F - gradient1.gradientY * 0.95F;
                float len = (float)Math.sqrt(dirX * dirX + dirY * dirY);
                if(Float.isNaN(len)) {
                    len = 0.0F;
                }
                if(len != 0.0F) {
                    dirX /= len;
                    dirY /= len;
                }
                posX += dirX;
                posY += dirY;
                if((dirX == 0.0F && dirY == 0.0F)
                    || posX < 0.0F
                    || posX >= mapSize - 1
                    || posY < 0.0F
                    || posY >= mapSize - 1) {
                    return;
                }
                float newHeight = gradient2.at(cells, mapSize, posX, posY).height;
                float deltaHeight = newHeight - gradient1.height;
                float sedimentCapacity = Math.max(-deltaHeight * speed * water * 4.0F, 0.01F);
                if(sediment > sedimentCapacity || deltaHeight > 0.0F) {
                    float amountToDeposit = deltaHeight > 0.0F
                        ? Math.min(deltaHeight, sediment)
                        : (sediment - sedimentCapacity) * this.depositSpeed;
                    sediment -= amountToDeposit;
                    this.deposit(cells[dropletIndex], amountToDeposit * (1.0F - cellOffsetX) * (1.0F - cellOffsetY));
                    this.deposit(cells[dropletIndex + 1], amountToDeposit * cellOffsetX * (1.0F - cellOffsetY));
                    this.deposit(cells[dropletIndex + mapSize], amountToDeposit * (1.0F - cellOffsetX) * cellOffsetY);
                    this.deposit(cells[dropletIndex + mapSize + 1], amountToDeposit * cellOffsetX * cellOffsetY);
                } else {
                    float amountToErode = Math.min((sedimentCapacity - sediment) * this.erodeSpeed, -deltaHeight);
                    for(int brushPointIndex = 0;
                        brushPointIndex < this.erosionBrushIndices[dropletIndex].length;
                        brushPointIndex++) {
                        int nodeIndex = this.erosionBrushIndices[dropletIndex][brushPointIndex];
                        Cell cell = cells[nodeIndex];
                        float brushWeight = this.erosionBrushWeights[dropletIndex][brushPointIndex];
                        float weighedErodeAmount = amountToErode * brushWeight;
                        float deltaSediment = Math.min(cell.height, weighedErodeAmount);
                        this.erode(cell, deltaSediment);
                        sediment += deltaSediment;
                    }
                }
                speed = (float)Math.sqrt(speed * speed + deltaHeight * 3.0F);
                water *= 0.99F;
                if(Float.isNaN(speed)) {
                    speed = 0.0F;
                }
            }
        }

        private void initBrushes(int size, int radius) {
            int[] xOffsets = new int[radius * radius * 4];
            int[] yOffsets = new int[radius * radius * 4];
            float[] weights = new float[radius * radius * 4];
            float weightSum = 0.0F;
            int addIndex = 0;
            for(int i = 0; i < this.erosionBrushIndices.length; i++) {
                int centreX = i % size;
                int centreY = i / size;
                if(centreY <= radius
                    || centreY >= size - radius
                    || centreX <= radius + 1
                    || centreX >= size - radius) {
                    weightSum = 0.0F;
                    addIndex = 0;
                    for(int y = -radius; y <= radius; y++) {
                        for(int x = -radius; x <= radius; x++) {
                            float sqrDst = x * x + y * y;
                            if(sqrDst < radius * radius) {
                                int coordX = centreX + x;
                                int coordY = centreY + y;
                                if(coordX >= 0 && coordX < size && coordY >= 0 && coordY < size) {
                                    float weight = 1.0F - (float)Math.sqrt(sqrDst) / radius;
                                    weightSum += weight;
                                    weights[addIndex] = weight;
                                    xOffsets[addIndex] = x;
                                    yOffsets[addIndex] = y;
                                    addIndex++;
                                }
                            }
                        }
                    }
                }
                this.erosionBrushIndices[i] = new int[addIndex];
                this.erosionBrushWeights[i] = new float[addIndex];
                for(int j = 0; j < addIndex; j++) {
                    this.erosionBrushIndices[i][j] = (yOffsets[j] + centreY) * size + xOffsets[j] + centreX;
                    this.erosionBrushWeights[i][j] = weights[j] / weightSum;
                }
            }
        }

        private void deposit(Cell cell, float amount) {
            if(!cell.erosionMask) {
                float change = this.modify(cell, amount);
                cell.height += change;
                cell.sediment += change;
            }
        }

        private void erode(Cell cell, float amount) {
            if(!cell.erosionMask) {
                float change = this.modify(cell, amount);
                cell.height -= change;
                cell.heightErosion -= change;
            }
        }

        private float modify(Cell cell, float value) {
            float strengthModifier = 1.0F;
            float erosionModifier = cell.terrain.erosionModifier();
            if(erosionModifier != 1.0F) {
                float alpha = NoiseUtil.map(cell.terrainRegionEdge, 0.0F, 0.15F, 0.15F);
                strengthModifier = NoiseUtil.lerp(1.0F, erosionModifier, alpha);
            }
            if(cell.riverMask < 0.1F) {
                strengthModifier *= NoiseUtil.map(cell.riverMask, 0.002F, 0.1F, 0.098F);
            }
            float valueModifier;
            if(cell.height > this.modifierMax) {
                valueModifier = 1.0F;
            } else {
                valueModifier = cell.height < this.modifierMin
                    ? 0.0F
                    : (cell.height - this.modifierMin) / this.modifierRange;
            }
            return valueModifier * strengthModifier * value;
        }
    }

    private static final class LegacyTerrainPos {
        private float height;
        private float gradientX;
        private float gradientY;

        private LegacyTerrainPos at(Cell[] nodes, int mapSize, float posX, float posY) {
            int coordX = (int)posX;
            int coordY = (int)posY;
            float x = posX - coordX;
            float y = posY - coordY;
            int nodeIndexNW = coordY * mapSize + coordX;
            float heightNW = nodes[nodeIndexNW].height;
            float heightNE = nodes[nodeIndexNW + 1].height;
            float heightSW = nodes[nodeIndexNW + mapSize].height;
            float heightSE = nodes[nodeIndexNW + mapSize + 1].height;
            this.gradientX = (heightNE - heightNW) * (1.0F - y) + (heightSE - heightSW) * y;
            this.gradientY = (heightSW - heightNW) * (1.0F - x) + (heightSE - heightNE) * x;
            this.height = heightNW * (1.0F - x) * (1.0F - y)
                + heightNE * x * (1.0F - y)
                + heightSW * (1.0F - x) * y
                + heightSE * x * y;
            return this;
        }

        private void reset() {
            this.height = 0.0F;
            this.gradientX = 0.0F;
            this.gradientY = 0.0F;
        }
    }

    private record Map(Size size, Cell[] cells) implements Filterable {
        @Override
        public int getBlockX() {
            return 0;
        }

        @Override
        public int getBlockZ() {
            return 0;
        }

        @Override
        public Size getBlockSize() {
            return this.size;
        }

        @Override
        public Cell[] getBacking() {
            return this.cells;
        }

        @Override
        public Cell getCellRaw(int x, int z) {
            return this.cells[this.size.indexOf(x, z)];
        }
    }
}

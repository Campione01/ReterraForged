package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.IntFunction;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.data.worldgen.preset.settings.FilterSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.util.FastRandom;

public class Erosion implements Filter {
    private final float erodeSpeed;
    private final float depositSpeed;
    private final float initialSpeed;
    private final float initialWaterVolume;
    private final int maxDropletLifetime;
    @Nullable
    private final long[][] erosionBrushes;
    @Nullable
    private final FlatBrushes flatSharedErosionBrushes;
    private final int seed;
    private final int mapSize;
    private final Modifier modifier;
    private final boolean cacheStrengthModifiers;
    private final ConcurrentLinkedDeque<StrengthModifierBuffer> strengthModifierPool;
    
    public Erosion(final int seed, final int mapSize, final FilterSettings.Erosion settings, final Modifier modifier) {
		this(seed, mapSize, settings, modifier, false);
	}

    Erosion(final int seed, final int mapSize, final FilterSettings.Erosion settings, final Modifier modifier, final boolean cacheStrengthModifiers) {
        this.seed = seed;
        this.mapSize = mapSize;
        this.modifier = modifier;
		this.cacheStrengthModifiers = cacheStrengthModifiers;
		this.strengthModifierPool = new ConcurrentLinkedDeque<>();
        this.erodeSpeed = settings.erosionRate;
        this.depositSpeed = settings.depositeRate;
        this.initialSpeed = settings.dropletVelocity;
        this.initialWaterVolume = settings.dropletVolume;
        this.maxDropletLifetime = settings.dropletLifetime;
        if(cacheStrengthModifiers) {
            this.erosionBrushes = null;
            this.flatSharedErosionBrushes = this.initFlatSharedBrushes(mapSize, 4);
        } else {
            this.erosionBrushes = new long[mapSize * mapSize][];
            this.flatSharedErosionBrushes = null;
            this.initBrushes(mapSize, 4);
        }
    }
    
    public int getSize() {
        return this.mapSize;
    }
    
    @Override
    public void apply(Filterable map, int regionX, int regionZ, int iterationsPerChunk) {
        final int chunkX = map.getBlockX() >> 4;
        final int chunkZ = map.getBlockZ() >> 4;
        final int lengthChunks = map.getBlockSize().total() >> 4;
        final int borderChunks = map.getBlockSize().border() >> 4;
        final Size size = map.getBlockSize();
        final int mapSize = size.total();
        final float maxPos = (float)(mapSize - 2);
        final Cell[] cells = map.getBacking();
        final TerrainPos gradient1 = new TerrainPos();
        final TerrainPos gradient2 = new TerrainPos();
        final FastRandom random = new FastRandom();
		StrengthModifierBuffer strengthModifierBuffer = this.acquireStrengthModifiers(cells, size.arraySize());
		float[] strengthModifiers = strengthModifierBuffer == null ? null : strengthModifierBuffer.values;
		try {
            for (int i = 0; i < iterationsPerChunk; ++i) {
                final long iterationSeed = NoiseUtil.seed(this.seed, i);
                for (int cz = 0; cz < lengthChunks; ++cz) {
                    final int relZ = cz << 4;
                    final int seedZ = chunkZ + cz - borderChunks;
                    for (int cx = 0; cx < lengthChunks; ++cx) {
                        final int relX = cx << 4;
                        final int seedX = chunkX + cx - borderChunks;
                        final long chunkSeed = NoiseUtil.seed(seedX, seedZ);
                        random.seed(chunkSeed, iterationSeed);
                        float posX = (float)(relX + random.nextInt(16));
                        float posZ = (float)(relZ + random.nextInt(16));
                        posX = NoiseUtil.clamp(posX, 1.0f, maxPos);
                        posZ = NoiseUtil.clamp(posZ, 1.0f, maxPos);
                        this.applyDrop(posX, posZ, cells, mapSize, gradient1, gradient2, strengthModifiers);
					}
                }
            }
		} finally {
			if(strengthModifierBuffer != null) {
				this.strengthModifierPool.addFirst(strengthModifierBuffer);
			}
        }
    }
    
	private void applyDrop(float posX, float posY, final Cell[] cells, final int mapSize, final TerrainPos gradient1, final TerrainPos gradient2,
			@Nullable final float[] strengthModifiers) {
        float dirX = 0.0f;
        float dirY = 0.0f;
        float sediment = 0.0f;
        float speed = this.initialSpeed;
        float water = this.initialWaterVolume;
        gradient1.reset();
        gradient2.reset();
        for (int lifetime = 0; lifetime < this.maxDropletLifetime; ++lifetime) {
            final int nodeX = (int)posX;
            final int nodeY = (int)posY;
            final int dropletIndex = nodeY * mapSize + nodeX;
            final float cellOffsetX = posX - nodeX;
            final float cellOffsetY = posY - nodeY;
            gradient1.at(cells, mapSize, posX, posY);
            dirX = dirX * 0.05f - gradient1.gradientX * 0.95f;
            dirY = dirY * 0.05f - gradient1.gradientY * 0.95f;
            float len = (float)Math.sqrt(dirX * dirX + dirY * dirY);
            if (Float.isNaN(len)) {
                len = 0.0f;
            }
            if (len != 0.0f) {
                dirX /= len;
                dirY /= len;
            }
            posX += dirX;
            posY += dirY;
            if ((dirX == 0.0f && dirY == 0.0f) || posX < 0.0f || posX >= mapSize - 1 || posY < 0.0f || posY >= mapSize - 1) {
                return;
            }
            final float newHeight = gradient2.at(cells, mapSize, posX, posY).height;
            final float deltaHeight = newHeight - gradient1.height;
            final float sedimentCapacity = Math.max(-deltaHeight * speed * water * 4.0f, 0.01f);
            if (sediment > sedimentCapacity || deltaHeight > 0.0f) {
                final float amountToDeposit = (deltaHeight > 0.0f) ? Math.min(deltaHeight, sediment) : ((sediment - sedimentCapacity) * this.depositSpeed);
                sediment -= amountToDeposit;
                final float inverseCellOffsetX = 1.0f - cellOffsetX;
                final float inverseCellOffsetY = 1.0f - cellOffsetY;
                final int southIndex = dropletIndex + mapSize;
                this.deposit(cells[dropletIndex], dropletIndex, amountToDeposit * inverseCellOffsetX * inverseCellOffsetY, strengthModifiers);
                this.deposit(cells[dropletIndex + 1], dropletIndex + 1, amountToDeposit * cellOffsetX * inverseCellOffsetY, strengthModifiers);
                this.deposit(cells[southIndex], southIndex, amountToDeposit * inverseCellOffsetX * cellOffsetY, strengthModifiers);
                this.deposit(cells[southIndex + 1], southIndex + 1, amountToDeposit * cellOffsetX * cellOffsetY, strengthModifiers);
            }
            else {
                final float amountToErode = Math.min((sedimentCapacity - sediment) * this.erodeSpeed, -deltaHeight);
                if(this.flatSharedErosionBrushes != null) {
                    final FlatBrushes brushes = this.flatSharedErosionBrushes;
                    final int start = brushes.starts[dropletIndex];
                    final int end = start + Byte.toUnsignedInt(brushes.lengths[dropletIndex]);
                    final int[] offsets = brushes.offsets;
                    final float[] weights = brushes.weights;
                    for (int brushPointIndex = start; brushPointIndex < end; ++brushPointIndex) {
                        final int nodeIndex = dropletIndex + offsets[brushPointIndex];
                        final Cell cell = cells[nodeIndex];
                        final float weighedErodeAmount = amountToErode * weights[brushPointIndex];
                        final float deltaSediment = Math.min(cell.height, weighedErodeAmount);
                        this.erode(cell, nodeIndex, deltaSediment, strengthModifiers);
                        sediment += deltaSediment;
                    }
                } else {
                    final long[] brush = this.erosionBrushes[dropletIndex];
                    for (int brushPointIndex = 0; brushPointIndex < brush.length; ++brushPointIndex) {
                        final long brushPoint = brush[brushPointIndex];
                        final int nodeIndex = (int)(brushPoint >>> 32);
                        final Cell cell = cells[nodeIndex];
                        final float brushWeight = Float.intBitsToFloat((int)brushPoint);
                        final float weighedErodeAmount = amountToErode * brushWeight;
                        final float deltaSediment = Math.min(cell.height, weighedErodeAmount);
                        this.erode(cell, nodeIndex, deltaSediment, strengthModifiers);
                        sediment += deltaSediment;
                    }
                }
            }
            speed = (float)Math.sqrt(speed * speed + deltaHeight * 3.0f);
            water *= 0.99f;
            if (Float.isNaN(speed)) {
                speed = 0.0f;
            }
        }
    }
    
    private void initBrushes(final int size, final int radius) {
        final int[] xOffsets = new int[radius * radius * 4];
        final int[] yOffsets = new int[radius * radius * 4];
        final float[] weights = new float[radius * radius * 4];
        float weightSum = 0.0f;
        int addIndex = 0;
        for (int i = 0; i < this.erosionBrushes.length; ++i) {
            final int centreX = i % size;
            final int centreY = i / size;
            if (centreY <= radius || centreY >= size - radius || centreX <= radius + 1 || centreX >= size - radius) {
                weightSum = 0.0f;
                addIndex = 0;
                for (int y = -radius; y <= radius; ++y) {
                    for (int x = -radius; x <= radius; ++x) {
                        final float sqrDst = (float)(x * x + y * y);
                        if (sqrDst < radius * radius) {
                            final int coordX = centreX + x;
                            final int coordY = centreY + y;
                            if (coordX >= 0 && coordX < size && coordY >= 0 && coordY < size) {
                                final float weight = 1.0f - (float)Math.sqrt(sqrDst) / radius;
                                weightSum += weight;
                                weights[addIndex] = weight;
                                xOffsets[addIndex] = x;
                                yOffsets[addIndex] = y;
                                ++addIndex;
                            }
                        }
                    }
                }
            }
            final int numEntries = addIndex;
            this.erosionBrushes[i] = new long[numEntries];
            for (int j = 0; j < numEntries; ++j) {
                int index = (yOffsets[j] + centreY) * size + xOffsets[j] + centreX;
                float weight = weights[j] / weightSum;
                this.erosionBrushes[i][j] = ((long)index << 32) | (Float.floatToRawIntBits(weight) & 0xFFFFFFFFL);
            }
        }
    }

    private Brush[] initSharedBrushes(final int size, final int radius) {
        final Brush[] brushes = new Brush[size * size];
        final Brush[] templates = new Brush[1 << 12];
        for(int index = 0; index < brushes.length; index++) {
            int centerX = index % size;
            int centerY = index / size;
            int left = Math.min(centerX, radius);
            int right = Math.min(size - 1 - centerX, radius);
            int top = Math.min(centerY, radius);
            int bottom = Math.min(size - 1 - centerY, radius);
            int key = left | right << 3 | top << 6 | bottom << 9;
            Brush brush = templates[key];
            if(brush == null) {
                brush = this.createSharedBrush(centerX, centerY, size, radius);
                templates[key] = brush;
            }
            brushes[index] = brush;
        }
        return brushes;
    }

    private FlatBrushes initFlatSharedBrushes(final int size, final int radius) {
        Brush[] brushes = this.initSharedBrushes(size, radius);
        IdentityHashMap<Brush, Integer> startsByBrush = new IdentityHashMap<>();
        List<Brush> uniqueBrushes = new ArrayList<>();
        int pointCount = 0;
        for(Brush brush : brushes) {
            if(!startsByBrush.containsKey(brush)) {
                startsByBrush.put(brush, pointCount);
                uniqueBrushes.add(brush);
                pointCount += brush.offsets.length;
            }
        }

        int[] starts = new int[brushes.length];
        byte[] lengths = new byte[brushes.length];
        int[] offsets = new int[pointCount];
        float[] weights = new float[pointCount];
        for(Brush brush : uniqueBrushes) {
            int start = startsByBrush.get(brush);
            System.arraycopy(brush.offsets, 0, offsets, start, brush.offsets.length);
            System.arraycopy(brush.weights, 0, weights, start, brush.weights.length);
        }
        for(int index = 0; index < brushes.length; index++) {
            Brush brush = brushes[index];
            starts[index] = startsByBrush.get(brush);
            lengths[index] = (byte)brush.offsets.length;
        }
        return new FlatBrushes(starts, lengths, offsets, weights);
    }

    private Brush createSharedBrush(final int centerX, final int centerY, final int size, final int radius) {
        final int[] offsets = new int[radius * radius * 4];
        final float[] weights = new float[radius * radius * 4];
        float weightSum = 0.0F;
        int count = 0;
        for(int y = -radius; y <= radius; y++) {
            for(int x = -radius; x <= radius; x++) {
                float distance2 = x * x + y * y;
                if(distance2 < radius * radius) {
                    int coordX = centerX + x;
                    int coordY = centerY + y;
                    if(coordX >= 0 && coordX < size && coordY >= 0 && coordY < size) {
                        float weight = 1.0F - (float)Math.sqrt(distance2) / radius;
                        weightSum += weight;
                        weights[count] = weight;
                        offsets[count] = y * size + x;
                        count++;
                    }
                }
            }
        }
        int[] relativeOffsets = Arrays.copyOf(offsets, count);
        float[] normalizedWeights = new float[count];
        for(int index = 0; index < count; index++) {
            normalizedWeights[index] = weights[index] / weightSum;
        }
        return new Brush(relativeOffsets, normalizedWeights);
    }
    
    @Nullable
    private StrengthModifierBuffer acquireStrengthModifiers(Cell[] cells, int cellCount) {
		if(!this.cacheStrengthModifiers) {
			return null;
		}
		StrengthModifierBuffer buffer = this.strengthModifierPool.pollFirst();
		if(buffer == null || buffer.values.length < cellCount) {
			buffer = new StrengthModifierBuffer(cellCount);
		}
		buffer.prepare(cells, cellCount, this.modifier);
		return buffer;
	}

    private void deposit(final Cell cell, final int index, final float amount, @Nullable final float[] strengthModifiers) {
        if (!cell.erosionMask) {
            final float change = strengthModifiers == null ? this.modifier.modify(cell, amount) : this.modifier.modifyWithStrength(cell, amount, strengthModifiers[index]);
            cell.height += change;
            cell.sediment += change;
        }
    }

    private void erode(final Cell cell, final int index, final float amount, @Nullable final float[] strengthModifiers) {
        if (!cell.erosionMask) {
            final float change = strengthModifiers == null ? this.modifier.modify(cell, amount) : this.modifier.modifyWithStrength(cell, amount, strengthModifiers[index]);
            cell.height -= change;
            cell.heightErosion -= change;
        }
    }

    public static IntFunction<Erosion> factory(final GeneratorContext context) {
		boolean cacheStrengthModifiers = context.preset.world().noiseEngine == WorldSettings.NoiseEngine.LEGACY_V2;
        return new Factory(context.seed.root(), context.preset.filters(), context.levels, cacheStrengthModifiers);
    }
    
    private static class TerrainPos
    {
        private float height;
        private float gradientX;
        private float gradientY;
        
        private TerrainPos at(final Cell[] nodes, final int mapSize, final float posX, final float posY) {
            final int coordX = (int)posX;
            final int coordY = (int)posY;
            final float x = posX - coordX;
            final float y = posY - coordY;
            final int nodeIndexNW = coordY * mapSize + coordX;
            final int nodeIndexSW = nodeIndexNW + mapSize;
            final float heightNW = nodes[nodeIndexNW].height;
            final float heightNE = nodes[nodeIndexNW + 1].height;
            final float heightSW = nodes[nodeIndexSW].height;
            final float heightSE = nodes[nodeIndexSW + 1].height;
            final float inverseX = 1.0f - x;
            final float inverseY = 1.0f - y;
            this.gradientX = (heightNE - heightNW) * inverseY + (heightSE - heightSW) * y;
            this.gradientY = (heightSW - heightNW) * inverseX + (heightSE - heightNE) * x;
            this.height = heightNW * inverseX * inverseY + heightNE * x * inverseY + heightSW * inverseX * y + heightSE * x * y;
            return this;
        }

        private void reset() {
            this.height = 0.0f;
            this.gradientX = 0.0f;
            this.gradientY = 0.0f;
        }
    }

    private static class StrengthModifierBuffer {
        private final float[] values;
        private Terrain[] terrainKeys = new Terrain[64];
        private float[] terrainModifiers = new float[64];
        private int[] terrainGenerations = new int[64];
        private int generation;

        private StrengthModifierBuffer(int size) {
            this.values = new float[size];
        }

        private void prepare(Cell[] cells, int cellCount, Modifier modifier) {
            this.nextGeneration();
            for(int index = 0; index < cellCount; index++) {
                Cell cell = cells[index];
                float terrainModifier = this.getTerrainModifier(cell.terrain);
                this.values[index] = modifier.getStrengthModifier(cell, terrainModifier);
            }
        }

        private float getTerrainModifier(Terrain terrain) {
            int id = terrain.getId();
            if(id < 0) {
                return terrain.erosionModifier();
            }
            this.ensureTerrainCapacity(id + 1);
            if(this.terrainGenerations[id] != this.generation || this.terrainKeys[id] != terrain) {
                this.terrainKeys[id] = terrain;
                this.terrainModifiers[id] = terrain.erosionModifier();
                this.terrainGenerations[id] = this.generation;
            }
            return this.terrainModifiers[id];
        }

        private void ensureTerrainCapacity(int capacity) {
            if(capacity <= this.terrainKeys.length) {
                return;
            }
            int size = Math.max(capacity, this.terrainKeys.length << 1);
            this.terrainKeys = Arrays.copyOf(this.terrainKeys, size);
            this.terrainModifiers = Arrays.copyOf(this.terrainModifiers, size);
            this.terrainGenerations = Arrays.copyOf(this.terrainGenerations, size);
        }

        private void nextGeneration() {
            this.generation++;
            if(this.generation == 0) {
                Arrays.fill(this.terrainGenerations, 0);
                this.generation = 1;
            }
        }
    }

    private record Brush(int[] offsets, float[] weights) {
    }

    private record FlatBrushes(int[] starts, byte[] lengths, int[] offsets, float[] weights) {
    }
    
    private static class Factory implements IntFunction<Erosion> {
        private static final int SEED_OFFSET = 12768;
        private final int seed;
        private final Modifier modifier;
        private final FilterSettings.Erosion settings;
		private final boolean cacheStrengthModifiers;
        
        private Factory(final int seed, final FilterSettings filters, final Levels levels, final boolean cacheStrengthModifiers) {
            this.seed = seed + 12768;
            this.settings = filters.erosion.copy();
            this.modifier = Modifier.range(levels.ground, levels.ground(15));
			this.cacheStrengthModifiers = cacheStrengthModifiers;
        }
        
        @Override
        public Erosion apply(final int size) {
            return new Erosion(this.seed, size, this.settings, this.modifier, this.cacheStrengthModifiers);
        }
    }
}

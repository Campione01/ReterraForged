package raccoonman.reterraforged.world.worldgen.cell.terrain.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

import net.minecraft.core.HolderGetter;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseData;
import raccoonman.reterraforged.data.worldgen.preset.PresetTerrainTypeNoise;
import raccoonman.reterraforged.data.worldgen.preset.settings.TerrainSettings;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.RegionConfig;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Populators;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.cell.terrain.populator.TerrainPopulator;
import raccoonman.reterraforged.world.worldgen.cell.terrain.populator.VolcanoPopulator;
import raccoonman.reterraforged.world.worldgen.cell.terrain.region.RegionSelector;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.util.Seed;

public class TerrainProvider {
	static final int STEPPE_MASK = 1 << 0;
	static final int PLAINS_MASK = 1 << 1;
	static final int HILLS_MASK = 1 << 2;
	static final int DALES_MASK = 1 << 3;
	static final int PLATEAU_MASK = 1 << 4;
	static final int BADLANDS_MASK = 1 << 5;
	static final int TORRIDONIAN_MASK = 1 << 6;
	static final int MOUNTAINS_MASK = 1 << 7;
	static final int VOLCANO_MASK = 1 << 8;

	private static final float DEFAULT_STEPPE_WEIGHT = 1.0F;
	private static final float DEFAULT_PLAINS_WEIGHT = 2.0F;
	private static final float DEFAULT_HILLS_WEIGHT = 2.0F;
	private static final float DEFAULT_DALES_WEIGHT = 1.5F;
	private static final float DEFAULT_PLATEAU_WEIGHT = 1.5F;
	private static final float DEFAULT_BADLANDS_WEIGHT = 1.0F;
	private static final float DEFAULT_TORRIDONIAN_WEIGHT = 2.0F;
	private static final float DEFAULT_MOUNTAINS_WEIGHT = 2.5F;
	private static final float DEFAULT_VOLCANO_WEIGHT = 5.0F;

    public static List<CellPopulator> generateTerrain(Seed seed, TerrainSettings settings, RegionConfig config, Levels levels, HolderGetter<Noise> noiseLookup) {
    	TerrainSettings.General general = settings.general;
    	float verticalScale = general.globalVerticalScale;
    	boolean fancyMountains = general.fancyMountains;
    	boolean legacyMountainScaling = general.legacyMountainScaling;
    	Seed terrainSeed = seed.offset(general.terrainSeedOffset);
    	
    	Noise ground = PresetNoiseData.getNoise(noiseLookup, PresetTerrainTypeNoise.GROUND);
    	
    	List<TerrainPopulator> mixable = new ArrayList<>();
    	mixable.add(Populators.makeSteppe(terrainSeed, ground, settings.steppe));
    	mixable.add(Populators.makePlains(terrainSeed, ground, settings.plains, verticalScale));
        mixable.add(Populators.makeDales(terrainSeed, ground, settings.dales));
        mixable.add(Populators.makeHills1(terrainSeed, ground, settings.hills, verticalScale));
        mixable.add(Populators.makeHills2(terrainSeed, ground, settings.hills, verticalScale));
        mixable.add(Populators.makeTorridonian(terrainSeed, ground, settings.torridonian));
        mixable.add(Populators.makePlateau(terrainSeed, ground, settings.plateau, verticalScale));
        mixable.add(Populators.makeBadlands(terrainSeed, ground, settings.badlands));
        
        List<CellPopulator> unmixable = new ArrayList<>();
        unmixable.add(Populators.makeBadlands(terrainSeed, ground, settings.badlands));
        unmixable.add(Populators.makeMountains(terrainSeed, ground, settings.mountains, settings.mountains.horizontalScale, verticalScale, fancyMountains, legacyMountainScaling));
        unmixable.add(Populators.makeMountains2(terrainSeed, ground, settings.mountains, verticalScale, fancyMountains, legacyMountainScaling));
        unmixable.add(Populators.makeMountains3(terrainSeed, ground, settings.mountains, verticalScale, fancyMountains, legacyMountainScaling));
        unmixable.add(new VolcanoPopulator(terrainSeed, config, levels, settings.volcano));

        List<TerrainPopulator> mixed = combine(mixable, (t1, t2) -> {
        	return combine(t1, t2, terrainSeed, levels, config.scale() / 2);
        });

        List<CellPopulator> result = new ArrayList<>();
        result.addAll(mixed);
        result.addAll(unmixable);
        List<SelectionWeight> selectionWeights = selectionWeights(settings);
        if (result.size() != selectionWeights.size()) {
            throw new IllegalStateException("Terrain populator and selection weight counts differ");
        }
        List<CellPopulator> weightedResult = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); ++i) {
            SelectionWeight selectionWeight = selectionWeights.get(i);
            weightedResult.add(RegionSelector.weighted(
                result.get(i),
                selectionWeight.legacyMultiplicity(),
                selectionWeight.configuredWeight(),
                selectionWeight.defaultWeight()
            ));
        }
        Collections.shuffle(weightedResult, new Random(terrainSeed.next()));
        return weightedResult;
    }

    static List<SelectionWeight> selectionWeights(TerrainSettings settings) {
        List<SelectionWeight> mixable = new ArrayList<>();
        mixable.add(SelectionWeight.source(settings.steppe.weight, DEFAULT_STEPPE_WEIGHT, STEPPE_MASK));
        mixable.add(SelectionWeight.source(settings.plains.weight, DEFAULT_PLAINS_WEIGHT, PLAINS_MASK));
        mixable.add(SelectionWeight.source(settings.dales.weight, DEFAULT_DALES_WEIGHT, DALES_MASK));
        mixable.add(SelectionWeight.source(settings.hills.weight, DEFAULT_HILLS_WEIGHT, HILLS_MASK));
        mixable.add(SelectionWeight.source(settings.hills.weight, DEFAULT_HILLS_WEIGHT, HILLS_MASK));
        mixable.add(SelectionWeight.source(settings.torridonian.weight, DEFAULT_TORRIDONIAN_WEIGHT, TORRIDONIAN_MASK));
        mixable.add(SelectionWeight.source(settings.plateau.weight, DEFAULT_PLATEAU_WEIGHT, PLATEAU_MASK));
        mixable.add(SelectionWeight.source(settings.badlands.weight, DEFAULT_BADLANDS_WEIGHT, BADLANDS_MASK));

        List<SelectionWeight> result = new ArrayList<>(combine(mixable, SelectionWeight::combine));
        result.add(SelectionWeight.source(settings.badlands.weight, DEFAULT_BADLANDS_WEIGHT, BADLANDS_MASK));
        result.add(SelectionWeight.source(settings.mountains.weight, DEFAULT_MOUNTAINS_WEIGHT, MOUNTAINS_MASK));
        result.add(SelectionWeight.source(settings.mountains.weight, DEFAULT_MOUNTAINS_WEIGHT, MOUNTAINS_MASK));
        result.add(SelectionWeight.source(settings.mountains.weight, DEFAULT_MOUNTAINS_WEIGHT, MOUNTAINS_MASK));
        result.add(SelectionWeight.source(settings.volcano.weight, DEFAULT_VOLCANO_WEIGHT, VOLCANO_MASK));
        return result;
    }
    
    static TerrainPopulator combine(TerrainPopulator tp1, TerrainPopulator tp2, Seed seed, Levels levels, int scale) {
        Terrain type = TerrainType.registerComposite(tp1.type(), tp2.type());
        Noise selector = Noises.perlin(seed.next(), scale, 1);
        selector = Noises.warpPerlin(selector, seed.next(), scale / 2, 2, scale / 2.0F);

        boolean identityScales = tp1.baseScale() == 1.0F
            && tp1.heightScale() == 1.0F
            && tp2.baseScale() == 1.0F
            && tp2.heightScale() == 1.0F;
        Noise firstHeight = scale(tp1.height(), tp1.heightScale());
        Noise secondHeight = scale(tp2.height(), tp2.heightScale());
        Noise height = Noises.blend(selector, firstHeight, secondHeight, 0.5F, 0.25F);
        height = Noises.max(height, Noises.zero());
        
        Noise erosion = Noises.blend(selector, tp1.erosion(), tp2.erosion(), 0.5F, 0.25F);
        Noise weirdness = Noises.threshold(selector, tp1.weirdness(), tp2.weirdness(), 0.5F);

        float weight = (tp1.weight() + tp2.weight()) / 2.0F;
        if(identityScales) {
            return new TerrainPopulator(type, Noises.constant(levels.ground), height, erosion, weirdness, weight);
        }
        Noise base = Noises.blend(
            selector,
            scale(tp1.base(), tp1.baseScale()),
            scale(tp2.base(), tp2.baseScale()),
            0.5F,
            0.25F
        );
        return new TerrainPopulator(type, base, height, erosion, weirdness, weight);
    }

    private static Noise scale(Noise input, float scale) {
        return scale == 1.0F ? input : Noises.mul(input, scale);
    }

    private static <T> List<T> combine(List<T> input, BiFunction<T, T, T> operator) {
        int length = input.size();
        for (int i = 1; i < input.size(); ++i) {
            length += input.size() - i;
        }
        List<T> result = new ArrayList<T>(length);
        for (int j = 0; j < length; ++j) {
            result.add(null);
        }
        int j = 0;
        int k = input.size();
        while (j < input.size()) {
            T t1 = input.get(j);
            result.set(j, t1);
            for (int l = j + 1; l < input.size(); ++l, ++k) {
                T t2 = input.get(l);
                T t3 = operator.apply(t1, t2);
                result.set(k, t3);
            }
            ++j;
        }
        return result;
    }

    static record SelectionWeight(double configuredWeight, double defaultWeight, float legacyDefaultWeight, int sourceMask) {

        SelectionWeight {
            if (!Double.isFinite(configuredWeight) || configuredWeight < 0.0D) {
                configuredWeight = 0.0D;
            }
        }

        static SelectionWeight source(float configuredWeight, float defaultWeight, int sourceMask) {
            return new SelectionWeight(configuredWeight, defaultWeight, defaultWeight, sourceMask);
        }

        static SelectionWeight combine(SelectionWeight first, SelectionWeight second) {
            // A geometric mean preserves proportional scaling and reaches zero when either source is disabled.
            double configuredWeight = Math.sqrt(first.configuredWeight * second.configuredWeight);
            double defaultWeight = Math.sqrt(first.defaultWeight * second.defaultWeight);
            float legacyDefaultWeight = (first.legacyDefaultWeight + second.legacyDefaultWeight) / 2.0F;
            return new SelectionWeight(configuredWeight, defaultWeight, legacyDefaultWeight, first.sourceMask | second.sourceMask);
        }

        int legacyMultiplicity() {
            return Math.max(1, Math.round(this.legacyDefaultWeight));
        }
    }
}

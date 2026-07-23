package raccoonman.reterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.StringRepresentable;
import raccoonman.reterraforged.world.worldgen.cell.continent.IslandPopulator;
import raccoonman.reterraforged.world.worldgen.noise.function.DistanceFunction;

public class WorldSettings {
	public static final NoiseEngine DEFAULT_NOISE_ENGINE = NoiseEngine.LEGACY_V2;

	public static final Codec<WorldSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		NoiseEngine.CODEC.optionalFieldOf("noiseEngine", DEFAULT_NOISE_ENGINE).forGetter((o) -> o.noiseEngine),
		Continent.CODEC.fieldOf("continent").forGetter((o) -> o.continent),
		ControlPoints.CODEC.fieldOf("controlPoints").forGetter((o) -> o.controlPoints),
		Properties.CODEC.fieldOf("properties").forGetter((o) -> o.properties)
	).apply(instance, WorldSettings::new));
	
	public NoiseEngine noiseEngine;
    public Continent continent;
    public ControlPoints controlPoints;
    public Properties properties;
    
    public WorldSettings(Continent continent, ControlPoints controlPoints, Properties properties) {
		this(DEFAULT_NOISE_ENGINE, continent, controlPoints, properties);
	}

	public WorldSettings(NoiseEngine noiseEngine, Continent continent, ControlPoints controlPoints, Properties properties) {
		this.noiseEngine = noiseEngine != null ? noiseEngine : DEFAULT_NOISE_ENGINE;
        this.continent = continent;
        this.controlPoints = controlPoints;
        this.properties = properties;
    }
    
    public WorldSettings copy() {
		return new WorldSettings(this.noiseEngine, this.continent.copy(), this.controlPoints.copy(), this.properties.copy());
    }

	public enum NoiseEngine implements StringRepresentable {
		LEGACY("legacy"),
		QUICK_V2("quick_v2"),
		LEGACY_V2("legacy_v2");

		public static final Codec<NoiseEngine> CODEC = StringRepresentable.fromEnum(NoiseEngine::values);
		private final String name;

		NoiseEngine(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return this.name;
		}
	}
    
    public static class Continent {
    	public static final Codec<Continent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
    		ContinentType.CODEC.fieldOf("continentType").forGetter((o) -> o.continentType),
    		DistanceFunction.CODEC.optionalFieldOf("continentShape", DistanceFunction.EUCLIDEAN).forGetter((o) -> o.continentShape),
    		Codec.INT.fieldOf("continentScale").forGetter((o) -> o.continentScale),
    		Codec.FLOAT.fieldOf("continentJitter").forGetter((o) -> o.continentJitter),
    		Codec.FLOAT.optionalFieldOf("continentSkipping", 0.25F).forGetter((o) -> o.continentSkipping),
    		Codec.FLOAT.optionalFieldOf("continentSizeVariance", 0.25F).forGetter((o) -> o.continentSizeVariance),
    		Codec.INT.optionalFieldOf("continentNoiseOctaves", 5).forGetter((o) -> o.continentNoiseOctaves),
    		Codec.FLOAT.optionalFieldOf("continentNoiseGain", 0.26F).forGetter((o) -> o.continentNoiseGain),
    		Codec.FLOAT.optionalFieldOf("continentNoiseLacunarity", 4.33F).forGetter((o) -> o.continentNoiseLacunarity)
    	).apply(instance, Continent::new));
    	
        public ContinentType continentType;
        public DistanceFunction continentShape;
        public int continentScale;
        public float continentJitter;
        public float continentSkipping;
        public float continentSizeVariance;
        public int continentNoiseOctaves;
        public float continentNoiseGain;
        public float continentNoiseLacunarity;
        
        public Continent(ContinentType continentType, DistanceFunction continentShape, int continentScale, float continentJitter, float continentSkipping, float continentSizeVariance, int continentNoiseOctaves, float continentNoiseGain, float continentNoiseLacunarity) {
            this.continentType = continentType;
            this.continentShape = continentShape;
            this.continentScale = continentScale;
            this.continentJitter = continentJitter;
            this.continentSkipping = continentSkipping;
            this.continentSizeVariance = continentSizeVariance;
            this.continentNoiseOctaves = continentNoiseOctaves;
            this.continentNoiseGain = continentNoiseGain;
            this.continentNoiseLacunarity = continentNoiseLacunarity;
        }
        
        public Continent copy() {
        	return new Continent(this.continentType, this.continentShape, this.continentScale, this.continentJitter, this.continentSkipping, this.continentSizeVariance, this.continentNoiseOctaves, this.continentNoiseGain, this.continentNoiseLacunarity);
        }
    }
    
    public static class ControlPoints {
    	public static final Codec<ControlPoints> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        	Codec.FLOAT.optionalFieldOf("islandInland", IslandPopulator.DEFAULT_INLAND_POINT).forGetter((o) -> o.islandInland),
        	Codec.FLOAT.optionalFieldOf("islandCoast", IslandPopulator.DEFAULT_COAST_POINT).forGetter((o) -> o.islandCoast),
    		Codec.FLOAT.fieldOf("deepOcean").forGetter((o) -> o.deepOcean),
    		Codec.FLOAT.fieldOf("shallowOcean").forGetter((o) -> o.shallowOcean),
    		Codec.FLOAT.fieldOf("beach").forGetter((o) -> o.beach),
    		Codec.FLOAT.fieldOf("coast").forGetter((o) -> o.coast),
    		Codec.FLOAT.fieldOf("inland").forGetter((o) -> o.inland)
        ).apply(instance, ControlPoints::new));

    	public float islandInland;
    	public float islandCoast;
        public float deepOcean;
        public float shallowOcean;
        public float beach;
        public float coast;
        public float inland;
        
        public ControlPoints(float islandInland, float islandCoast, float deepOcean, float shallowOcean, float beach, float coast, float inland) {
        	this.islandInland = islandInland;
        	this.islandCoast = islandCoast;
            this.deepOcean = deepOcean;
            this.shallowOcean = shallowOcean;
            this.beach = beach;
            this.coast = coast;
            this.inland = inland;
        }
        
        public float coastMarker() {
        	return this.coast + (this.inland - this.coast) / 2.0F;
        }
        
        public ControlPoints copy() {
        	return new ControlPoints(this.islandInland, this.islandCoast, this.deepOcean, this.shallowOcean, this.beach, this.coast, this.inland);
        }
    }
    
    public static class Properties {
    	public static final int DEFAULT_OCEAN_DEPTH = 64;
    	
    	public static final Codec<Properties> CODEC = RecordCodecBuilder.create(instance -> instance.group(
    		SpawnType.CODEC.fieldOf("spawnType").forGetter((o) -> o.spawnType),
    		Codec.INT.fieldOf("worldHeight").forGetter((o) -> o.worldHeight),
    		Codec.INT.optionalFieldOf("worldDepth", 64).forGetter((o) -> o.worldDepth),
    		Codec.INT.fieldOf("seaLevel").forGetter((o) -> o.seaLevel),
    		Codec.INT.optionalFieldOf("oceanDepth", DEFAULT_OCEAN_DEPTH).forGetter((o) -> o.oceanDepth),
    		Codec.INT.optionalFieldOf("lavaLevel", -54).forGetter((o) -> o.lavaLevel)
    	).apply(instance, Properties::new));
    	
        public SpawnType spawnType;
        public int worldHeight;
        public int worldDepth;
        public int seaLevel;
        public int oceanDepth;
        public int lavaLevel;
        
        public Properties(SpawnType spawnType, int worldHeight, int worldDepth, int seaLevel, int lavaLevel) {
        	this(spawnType, worldHeight, worldDepth, seaLevel, DEFAULT_OCEAN_DEPTH, lavaLevel);
        }
        
        public Properties(SpawnType spawnType, int worldHeight, int worldDepth, int seaLevel, int oceanDepth, int lavaLevel) {
        	this.spawnType = spawnType;
        	this.worldHeight = worldHeight;
        	this.worldDepth = worldDepth;
        	this.seaLevel = seaLevel;
        	this.oceanDepth = oceanDepth;
        	this.lavaLevel = lavaLevel;
        }
        
        public Properties copy() {
        	return new Properties(this.spawnType, this.worldHeight, this.worldDepth, this.seaLevel, this.oceanDepth, this.lavaLevel);
        }
        
        @Deprecated
        public int terrainScaler() {
        	return Math.min(this.worldHeight, 256);
        }
    }
}

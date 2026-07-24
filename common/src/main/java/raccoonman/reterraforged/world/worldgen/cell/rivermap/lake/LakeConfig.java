package raccoonman.reterraforged.world.worldgen.cell.rivermap.lake;

import java.util.Random;

import raccoonman.reterraforged.data.worldgen.preset.settings.RiverSettings;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public class LakeConfig {
    public static final float DEFAULT_PRESET_DISTANCE_MIN = 0.0F;
    public static final float DEFAULT_PRESET_DISTANCE_MAX = 0.03F;

    public float depth;
    public float chance;
    public float sizeMin;
    public float sizeMax;
    public float sizeRange;
    public float bankMin;
    public float bankMax;
    public float distanceMin;
    public float distanceMax;
    
    private LakeConfig(Builder builder) {
        this.depth = builder.depth;
        this.chance = builder.chance;
        this.sizeMin = builder.sizeMin;
        this.sizeMax = builder.sizeMax;
        this.sizeRange = Math.max(0, this.sizeMax - this.sizeMin);
        this.bankMin = builder.bankMin;
        this.bankMax = builder.bankMax;
        float distanceMin = Math.min(builder.distanceMin, builder.distanceMax);
        float distanceMax = Math.max(builder.distanceMin, builder.distanceMax);
        this.distanceMin = NoiseUtil.clamp(distanceMin, 0.0F, 1.0F);
        this.distanceMax = NoiseUtil.clamp(distanceMax, this.distanceMin, 1.0F);
    }

    public float nextStartDistance(Random random) {
        return this.distanceMin + random.nextFloat() * (this.distanceMax - this.distanceMin);
    }

    public boolean usesDefaultPresetDistance() {
        return this.distanceMin == DEFAULT_PRESET_DISTANCE_MIN && this.distanceMax == DEFAULT_PRESET_DISTANCE_MAX;
    }
    
    public static LakeConfig of(RiverSettings.Lake settings, Levels levels) {
        Builder builder = new Builder();
        builder.chance = settings.chance;
        builder.sizeMin = settings.sizeMin;
        builder.sizeMax = settings.sizeMax;
        builder.depth = levels.water(-settings.depth);
        builder.distanceMin = settings.minStartDistance;
        builder.distanceMax = settings.maxStartDistance;
        builder.bankMin = levels.water(settings.minBankHeight);
        builder.bankMax = levels.water(settings.maxBankHeight);
        return new LakeConfig(builder);
    }
    
    public static class Builder {
        public float chance;
        public float depth;
        public float sizeMin;
        public float sizeMax;
        public float bankMin;
        public float bankMax;
        public float distanceMin;
        public float distanceMax;
        
        public Builder() {
            this.depth = 10.0F;
            this.sizeMin = 30.0F;
            this.sizeMax = 100.0F;
            this.bankMin = 1.0F;
            this.bankMax = 8.0F;
            this.distanceMin = 0.025F;
            this.distanceMax = 0.05F;
        }
    }
}

package raccoonman.reterraforged.world.worldgen.cell.rivermap.wetland;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.fade.RiverTerrainFade;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.util.Boundsf;

public class Wetland {
    private static final float VALLEY = 0.65F;
    private static final float POOLS = 0.7F;
    private static final float BANKS = POOLS - VALLEY;

    private Vec2f a;
    private Vec2f b;
    private float radius;
    private float radius2;
    private float bed;
    private float banks;
    private float moundMin;
    private float moundMax;
    private float moundVariance;
    private float mountainErosionThreshold;
    private Noise moundShape;
    private Noise moundHeight;
    private Noise terrainEdge;
    
    private float fadeStartHeight;
    private float fadeEndHeight;
    
    public Wetland(int seed, Vec2f a, Vec2f b, float radius, Levels levels) {
        this.a = a;
        this.b = b;
        this.radius = radius;
        this.radius2 = radius * radius;
        this.bed = levels.water(-1) - 0.5F / (float)levels.scale(1.0F); 
        this.banks = levels.ground(3);
        this.moundMin = levels.water(1);
        this.moundMax = levels.water(2);
        this.moundVariance = this.moundMax - this.moundMin;
        this.mountainErosionThreshold = levels.water(1) + 2.0F / 1184.0F;
        
        this.moundShape = Noises.map(Noises.clamp(Noises.perlin(++seed, 10, 1), 0.3F, 0.6F), 0.0F, 1.0F);
        this.moundHeight = Noises.map(Noises.clamp(Noises.simplex(++seed, 20, 1), 0.0F, 0.3F), 0.0F, 1.0F);
        this.terrainEdge = Noises.map(Noises.clamp(Noises.perlin(++seed, 8, 1), 0.2F, 0.8F), 0.0F, 0.9F);
        
        this.fadeStartHeight = levels.scale(300);
        this.fadeEndHeight = levels.scale(360);
    }
    
    public void apply(Cell cell, float rx, float rz, float x, float z) {
        if (cell.height < this.bed) {
            return;
        }
        float d2 = distToLineSq(rx, rz, this.a.x(), this.a.y(), this.b.x(), this.b.y());
        float dist = 1.0F - d2 / this.radius2;
        if (dist <= 0.0F) {
            return;
        }
        float heightFade = RiverTerrainFade.heightFade(cell.height, this.fadeStartHeight, this.fadeEndHeight);
        if (heightFade <= 0.0F) {
            return;
        }
        boolean mountain = RiverTerrainFade.isMountain(cell);
        float valleyFade = RiverTerrainFade.valleyFade(cell, heightFade);
        if (mountain) {
            // Keep the pre-carve protection footprint; the new squared falloff is wider.
            if (cell.height > this.mountainErosionThreshold
                    && (1.0F - (float)Math.sqrt(d2) / this.radius) * valleyFade > 0.05F) {
                cell.erosionMask = true;
            }
            valleyFade = heightFade * RiverTerrainFade.MOUNTAIN_VALLEY_FADE;
        }
        float valleyAlpha = NoiseUtil.map(dist, 0.0F, VALLEY, VALLEY) * valleyFade;
        if (cell.height > this.banks) {
            cell.height = NoiseUtil.lerp(cell.height, this.banks, valleyAlpha);
        }
        if (!mountain) {
            float poolsAlpha = NoiseUtil.map(dist, VALLEY, POOLS, BANKS) * RiverTerrainFade.bedFade(cell, heightFade);
            if (cell.height > this.bed && cell.height <= this.banks) {
                cell.height = NoiseUtil.lerp(cell.height, this.bed, poolsAlpha);
            }
            if (poolsAlpha >= 1.0F) {
                cell.erosionMask = true;
            }
            if (dist > VALLEY && poolsAlpha > this.terrainEdge.compute(x, z, 0)) {
                cell.terrain = TerrainType.WETLAND;
            }
            if (cell.height >= this.bed && cell.height < this.moundMax) {
                float shapeAlpha = this.moundShape.compute(x, z, 0) * poolsAlpha;
                float mounds = this.moundMin + this.moundHeight.compute(x, z, 0) * this.moundVariance;
                cell.height = NoiseUtil.lerp(cell.height, mounds, shapeAlpha);
            }
        }
        cell.riverMask = Math.min(cell.riverMask, 1.0F - valleyAlpha);
    }

    public void recordBounds(Boundsf.Builder builder) {
        builder.record(Math.min(this.a.x(), this.b.x()) - this.radius, Math.min(this.a.y(), this.b.y()) - this.radius);
        builder.record(Math.max(this.a.x(), this.b.x()) + this.radius, Math.max(this.a.y(), this.b.y()) + this.radius);
    }

    private static float distToLineSq(float px, float py, float l1x, float l1y, float l2x, float l2y) {
        float l2_l1_x = l2x - l1x;
        float l2_l1_y = l2y - l1y;
        float lengthSq = l2_l1_x * l2_l1_x + l2_l1_y * l2_l1_y;
        if (lengthSq == 0.0F) {
            float dx = px - l1x;
            float dy = py - l1y;
            return dx * dx + dy * dy;
        }
        
        float t = ((px - l1x) * l2_l1_x + (py - l1y) * l2_l1_y) / lengthSq;
        t = Math.max(0.0F, Math.min(1.0F, t));
        
        float projectionX = l1x + t * l2_l1_x;
        float projectionY = l1y + t * l2_l1_y;
        
        float dx = px - projectionX;
        float dy = py - projectionY;
        return dx * dx + dy * dy;
    }
}

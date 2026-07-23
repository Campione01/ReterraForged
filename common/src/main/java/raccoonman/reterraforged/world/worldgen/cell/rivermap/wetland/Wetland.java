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
    private Vec2f a;
    private Vec2f b;
    private float radius;
    private float radius2;
    private float bed;
    private float banks;
    private float moundMin;
    private float moundMax;
    private float moundVariance;
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
        this.banks = levels.water(1);
        this.moundMin = levels.water(0);
        this.moundMax = levels.water(1);
        this.moundVariance = this.moundMax - this.moundMin;
        
        this.moundShape = Noises.simplex(seed + 1, 10, 1);
        this.moundHeight = Noises.simplex(seed + 2, 20, 1);
        this.terrainEdge = Noises.perlin(seed + 3, 15, 1);
        
        this.fadeStartHeight = levels.scale(300);
        this.fadeEndHeight = levels.scale(360);
    }
    
    public void apply(Cell cell, float x, float z, float rx, float rz) {
        float dist2 = distToLineSq(x, z, this.a.x(), this.a.y(), this.b.x(), this.b.y());
        if (dist2 > this.radius2) {
            return;
        }
        float heightFade = RiverTerrainFade.heightFade(cell.height, this.fadeStartHeight, this.fadeEndHeight);
		boolean mountain = RiverTerrainFade.isMountain(cell);
		float mountainFade = RiverTerrainFade.mountainFade(cell, mountain);
        float valleyFade = RiverTerrainFade.valleyFade(heightFade, mountainFade);
        float poolsFade = RiverTerrainFade.bedFade(heightFade, mountainFade);

        float dist = (float)Math.sqrt(dist2);
        float valleyAlpha = 1.0F - dist / this.radius;
        valleyAlpha *= valleyFade;
        
        if (valleyAlpha <= 0.0F) {
            return;
        }
        
        float bankHeight = this.banks;
        float bedHeight = this.bed;

        if (mountain) {
            float wetlandThreshold = this.banks + (2.0F / 1184.0F); 
            
            if (cell.height > wetlandThreshold) {
                float delta = cell.height - wetlandThreshold;
                float blendSlope = 10.0F; 
                
                float blendFactor = delta * blendSlope;
                blendFactor = Math.min(1.0F, Math.max(0.0F, blendFactor));
                
                bankHeight = NoiseUtil.lerp(bankHeight, cell.height, blendFactor);
                bedHeight = NoiseUtil.lerp(bedHeight, cell.height, blendFactor);
                
                if (valleyAlpha > 0.05F) {
                    cell.erosionMask = true;
                }
            }
        }
        
        float rawShape = this.moundShape.computeRoot(x, z, 0);
        float shapeVal = NoiseUtil.clamp(rawShape, 0.0F, 1.0F);
        
        float poolsAlpha = valleyAlpha * shapeVal * poolsFade;
        poolsAlpha = NoiseUtil.clamp(poolsAlpha, 0.0F, 1.0F);
        
        if (cell.height > bedHeight && cell.height <= bankHeight) {
            cell.height = NoiseUtil.lerp(cell.height, bedHeight, poolsAlpha);
        }
        if (poolsAlpha >= 1.0F) {
            cell.erosionMask = true;
        }
        
        float edgeVal = NoiseUtil.clamp(this.terrainEdge.computeRoot(x, z, 0), 0.2F, 0.8F);
        if (dist > 0.65F && poolsAlpha > edgeVal) {
            cell.terrain = TerrainType.WETLAND;
        }
        
        if (cell.height >= bedHeight && cell.height < this.moundMax) {
            float shapeAlpha = shapeVal * poolsAlpha;
            
            float rawHeight = this.moundHeight.computeRoot(x, z, 0);
            float heightVal = NoiseUtil.clamp(rawHeight, 0.0F, 1.0F);
            
            float mounds = this.moundMin + heightVal * this.moundVariance;
            cell.height = NoiseUtil.lerp(cell.height, mounds, shapeAlpha);
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

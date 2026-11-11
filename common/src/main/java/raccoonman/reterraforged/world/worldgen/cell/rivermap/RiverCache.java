package raccoonman.reterraforged.world.worldgen.cell.rivermap;

import java.util.concurrent.TimeUnit;

import raccoonman.reterraforged.concurrent.cache.Cache;
import raccoonman.reterraforged.concurrent.cache.map.LongMap;
import raccoonman.reterraforged.concurrent.cache.map.StampedBoundLongMap;
import raccoonman.reterraforged.concurrent.cache.map.StampedLongMap;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

public class RiverCache {
    private static final int LATE_CAPACITY = 8;
    protected RiverGenerator generator;
    protected Cache<Rivermap> cache;
    // Set by close: the few river maps a closed context may still be asked for. The cache itself is no longer polled and stays empty.
    private volatile LongMap<Rivermap> late;

    public RiverCache(RiverGenerator generator) {
        this.cache = new Cache<>(32, 5L, 1L, TimeUnit.MINUTES, StampedLongMap::new);
        this.generator = generator;
    }

    public void close() {
        if(this.late == null) {
            this.late = new StampedBoundLongMap<>(LATE_CAPACITY);
        }
        try {
            this.cache.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.cache.clear();
    }

    public Rivermap getRivers(int x, int z) {
        long key = PosUtil.pack(x, z);
        LongMap<Rivermap> late = this.late;
        if(late != null) {
            return late.computeIfAbsent(key, this::generate);
        }
        Rivermap rivers = this.cache.computeIfAbsent(key, this::generate);
        if(this.late != null) {
            this.cache.clear();
        }
        return rivers;
    }

    private Rivermap generate(long id) {
        return this.generator.generateRivers(PosUtil.unpackLeft(id), PosUtil.unpackRight(id), id);
    }
}

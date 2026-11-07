package raccoonman.reterraforged.world.worldgen.surface;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.RandomState;
import raccoonman.reterraforged.RTFCommon;

/** Reports the actual manager for the first surface call of the server's Overworld. */
public final class StrataModeReporter {
    private static final AtomicReference<WeakReference<RandomState>> PENDING = new AtomicReference<>();

    private StrataModeReporter() {}

    public static void arm(RandomState overworld, boolean active) {
        PENDING.set(active ? new WeakReference<>(overworld) : null);
        if (!active) RTFCommon.LOGGER.info("RTF strata exclusions: mode=inactive");
    }

    public static void observe(RandomState state, BiomeManager manager) {
        var armed = PENDING.get();
        if (armed != null && armed.get() == state && PENDING.compareAndSet(armed, null)) {
            RTFCommon.LOGGER.info("RTF strata exclusions: mode={}",
                StrataBiomeExclusions.hasVanillaBounds(manager) ? "chunk_filter" : "exact");
        }
    }
}

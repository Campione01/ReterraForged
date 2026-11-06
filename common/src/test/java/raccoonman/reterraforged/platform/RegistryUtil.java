package raccoonman.reterraforged.platform;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

/** Binds the common-source platform placeholder for offline worldgen tests only. */
public final class RegistryUtil {
    private RegistryUtil() {}

    public static <T> Registry<T> createRegistry(ResourceKey<Registry<T>> key) {
        return new MappedRegistry<>(key, Lifecycle.stable());
    }
}

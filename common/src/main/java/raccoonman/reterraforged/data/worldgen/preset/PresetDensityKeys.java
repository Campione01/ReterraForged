package raccoonman.reterraforged.data.worldgen.preset;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.serialization.Lifecycle;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import raccoonman.reterraforged.RTFCommon;

/** The private Overworld density graph emitted by the RTF preset. */
final class PresetDensityKeys {
    private static final ResourceKey<DensityFunction> RIDGES_FOLDED = ResourceKey.create(Registries.DENSITY_FUNCTION,
        ResourceLocation.withDefaultNamespace("overworld/ridges_folded"));
    private static final Set<ResourceKey<DensityFunction>> VANILLA_COPIES = Set.of(
        NoiseRouterData.FACTOR, RIDGES_FOLDED, NoiseRouterData.SLOPED_CHEESE);
    private static final Map<ResourceKey<DensityFunction>, ResourceKey<DensityFunction>> PRIVATE_KEYS = Stream.concat(
        Stream.of(NoiseRouterData.BASE_3D_NOISE_OVERWORLD, NoiseRouterData.ENTRANCES, NoiseRouterData.NOODLE,
            NoiseRouterData.SPAGHETTI_2D, NoiseRouterData.CONTINENTS, NoiseRouterData.DEPTH, NoiseRouterData.EROSION,
            NoiseRouterData.JAGGEDNESS, NoiseRouterData.OFFSET, NoiseRouterData.RIDGES), VANILLA_COPIES.stream())
        .collect(Collectors.toUnmodifiableMap(key -> key, key -> ResourceKey.create(Registries.DENSITY_FUNCTION,
            RTFCommon.location(key.location().getPath()))));

    private PresetDensityKeys() {}

    static ResourceKey<DensityFunction> remapKey(ResourceKey<DensityFunction> key) {
        return PRIVATE_KEYS.getOrDefault(key, key);
    }

    static HolderGetter<DensityFunction> remapLookup(HolderGetter<DensityFunction> original) {
        return new HolderGetter<>() {
            @Override
            public Optional<Holder.Reference<DensityFunction>> get(ResourceKey<DensityFunction> key) {
                return original.get(remapKey(key));
            }

            @Override
            public Optional<HolderSet.Named<DensityFunction>> get(TagKey<DensityFunction> tag) {
                return original.get(tag);
            }
        };
    }

    static void copyVanilla(BootstrapContext<DensityFunction> ctx) {
        var density = remapLookup(ctx.lookup(Registries.DENSITY_FUNCTION));
        // Vanilla constructs the three copied graphs; its other registrations only resolve existing holders.
        NoiseRouterData.bootstrap(new BootstrapContext<DensityFunction>() {
            @Override
            public Holder.Reference<DensityFunction> register(ResourceKey<DensityFunction> key, DensityFunction value, Lifecycle lifecycle) {
                if (VANILLA_COPIES.contains(key)) {
                    return ctx.register(remapKey(key), value, lifecycle);
                }
                return density.getOrThrow(key);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> HolderGetter<T> lookup(ResourceKey<? extends Registry<? extends T>> registry) {
                return registry.equals(Registries.DENSITY_FUNCTION) ? (HolderGetter<T>) density : ctx.lookup(registry);
            }
        });
    }
}

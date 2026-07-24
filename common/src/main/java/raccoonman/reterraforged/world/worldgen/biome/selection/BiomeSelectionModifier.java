package raccoonman.reterraforged.world.worldgen.biome.selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import raccoonman.reterraforged.data.worldgen.preset.settings.MiscellaneousSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.tags.RTFBiomeTags;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;

public final class BiomeSelectionModifier {
	public static final float INITIAL_RELEASE_DEFAULT_USAGE = 0.4F;

	private final float mountainUsage;
	private final float volcanoUsage;
	private final MountainBoundary mountainBoundary;
	private final CandidatePool<Holder<Biome>> mountainBiomes;
	private final CandidatePool<Holder<Biome>> volcanoBiomes;
	private final Set<Holder<Biome>> possibleBiomes;

	BiomeSelectionModifier(
		float mountainUsage,
		float volcanoUsage,
		MountainBoundary mountainBoundary,
		CandidatePool<Holder<Biome>> mountainBiomes,
		CandidatePool<Holder<Biome>> volcanoBiomes,
		Set<Holder<Biome>> possibleBiomes
	) {
		this.mountainUsage = mountainUsage;
		this.volcanoUsage = volcanoUsage;
		this.mountainBoundary = mountainBoundary;
		this.mountainBiomes = mountainBiomes;
		this.volcanoBiomes = volcanoBiomes;
		this.possibleBiomes = Set.copyOf(possibleBiomes);
	}

	public static BiomeSelectionModifier create(
		RegistryAccess registries,
		Preset preset,
		GeneratorContext generatorContext,
		Set<Holder<Biome>> possibleBiomes
	) {
		HolderLookup.RegistryLookup<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
		MiscellaneousSettings settings = preset.miscellaneous();
		Set<Holder<Biome>> allowedBiomes = Set.copyOf(possibleBiomes);
		return new BiomeSelectionModifier(
			settings.mountainBiomeUsage,
			settings.volcanoBiomeUsage,
			MountainBoundary.create(generatorContext),
			CandidatePool.fromTag(biomes, RTFBiomeTags.MOUNTAIN_BIOMES, allowedBiomes, CandidateKind.MOUNTAIN),
			CandidatePool.fromTag(biomes, RTFBiomeTags.VOLCANO_BIOMES, allowedBiomes, CandidateKind.VOLCANO),
			allowedBiomes
		);
	}

	public static boolean hasActiveUsage(MiscellaneousSettings settings) {
		return isActive(settings.mountainBiomeUsage) || isActive(settings.volcanoBiomeUsage);
	}

	public boolean isActive() {
		return isActive(this.mountainUsage) || isActive(this.volcanoUsage);
	}

	public Holder<Biome> modify(Holder<Biome> selected, BiomeSelectionTarget target) {
		if(target.mountain() && passesThreshold(target.macroBiomeId(), this.mountainUsage)) {
			if(this.mountainBoundary.canModify(target)) {
				Holder<Biome> mountain = this.mountainBiomes.select(target.temperature(), target.biomeRegionId());
				if(mountain != null && this.possibleBiomes.contains(mountain)) {
					return mountain;
				}
			}
			return selected;
		}
		if(target.volcano() && passesThreshold(target.terrainRegionId(), this.volcanoUsage)) {
			Holder<Biome> volcano = this.volcanoBiomes.select(target.temperature(), target.biomeRegionId());
			if(volcano != null && this.possibleBiomes.contains(volcano)) {
				return volcano;
			}
			return selected;
		}
		return selected;
	}

	static boolean isActive(float usage) {
		return Float.isFinite(usage)
			&& usage > 0.0F
			&& Float.floatToRawIntBits(usage) != Float.floatToRawIntBits(INITIAL_RELEASE_DEFAULT_USAGE);
	}

	static boolean passesThreshold(float sample, float usage) {
		return isActive(usage) && Float.isFinite(sample) && sample < usage;
	}

	@FunctionalInterface
	interface BoundaryNoise {
		float compute(float x, float z);
	}

	record MountainBoundary(float height, float range, BoundaryNoise noise) {

		static MountainBoundary create(GeneratorContext context) {
			float height = context.levels.ground(48);
			float range = context.levels.scale(10);
			Noise noise = Noises.perlin(context.seed.next(), 80, 2);
			return new MountainBoundary(height, range, (x, z) -> noise.compute(x, z, 0) * range);
		}

		boolean canModify(BiomeSelectionTarget target) {
			float terrainHeight = target.height();
			if(terrainHeight > this.height) {
				return true;
			}
			if(terrainHeight + this.range < this.height) {
				return false;
			}
			return terrainHeight + this.noise.compute(target.blockX(), target.blockZ()) > this.height;
		}
	}

	record Candidate<T>(ResourceLocation id, float temperature, int weight, T value) {

		Candidate(ResourceLocation id, float temperature, T value) {
			this(id, temperature, 1, value);
		}
	}

	static final class CandidatePool<T> {
		private final Map<TemperatureBand, List<Candidate<T>>> candidates;

		CandidatePool(List<Candidate<T>> candidates) {
			this(candidates, CandidateKind.VOLCANO);
		}

		CandidatePool(List<Candidate<T>> candidates, CandidateKind kind) {
			Map<TemperatureBand, List<Candidate<T>>> grouped = new EnumMap<>(TemperatureBand.class);
			for(TemperatureBand band : TemperatureBand.values()) {
				grouped.put(band, new ArrayList<>());
			}
			for(Candidate<T> candidate : candidates) {
				if(candidate.weight() > 0) {
					grouped.get(kind.classify(candidate.id(), candidate.temperature())).add(candidate);
				}
			}
			for(Map.Entry<TemperatureBand, List<Candidate<T>>> entry : grouped.entrySet()) {
				entry.getValue().sort(Comparator.comparing((candidate) -> candidate.id().toString()));
				entry.setValue(expandWeights(entry.getValue()));
			}
			this.candidates = Map.copyOf(grouped);
		}

		@Nullable
		T select(float temperature, float biomeRegionId) {
			List<Candidate<T>> candidates = this.candidates.get(TemperatureBand.forTarget(temperature));
			if(candidates.isEmpty() || !Float.isFinite(biomeRegionId)) {
				return null;
			}
			int index = NoiseUtil.round((candidates.size() - 1) * biomeRegionId);
			if(index < 0 || index >= candidates.size()) {
				return null;
			}
			return candidates.get(index).value();
		}

		private static <T> List<Candidate<T>> expandWeights(List<Candidate<T>> candidates) {
			int divisor = 0;
			for(Candidate<T> candidate : candidates) {
				divisor = greatestCommonDivisor(divisor, candidate.weight());
			}
			if(divisor <= 0) {
				return List.of();
			}
			List<Candidate<T>> weighted = new ArrayList<>();
			for(Candidate<T> candidate : candidates) {
				int count = candidate.weight() / divisor;
				for(int i = 0; i < count; i++) {
					weighted.add(candidate);
				}
			}
			return List.copyOf(weighted);
		}

		private static int greatestCommonDivisor(int first, int second) {
			first = Math.abs(first);
			second = Math.abs(second);
			while(second != 0) {
				int remainder = first % second;
				first = second;
				second = remainder;
			}
			return first;
		}

		static CandidatePool<Holder<Biome>> fromTag(
			HolderLookup.RegistryLookup<Biome> biomes,
			TagKey<Biome> tag,
			Set<Holder<Biome>> possibleBiomes,
			CandidateKind kind
		) {
			List<Candidate<Holder<Biome>>> candidates = biomes.get(tag).stream()
				.flatMap((holders) -> holders.stream())
				.filter(possibleBiomes::contains)
				.flatMap((holder) -> holder.unwrapKey().stream().map((key) -> {
					return new Candidate<>(key.location(), holder.value().getBaseTemperature(), holder);
				}))
				.toList();
			return new CandidatePool<>(candidates, kind);
		}
	}

	enum CandidateKind {
		MOUNTAIN {
			@Override
			TemperatureBand classify(ResourceLocation id, float temperature) {
				if(temperature <= 0.25F) {
					return TemperatureBand.COLD;
				}
				if(temperature >= 0.75F) {
					return TemperatureBand.WARM;
				}
				return TemperatureBand.MEDIUM;
			}
		},
		VOLCANO {
			@Override
			TemperatureBand classify(ResourceLocation id, float temperature) {
				if(temperature <= 0.3F) {
					return TemperatureBand.COLD;
				}
				if(temperature > 0.8F) {
					return TemperatureBand.WARM;
				}
				String name = id.toString();
				if(containsKeyword(name, COLD_KEYWORDS)) {
					return TemperatureBand.COLD;
				}
				if(containsKeyword(name, WARM_KEYWORDS)) {
					return TemperatureBand.WARM;
				}
				return TemperatureBand.MEDIUM;
			}
		};

		private static final String[] COLD_KEYWORDS = {
			"cold", "frozen", "ice", "chill", "tundra", "taiga", "arctic"
		};
		private static final String[] WARM_KEYWORDS = {
			"hot", "warm", "tropic", "desert", "savanna", "jungle"
		};

		abstract TemperatureBand classify(ResourceLocation id, float temperature);

		private static boolean containsKeyword(String value, String[] keywords) {
			for(String keyword : keywords) {
				if(value.contains(keyword)) {
					return true;
				}
			}
			return false;
		}
	}

	enum TemperatureBand {
		COLD,
		MEDIUM,
		WARM;

		static TemperatureBand forTarget(float temperature) {
			if(temperature < 0.25F) {
				return COLD;
			}
			if(temperature > 0.75F) {
				return WARM;
			}
			return MEDIUM;
		}
	}
}

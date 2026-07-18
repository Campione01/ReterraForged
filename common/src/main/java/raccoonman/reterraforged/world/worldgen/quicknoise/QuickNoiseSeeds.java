package raccoonman.reterraforged.world.worldgen.quicknoise;

public final class QuickNoiseSeeds {
	public static final int SEED_COUNT = 14;

	private static final long NOISE_SEED_SALT = 0xD5E7B3C94F8A1E6BL;
	private static final long CHEESE_SALT = 0x434845455345L;
	private static final long SPAGHETTI_A_SALT = 0x53504147484541L;
	private static final long SPAGHETTI_B_SALT = 0x53504147484542L;
	private static final long NOODLE_A_SALT = 0x4E4F4F444C4541L;
	private static final long NOODLE_B_SALT = 0x4E4F4F444C4542L;

	private QuickNoiseSeeds() {
	}

	public static int[] create(long worldSeed) {
		int[] seeds = new int[SEED_COUNT];
		int offset = 0;
		offset = append(seeds, offset, worldSeed, CHEESE_SALT, 4, 1.0F / 24.0F);
		offset = append(seeds, offset, worldSeed, SPAGHETTI_A_SALT, 3, 1.0F / 14.0F);
		offset = append(seeds, offset, worldSeed, SPAGHETTI_B_SALT, 3, 1.0F / 14.0F);
		offset = append(seeds, offset, worldSeed, NOODLE_A_SALT, 2, 1.0F / 28.0F);
		append(seeds, offset, worldSeed, NOODLE_B_SALT, 2, 1.0F / 28.0F);
		return seeds;
	}

	private static int append(int[] seeds, int offset, long worldSeed, long noiseSalt, int octaves, float frequency) {
		long gridSeed = mix(worldSeed);
		long noiseSeed = mix(noiseSalt ^ NOISE_SEED_SALT);
		long baseSeed = mixPair(gridSeed, noiseSeed);
		float xFrequency = frequency;
		float yFrequency = frequency * 2.0F;
		float zFrequency = frequency;
		for(int octave = 0; octave < octaves; octave++) {
			long x = baseSeed * Integer.toUnsignedLong(Float.floatToRawIntBits(xFrequency));
			long y = baseSeed * Integer.toUnsignedLong(Float.floatToRawIntBits(yFrequency));
			long z = baseSeed * Integer.toUnsignedLong(Float.floatToRawIntBits(zFrequency));
			seeds[offset++] = (int)mixTriple(x, y, z);
			xFrequency *= 2.0F;
			yFrequency *= 2.0F;
			zFrequency *= 2.0F;
		}
		return offset;
	}

	private static long mix(long data) {
		data ^= 0xB820ABC04DB1A623L;
		data ^= data >>> 33;
		data *= 0xFF51AFD7ED558CCDL;
		data ^= data >>> 33;
		data *= 0xC4CEB9FE1A85EC53L;
		data ^= data >>> 33;
		return data;
	}

	private static long mixPair(long data1, long data2) {
		data1 ^= 0xB820ABC04DB1A623L;
		data1 ^= data1 >>> 33;
		data1 *= 0xFF51AFD7ED558CCDL ^ data2;
		data1 ^= data1 >>> 33;
		data1 *= 0xC4CEB9FE1A85EC53L ^ data2;
		data1 ^= data1 >>> 33;
		return data1;
	}

	private static long mixTriple(long data1, long data2, long data3) {
		data1 ^= 0xB820ABC04DB1A623L;
		data1 ^= data1 >>> 33;
		data1 *= 0xFF51AFD7ED558CCDL ^ data2;
		data1 ^= data1 >>> 33;
		data1 *= 0xC4CEB9FE1A85EC53L ^ data3;
		data1 ^= data1 >>> 33;
		data1 *= 0xFF51AFD7ED558CCDL ^ data2;
		data1 ^= data1 >>> 33;
		return data1;
	}
}

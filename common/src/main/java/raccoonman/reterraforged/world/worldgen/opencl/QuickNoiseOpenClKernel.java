package raccoonman.reterraforged.world.worldgen.opencl;

final class QuickNoiseOpenClKernel {
	static final String KERNEL_NAME = "rtf_quick_cave_v1";
	static final String SOURCE = """
		float rtf_gradient(uint packed, uint index) {
		    uint code = (packed >> index) & 3U;
		    return code == 1U ? 1.0f : code == 2U ? -1.0f : 0.0f;
		}

		uint rtf_permute_bytes(uint value) {
		    return ((value >> 24) & 0x000000FFU)
		         | ((value & 0x000000FFU) << 8)
		         |  (value & 0x00FF0000U)
		         | ((value & 0x0000FF00U) << 16);
		}

		float rtf_fade(float value) {
		    float inner = fma(value, 6.0f, -15.0f);
		    float tail = fma(value, inner, 10.0f);
		    return (value * value * value) * tail;
		}

		float rtf_perlin(uint seed, float x, float y, float z, float frequency) {
		    float x_scaled = x * frequency;
		    float y_scaled = y * (frequency * 2.0f);
		    float z_scaled = z * frequency;
		    float x_floor = floor(x_scaled);
		    float y_floor = floor(y_scaled);
		    float z_floor = floor(z_scaled);
		    int x_grid = convert_int_rtz(x_floor);
		    int y_grid = convert_int_rtz(y_floor);
		    int z_grid = convert_int_rtz(z_floor);
		    float xd0 = x_scaled - x_floor;
		    float yd0 = y_scaled - y_floor;
		    float zd0 = z_scaled - z_floor;
		    float xd1 = xd0 - 1.0f;
		    float yd1 = yd0 - 1.0f;
		    float zd1 = zd0 - 1.0f;
		    float xf = rtf_fade(xd0);
		    float yf = rtf_fade(yd0);
		    float zf = rtf_fade(zd0);

		    uint x1 = as_uint(x_grid) * seed;
		    uint y1 = as_uint(y_grid) * seed;
		    uint z1 = as_uint(z_grid) * seed;
		    uint x2 = x1 + seed;
		    uint y2 = y1 + seed;
		    uint z2 = z1 + seed;
		    uint prime = 0x85EBCA6BU;
		    x1 = rtf_permute_bytes(x1) ^ prime;
		    y1 = rtf_permute_bytes(y1) ^ prime;
		    z1 = rtf_permute_bytes(z1) ^ prime;
		    x2 = rtf_permute_bytes(x2) ^ prime;
		    y2 = rtf_permute_bytes(y2) ^ prime;
		    z2 = rtf_permute_bytes(z2) ^ prime;

		    uint indices[8];
		    indices[0] = ((x1 * y1 * z1) >> 28) << 1;
		    indices[1] = ((x1 * y1 * z2) >> 28) << 1;
		    indices[2] = ((x1 * y2 * z1) >> 28) << 1;
		    indices[3] = ((x1 * y2 * z2) >> 28) << 1;
		    indices[4] = ((x2 * y1 * z1) >> 28) << 1;
		    indices[5] = ((x2 * y1 * z2) >> 28) << 1;
		    indices[6] = ((x2 * y2 * z1) >> 28) << 1;
		    indices[7] = ((x2 * y2 * z2) >> 28) << 1;

		    const uint gx = 0x90A5A500U;
		    const uint gy = 0xA59900A5U;
		    const uint gz = 0x09009999U;
		    float products[8];
		    products[0] = fma(rtf_gradient(gx, indices[0]), xd0, fma(rtf_gradient(gy, indices[0]), yd0, rtf_gradient(gz, indices[0]) * zd0));
		    products[1] = fma(rtf_gradient(gx, indices[1]), xd0, fma(rtf_gradient(gy, indices[1]), yd0, rtf_gradient(gz, indices[1]) * zd1));
		    products[2] = fma(rtf_gradient(gx, indices[2]), xd0, fma(rtf_gradient(gy, indices[2]), yd1, rtf_gradient(gz, indices[2]) * zd0));
		    products[3] = fma(rtf_gradient(gx, indices[3]), xd0, fma(rtf_gradient(gy, indices[3]), yd1, rtf_gradient(gz, indices[3]) * zd1));
		    products[4] = fma(rtf_gradient(gx, indices[4]), xd1, fma(rtf_gradient(gy, indices[4]), yd0, rtf_gradient(gz, indices[4]) * zd0));
		    products[5] = fma(rtf_gradient(gx, indices[5]), xd1, fma(rtf_gradient(gy, indices[5]), yd0, rtf_gradient(gz, indices[5]) * zd1));
		    products[6] = fma(rtf_gradient(gx, indices[6]), xd1, fma(rtf_gradient(gy, indices[6]), yd1, rtf_gradient(gz, indices[6]) * zd0));
		    products[7] = fma(rtf_gradient(gx, indices[7]), xd1, fma(rtf_gradient(gy, indices[7]), yd1, rtf_gradient(gz, indices[7]) * zd1));

		    float top_front = fma(zf, products[1] - products[0], products[0]);
		    float bottom_front = fma(zf, products[3] - products[2], products[2]);
		    float top_back = fma(zf, products[5] - products[4], products[4]);
		    float bottom_back = fma(zf, products[7] - products[6], products[6]);
		    float front = fma(yf, bottom_front - top_front, top_front);
		    float back = fma(yf, bottom_back - top_back, top_back);
		    return fma(xf, back - front, front);
		}

		float rtf_fbm(__global const uint *seeds, int offset, int octaves, float frequency, float x, float y, float z) {
		    float weight = octaves == 4 ? as_float(0x3F088889U) : octaves == 3 ? as_float(0x3F124925U) : as_float(0x3F2AAAABU);
		    float result = rtf_perlin(seeds[offset], x, y, z, frequency) * weight;
		    for (int octave = 1; octave < octaves; octave++) {
		        frequency *= 2.0f;
		        weight *= 0.5f;
		        result += rtf_perlin(seeds[offset + octave], x, y, z, frequency) * weight;
		    }
		    return result;
		}

		__kernel void rtf_quick_cave_v1(
		    __global const uint *seeds,
		    __global float *output,
		    int tile_x,
		    int tile_y,
		    int tile_z,
		    float chamber_bias,
		    float spaghetti_width,
		    float noodle_width
		) {
		    int index = (int)get_global_id(0);
		    int local_x = index & 31;
		    int local_y = (index >> 5) & 31;
		    int local_z = index >> 10;
		    float x = convert_float(tile_x * 32 + local_x);
		    float y = convert_float(tile_y * 32 + local_y);
		    float z = convert_float(tile_z * 32 + local_z);

		    float cheese = rtf_fbm(seeds, 0, 4, as_float(0x3D2AAAABU), x, y, z);
		    float spaghetti_a = rtf_fbm(seeds, 4, 3, as_float(0x3D924925U), x, y, z);
		    float spaghetti_b = rtf_fbm(seeds, 7, 3, as_float(0x3D924925U), x, y, z);
		    float noodle_a = rtf_fbm(seeds, 10, 2, as_float(0x3D124925U), x, y, z);
		    float noodle_b = rtf_fbm(seeds, 12, 2, as_float(0x3D124925U), x, y, z);
		    float chamber = cheese + chamber_bias;
		    float spaghetti = fmax(fabs(spaghetti_a) - spaghetti_width, fabs(spaghetti_b) - spaghetti_width);
		    float noodle = fmax(fabs(noodle_a) - noodle_width, fabs(noodle_b) - noodle_width);
		    float cave = fmin(chamber, fmin(spaghetti, noodle));
		    output[index] = round(cave * 4096.0f) / 4096.0f;
		}
		""";

	private QuickNoiseOpenClKernel() {
	}
}

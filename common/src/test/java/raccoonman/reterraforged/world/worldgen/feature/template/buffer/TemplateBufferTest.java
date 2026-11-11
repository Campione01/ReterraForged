package raccoonman.reterraforged.world.worldgen.feature.template.buffer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelAccessor;
import raccoonman.reterraforged.world.worldgen.TestContexts;

/** The paste buffer is kept per thread, so it must let go of the level once a paste is over. */
class TemplateBufferTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		TestContexts.bootstrapMinecraft();
	}

	@Test
	void aReleasedBufferKeepsNoLevel() throws Exception {
		LevelAccessor level = (LevelAccessor) Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(), new Class<?>[] { LevelAccessor.class }, (proxy, method, args) -> {
			throw new UnsupportedOperationException(method.getName());
		});
		TemplateBuffer buffer = new TemplateBuffer().init(level, BlockPos.ZERO, Vec3i.ZERO, new Vec3i(1, 1, 1));
		assertSame(level, field("world").get(buffer));

		buffer.release();

		assertNull(field("world").get(buffer), "the level of the finished paste");
		assertNull(field("origin").get(buffer));
	}

	private static Field field(String name) throws Exception {
		Field field = TemplateBuffer.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}

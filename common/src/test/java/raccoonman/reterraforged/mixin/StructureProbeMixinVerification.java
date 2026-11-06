package raccoonman.reterraforged.mixin;

import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.MixinService;
import sun.misc.Unsafe;

/** Standalone entry point for the existing W5 isolated Sponge transformer fixture. */
public final class StructureProbeMixinVerification {

	private static final String LEVELGEN = "net.minecraft.world.level.levelgen.";
	private static final String STRUCTURE = LEVELGEN + "structure.Structure";
	private static final String RTF = "raccoonman.reterraforged.";
	private static final String TILE = RTF + "world.worldgen.densityfunction.tile.";
	private static final String PROBE = RTF + "world.worldgen.structure.StructureProbe";
	private static final String FIXTURE = "com.localcompat.rtfstructureprobe.test.";

	public static void main(String[] args) throws Exception {
		MixinBootstrap.init();
		var service = MixinService.getService();
		require(service.getClass().getName().equals(FIXTURE + "FixtureMixinService"), "Expected W5 standalone service");
		var factory = (IMixinTransformerFactory) get(service.getClass(), null, "factory");
		var transformer = factory.createTransformer();
		MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
		MixinEnvironment.getCurrentEnvironment().setSide(MixinEnvironment.Side.CLIENT);
		Mixins.addConfiguration("reterraforged-structure-probe-test.mixins.json");
		var phase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
		phase.setAccessible(true);
		phase.invoke(null, MixinEnvironment.Phase.DEFAULT);
		var composed = new LinkedHashMap<String, byte[]>();
		for(String name : new String[] {LEVELGEN + "NoiseChunk", STRUCTURE + "Check", LEVELGEN + "RandomState"}) {
			try(InputStream input = StructureProbeMixinVerification.class.getResourceAsStream('/' + name.replace('.', '/') + ".class")) {
				require(input != null, "Missing host class " + name);
				composed.put(name, transformer.transformClassBytes(name, name, input.readAllBytes()));
			}
		}
		String noiseRedirect = verifyNoiseChunk(composed.get(LEVELGEN + "NoiseChunk"));
		verifyStructureCheck(composed.get(STRUCTURE + "Check"));

		var isolated = Class.forName(FIXTURE + "CompositionTests$Isolated").getDeclaredConstructor(Map.class, Function.class);
		isolated.setAccessible(true);
		Function<String, byte[]> generated = name -> transformer.generateClass(MixinEnvironment.getCurrentEnvironment(), name);
		verifyRouting((ClassLoader) isolated.newInstance(composed, generated), noiseRedirect);
		System.out.println("RTF structure probe real Mixin shape and merged routing passed; java=" + System.getProperty("java.version"));
	}

	private static String verifyNoiseChunk(byte[] bytes) {
		var owner = node(bytes);
		String probeOwner = PROBE.replace('.', '/');
		var redirect = owner.methods.stream().filter(method -> calls(method, probeOwner, "bypass") == 1).toList();
		require(redirect.size() == 1, "Expected one merged RTF router redirect");
		var handler = redirect.getFirst();
		require(owner.methods.stream().filter(method -> method.name.equals("<init>"))
			.mapToLong(method -> calls(method, owner.name, handler.name)).sum() == 1, "Expected one NoiseChunk constructor redirect");
		int bypass = Arrays.asList(handler.instructions.toArray()).indexOf(callStream(handler)
			.filter(call -> call.owner.equals(probeOwner) && call.name.equals("bypass")).findFirst().orElseThrow());
		boolean earlyReturn = false;
		for(int i = 0; i < handler.instructions.size(); i++) {
			int opcode = handler.instructions.get(i).getOpcode();
			if(opcode == Opcodes.PUTFIELD) break;
			if(i > bypass && opcode == Opcodes.ARETURN) earlyReturn = true;
		}
		require(earlyReturn, "Probe must return before the first RTF state assignment");
		return handler.name;
	}

	private static void verifyStructureCheck(byte[] bytes) {
		var owner = node(bytes);
		String probeOwner = PROBE.replace('.', '/');
		var scope = owner.methods.stream().filter(method -> calls(method, probeOwner, "enter") == 1).toList();
		require(scope.size() == 1, "Expected one merged structure scope handler");
		var handler = scope.getFirst();
		var original = owner.methods.stream().filter(method -> method.name.equals("canCreateStructure")).findFirst().orElseThrow();
		require(calls(original, owner.name, handler.name) == 1
			&& calls(original, STRUCTURE.replace('.', '/'), "findValidGenerationPoint") == 0,
			"Expected one scoped generation-point query in canCreateStructure");
		require(calls(handler, STRUCTURE.replace('.', '/'), "findValidGenerationPoint") == 1
			&& calls(handler, probeOwner, "exit") == 2 && !handler.tryCatchBlocks.isEmpty(),
			"Expected the original query and scope cleanup on success and failure");
	}

	private static void verifyRouting(ClassLoader loader, String redirect) throws Exception {
		Class<?> chunkType = loader.loadClass(LEVELGEN + "NoiseChunk");
		Class<?> stateType = loader.loadClass(LEVELGEN + "RandomState");
		Class<?> contextType = loader.loadClass(RTF + "world.worldgen.GeneratorContext");
		Class<?> cacheType = loader.loadClass(RTF + "concurrent.cache.Cache");
		Class<?> tilesType = loader.loadClass(TILE + "TileCache");
		Class<?> tileType = loader.loadClass(TILE + "Tile");
		Class<?> recordingType = loader.loadClass(FIXTURE + "exec.RecordingTileCache");
		Class<?> scope = loader.loadClass(PROBE);
		Object recording = allocate(recordingType);
		set(tilesType, recording, "tileSize", 3);
		Object cache = allocate(cacheType);
		Object map = loader.loadClass(RTF + "concurrent.cache.map.StampedBoundLongMap").getConstructor(int.class).newInstance(16);
		set(cacheType, cache, "map", map);
		set(tilesType, recording, "cache", cache);
		Object context = allocate(contextType);
		set(contextType, context, "cache", recording);
		Object tile = allocate(tileType);
		Class<?> sizeType = loader.loadClass(TILE + "Size");
		Object size = sizeType.getMethod("chunks", int.class, int.class).invoke(null, 3, 1);
		set(tileType, tile, "chunkSize", size);
		set(tileType, tile, "border", 1);
		int length = (int) sizeType.getMethod("arraySize").invoke(size);
		set(tileType, tile, "chunks", Array.newInstance(loader.loadClass(TILE + "Tile$Chunk"), length));
		set(recordingType, null, "tile", tile);
		var provides = (AtomicInteger) get(recordingType, null, "PROVIDES");
		var lookups = (AtomicInteger) get(recordingType, null, "IF_PRESENT");

		Object router = allocate(loader.loadClass(LEVELGEN + "NoiseRouter"));
		Object state = allocate(stateType);
		set(stateType, state, "router", router);
		set(stateType, state, "generatorContext", context);
		Method handler = chunkType.getDeclaredMethod(redirect, stateType, int.class, stateType, int.class, int.class);
		handler.setAccessible(true);
		Method enter = scope.getMethod("enter"), exit = scope.getMethod("exit");

		Object ordinary = sample(chunkType, handler, state, router, 1);
		require(provides.get() == 1 && lookups.get() == 0 && get(chunkType, ordinary, "randomState") == state,
			"Outside scope must keep the ordinary tile path");
		enter.invoke(null);
		try {
			Object cold = sample(chunkType, handler, state, router, 1);
			require(provides.get() == 1 && lookups.get() == 1, "Cold one-column probe must not fetch a tile");
			for(String field : new String[] {"randomState", "chunk", "cache2d"}) {
				require(get(chunkType, cold, field) == null, "Cold probe assigned " + field);
			}

			Class<?> entryType = loader.loadClass(TILE + "TileCache$Entry");
			Object entry = allocate(entryType);
			set(entryType, entry, "tile", tile);
			Object cached = loader.loadClass(RTF + "concurrent.cache.CacheEntry").getMethod("supply", java.util.concurrent.Future.class)
				.invoke(null, CompletableFuture.completedFuture(entry));
			long key = (long) loader.loadClass(RTF + "world.worldgen.util.PosUtil").getMethod("pack", int.class, int.class).invoke(null, 7, 15);
			map.getClass().getMethod("put", long.class, Object.class).invoke(map, key, cached);
			sample(chunkType, handler, state, router, 1);
			require(provides.get() == 2 && lookups.get() == 2, "Cached probe must use the ordinary tile path");
			sample(chunkType, handler, state, router, 4);
			require(provides.get() == 3 && lookups.get() == 2, "Full chunk must not use the probe lookup");
		} finally {
			exit.invoke(null);
		}
		sample(chunkType, handler, state, router, 1);
		require(provides.get() == 4 && lookups.get() == 2, "Exiting scope must restore the ordinary tile path");
	}

	private static Object sample(Class<?> type, Method handler, Object state, Object router, int cells) throws Exception {
		Object chunk = allocate(type);
		require(handler.invoke(chunk, state, cells, state, 1000, 2000) == router, "Wrong router from merged redirect");
		return chunk;
	}

	private static ClassNode node(byte[] bytes) {
		var node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static Stream<MethodInsnNode> callStream(MethodNode method) {
		return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast);
	}

	private static long calls(MethodNode method, String owner, String name) {
		return callStream(method).filter(call -> call.owner.equals(owner) && call.name.equals(name)).count();
	}

	private static Object allocate(Class<?> type) throws Exception {
		return ((Unsafe) get(Unsafe.class, null, "theUnsafe")).allocateInstance(type);
	}

	private static Object get(Class<?> owner, Object target, String name) throws Exception {
		var field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
		var field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static void require(boolean passed, String message) {
		if(!passed) throw new AssertionError(message);
	}
}

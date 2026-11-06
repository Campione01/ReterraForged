package raccoonman.reterraforged.mixin;

import java.util.Optional;

import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import raccoonman.reterraforged.world.worldgen.structure.StructureProbe;

@Mixin(StructureCheck.class)
class MixinStructureCheck {
	@Redirect(
		method = "canCreateStructure(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Z",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/structure/Structure;findValidGenerationPoint(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;)Ljava/util/Optional;"
		),
		require = 1,
		expect = 1,
		allow = 1
	)
	private Optional<Structure.GenerationStub> reterraforged$probeGenerationPoint(Structure structure, Structure.GenerationContext context) {
		StructureProbe.enter();
		try {
			return structure.findValidGenerationPoint(context);
		} finally {
			StructureProbe.exit();
		}
	}
}

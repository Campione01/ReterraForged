package raccoonman.reterraforged.world.worldgen.feature.template;

import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;

public class StructureUtils {

    public static boolean hasOvergroundStructure(HolderLookup<Structure> structures, ChunkAccess chunk) {
    	// Walks the chunk's own references rather than the whole structure registry: this runs for every pasted template.
    	for (Map.Entry<Structure, LongSet> reference : chunk.getAllReferences().entrySet()) {
            LongSet refs = reference.getValue();
            if (refs != null && refs.size() > 0 && reference.getKey().step() == GenerationStep.Decoration.SURFACE_STRUCTURES) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasStructure(ChunkAccess chunk, Structure structure) {
        LongSet refs = chunk.getAllReferences().get(structure);
        return refs != null && refs.size() > 0;
    }
}

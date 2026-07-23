package raccoonman.reterraforged.world.worldgen.cell.rivermap;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;

public final class LegacyV2Rivermap extends Rivermap {
    public LegacyV2Rivermap(int x, int z, Network[] networks, GenWarp warp) {
        super(x, z, networks, warp);
    }

    @Override
    public void apply(Cell cell, float x, float z) {
        Domain riverWarp = this.riverWarp();
        float rx = riverWarp.getRootX(x, z, 0);
        float rz = riverWarp.getRootZ(x, z, 0);
        Domain lakeWarp = this.lakeWarp();
        float lx = lakeWarp.getRootOffsetX(rx, rz, 0);
        float lz = lakeWarp.getRootOffsetZ(rx, rz, 0);
        for(Network network : this.networks()) {
            if(network.contains(rx, rz)) {
                network.carveBounded(cell, rx, rz, lx, lz);
            }
        }
    }
}

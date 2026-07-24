package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.util.Boundsf;

class NetworkBoundsParityTest {
    private static final Levels LEVELS = new Levels(384, 63);

    @Test
    void productionTreeKeepsAggregateBoundsOnTheRootOnly() {
        Network.Builder root = Network.builder(carver(-1000.0F, 1000.0F, 1000.0F, 1000.0F, 101, true));
        Network.Builder child = Network.builder(carver(-100.0F, 0.0F, 100.0F, 0.0F, 102, false));
        child.children.add(Network.builder(carver(-50.0F, -200.0F, 50.0F, -100.0F, 103, false)));
        root.children.add(child);

        Network network = root.build();

        assertNotSame(Boundsf.NONE, network.bounds());
        assertSame(Boundsf.NONE, network.children()[0].bounds());
        assertSame(Boundsf.NONE, network.children()[0].children()[0].bounds());
    }

    private static RiverCarver carver(float x1, float z1, float x2, float z2, int seed, boolean main) {
        RiverConfig config = RiverConfig.builder(LEVELS)
            .main(main)
            .order(main ? 0 : 1)
            .bedWidth(main ? 7 : 4)
            .bankWidth(main ? 24 : 15)
            .bedDepth(main ? 7 : 5)
            .bankHeight(1, 4)
            .fade(0.7F)
            .length(5000)
            .build();
        RiverCarver.Settings settings = new RiverCarver.Settings();
        settings.connecting = !main;
        settings.valleySize = main ? 320.0F : 210.0F;
        settings.fadeIn = 0.7F;
        RiverWarp warp = new RiverWarp(seed, 0.15F, 0.8F, 7.5E-4F, main ? 165.0F : 115.0F);
        return new RiverCarver(new River(x1, z1, x2, z2), warp, config, settings, LEVELS);
    }
}

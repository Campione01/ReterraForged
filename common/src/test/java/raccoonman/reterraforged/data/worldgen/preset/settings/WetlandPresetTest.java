package raccoonman.reterraforged.data.worldgen.preset.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.wetland.WetlandConfig;

class WetlandPresetTest {
    @Test
    void defaultPresetsAdmitOneInTwoRiverSegments() {
        for (Preset preset : List.of(Presets.makeRTFDefault(), Presets.makeLegacyDefault())) {
            assertEquals(ContinentType.MULTI_IMPROVED, preset.world().continent.continentType);
            RiverSettings.Wetland wetland = preset.rivers().wetlands;
            assertEquals(0.8F, wetland.chance);
            assertEquals(175, wetland.sizeMin);
            assertEquals(225, wetland.sizeMax);
            assertEquals(2, new WetlandConfig(wetland).skipSize);
        }
        assertEquals(4, new WetlandConfig(new RiverSettings.Wetland(0.6F, 175, 225)).skipSize);
    }
}

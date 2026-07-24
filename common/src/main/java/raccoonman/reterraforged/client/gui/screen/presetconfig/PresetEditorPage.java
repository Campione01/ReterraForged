package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;
import java.io.IOException;
import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.client.gui.screen.page.BisectedPage;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.client.gui.widget.ValueButton;
import raccoonman.reterraforged.config.PerformanceConfig;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;

public abstract class PresetEditorPage extends BisectedPage<PresetConfigScreen, AbstractWidget, AbstractWidget> {
	private CycleButton<RenderMode> renderMode;
	private ValueButton<Integer> seed;
	private Preview preview;
	protected PresetEntry preset;
	
	public PresetEditorPage(PresetConfigScreen screen, PresetEntry preset) {
		super(screen);
		
		this.preset = preset;
	}
	
	protected void regenerate() {
		this.preview.requestRegeneration(true, this.applyOptionalPreviewFilters());
	}

	protected boolean applyOptionalPreviewFilters() {
		return false;
	}
	
	@Override
	public void init() {
		super.init();

		this.preview = this.screen.acquirePreview(this, this.preset);
		this.renderMode = PresetWidgets.createCycle(ImmutableList.copyOf(RenderMode.values()), this.preview.renderMode(), Optional.empty(), (button, value) -> {
			this.preview.setRenderMode(value);
		}, RenderMode::name);
		this.seed = PresetWidgets.createRandomButton(RTFTranslationKeys.GUI_BUTTON_SEED, (int) this.screen.getSettings().options().seed(), (i) -> {
			this.screen.setSeed(i);
			this.regenerate();
		});

		this.right.addWidget(this.renderMode);
		this.right.addWidget(this.seed);
		this.right.addWidget(this.preview);
	}
	
	@Override
	public void onClose() {
		super.onClose();
	
		try {
			this.preset.save();
		} catch (Exception e) {
			e.printStackTrace();
		}
		this.screen.detachPreview(this);
	}
	
	@Override
	public void onDone() {
		super.onDone();
		
		try {
			this.screen.applyPreset(this.preset);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	static boolean requiresOptionalPreviewFilterRefresh(boolean current, PresetEditorPage owner) {
		return current != owner.applyOptionalPreviewFilters();
	}

	public static final class Preview extends AbstractWidget {
	    private static final int FACTOR = 4;
	    public static final int SIZE = (1 << 4) << FACTOR;
	    private static final float[] LEGEND_SCALES = { 1, 0.9F, 0.75F, 0.6F };
	    private final PresetConfigScreen screen;
	    private final PresetEntry preset;
	    private DynamicTexture texture = new DynamicTexture(new NativeImage(SIZE, SIZE, false));
	    private ResourceLocation textureId = Minecraft.getInstance().getTextureManager().register(RTFCommon.MOD_ID + "-preview-framebuffer", this.texture); 
	    private Tile tile;
	    private GeneratorContext generatorContext;
	    private Levels levels;
	    private BlockPos spawnCenter = BlockPos.ZERO;
	    private int centerX, centerZ;
	    private boolean applyOptionalFilters;
	    private boolean closed;
	    private RenderMode renderMode = RenderMode.BIOME_TYPE;
	    
	    private String hoveredCoords = "";
	    //TODO maybe make this a map or something instead?
	    private String[] legendValues = {"", "", ""};
	    private Component[] legendLabels = { Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_AREA), Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_TERRAIN), Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_BIOME) };
	    
	    private int offsetX, offsetZ;
	    private boolean clicked = false;
	    private int clickOffsetX, clickOffsetZ;
	    private int zoomValue = 68; // default zoom value
	
	    Preview(PresetConfigScreen screen, PresetEntry preset) {
	        super(-1, -1, -1, -1, CommonComponents.EMPTY);
	        this.screen = screen;
	        this.preset = preset;
	        this.requestRegeneration(true, false);
	    }

	    void attach(PresetEditorPage owner) {
			this.resetInputState();
			if(requiresOptionalPreviewFilterRefresh(this.applyOptionalFilters, owner)) {
				this.applyOptionalFilters = owner.applyOptionalPreviewFilters();
				this.requestRegeneration(false, this.applyOptionalFilters);
			}
	    }

	    void detach(PresetEditorPage owner) {
			this.resetInputState();
			if(this.screen.getFocused() == this) {
				this.screen.setFocused(null);
			}
	    }

	    RenderMode renderMode() {
			return this.renderMode;
	    }

	    void setRenderMode(RenderMode renderMode) {
			this.renderMode = renderMode;
			this.recolor();
	    }

		public void requestRegeneration(boolean rebuildContext) {
			this.requestRegeneration(rebuildContext, this.applyOptionalFilters);
		}

		void requestRegeneration(boolean rebuildContext, boolean applyOptionalFilters) {
			if(this.closed) {
				return;
			}
			boolean replacingContext = rebuildContext || this.generatorContext == null;
			GeneratorContext nextContext = this.generatorContext;
			Tile nextTile = null;
			try {
				if(replacingContext) {
					WorldCreationContext settings = this.screen.getSettings();
					RegistryAccess.Frozen registries = settings.worldgenLoadContext();
					Preset presetSnapshot = this.preset.getPreset().copy();
					nextContext = this.createGeneratorContext(presetSnapshot, registries, (int)settings.options().seed());
				}
				BlockPos nextSpawnCenter = replacingContext
					? nextContext.preset.world().properties.spawnType.getSearchCenter(nextContext)
					: this.spawnCenter;
				int nextCenterX = nextSpawnCenter.getX() + this.offsetX;
				int nextCenterZ = nextSpawnCenter.getZ() + this.offsetZ;
				nextTile = nextContext.generator.generateZoomed(nextCenterX, nextCenterZ, this.getZoom(), applyOptionalFilters).join();

				Tile previousTile = this.tile;
				GeneratorContext previousContext = this.generatorContext;
				this.generatorContext = nextContext;
				this.tile = nextTile;
				this.spawnCenter = nextSpawnCenter;
				this.centerX = nextCenterX;
				this.centerZ = nextCenterZ;
				this.applyOptionalFilters = applyOptionalFilters;
				WorldSettings.Properties properties = nextContext.preset.world().properties;
				this.levels = new Levels(properties.terrainScaler(), properties.seaLevel);
				nextTile = null;
				if(previousTile != null) {
					previousTile.close();
				}
				if(replacingContext && previousContext != null) {
					previousContext.close();
				}
				this.recolor();
			} catch(RuntimeException exception) {
				if(nextTile != null) {
					nextTile.close();
				}
				if(replacingContext && nextContext != null && nextContext != this.generatorContext) {
					nextContext.close();
				}
				RTFCommon.LOGGER.error("Failed to generate the terrain preview", exception);
			}
		}

		private GeneratorContext createGeneratorContext(Preset presetSnapshot, RegistryAccess.Frozen registries, int seed) {
	        HolderLookup.Provider provider = presetSnapshot.buildPatch(registries);
	        HolderGetter<Preset> presets = provider.lookupOrThrow(RTFRegistries.PRESET);
	        HolderGetter<Noise> noises = provider.lookupOrThrow(RTFRegistries.NOISE);
	        Preset preset = presets.getOrThrow(Preset.KEY).value();
			PerformanceConfig performance = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
				.resultOrPartial(RTFCommon.LOGGER::error)
				.orElseGet(PerformanceConfig::makeDefault);
			return GeneratorContext.makeUncached(preset, noises, seed, FACTOR, 0, performance.batchCount());
	    }

		private void recolor() {
			if(this.tile == null || this.levels == null || this.closed) {
				return;
			}
	        int stroke = 2;
	        int width = this.tile.getBlockSize().size();
	        NativeImage pixels = this.texture.getPixels();
	        this.tile.iterate((cell, x, z) -> {
	            if (x < stroke || z < stroke || x >= width - stroke || z >= width - stroke) {
	                pixels.setPixelRGBA(x, z, Color.BLACK.getRGB());
	            } else {
	                pixels.setPixelRGBA(x, z, this.renderMode.getColor(cell, this.levels));
	            }
	        });
	        this.texture.upload();
	    }
	    
		public void close() {
			if(this.closed) {
				return;
			}
			this.closed = true;
			if(this.tile != null) {
				this.tile.close();
				this.tile = null;
			}
			if(this.generatorContext != null) {
				this.generatorContext.close();
				this.generatorContext = null;
			}
			Minecraft.getInstance().getTextureManager().release(this.textureId);
		}

		private void resetInputState() {
			this.clicked = false;
			this.hoveredCoords = "";
		}
	
	    @Override
	    public void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
	        // No narration needed
	    }
	
	    @Override
	    public void renderWidget(GuiGraphics guiGraphics, int mx, int my, float partialTicks) {
	        int x = this.getX();
	        int y = this.getY();
	        
	        this.height = this.getWidth(); // ensure height equals width for click area
	        RenderSystem.enableBlend();
	        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
	        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
	        guiGraphics.blit(this.textureId, x, y, 0, 0, this.width, this.height, this.width, this.height);

	        if(this.tile != null) {
	            this.updateLegend(mx, my);
	            this.renderLegend(guiGraphics, mx, my, this.legendLabels, this.legendValues, x, y + this.width, 10, 0xFFFFFF);
	        }
	    }
	
	    @Override
	    public void onClick(double mouseX, double mouseY) {
	        if (Minecraft.getInstance().screen != null) {
	            Minecraft.getInstance().screen.setFocused(this);
	        }
	        clicked = true;
	        clickOffsetX = offsetX;
	        clickOffsetZ = offsetZ;
	    }
	
	    @Override
	    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
	        int zoom = getZoom();
	        double blocksPerPixel = zoom; // preview size = 256
	        offsetX -= dragX * blocksPerPixel;
	        offsetZ -= dragY * blocksPerPixel;
	        this.requestRegeneration(false);
	    }

		public boolean isDraggingPreview() {
			return this.clicked;
		}
	
	    @Override
	    public void onRelease(double mouseX, double mouseY) {
	        if (!clicked) {
	            return;
	        }
	        clicked = false;
	
	        // Check if drag distance is minimal (i.e., click)
	        int dragDeltaX = offsetX - clickOffsetX;
	        int dragDeltaZ = offsetZ - clickOffsetZ;
	        if (Math.abs(dragDeltaX) <= 4 && Math.abs(dragDeltaZ) <= 4) {
	            offsetX = clickOffsetX;
	            offsetZ = clickOffsetZ;
	            // Treat as click: copy coordinates if hoveredCoords is not empty
	            if (updateLegend((int) mouseX, (int) mouseY) && !hoveredCoords.isEmpty()) {
	                playDownSound(Minecraft.getInstance().getSoundManager());
	                this.screen.minecraft.keyboardHandler.setClipboard(hoveredCoords);
	            }
	        }
	    }
	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
	    return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
	    if (this.isMouseOver(mouseX, mouseY)) {
	        // Dynamic step: larger when zoom is small (close to 1), smaller when zoom is large (close to 100)
	        int dynamicStep = Math.max(1, (100 - zoomValue) / 10);
	        int step = (int) Math.signum(scrollY) * dynamicStep;
	        zoomValue = Math.max(1, Math.min(100, zoomValue + step));
	        this.requestRegeneration(false);
	        return true;
	    }
	    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	
	    @Override
	    public boolean mouseClicked(double mouseX, double mouseY, int button) {
	        return super.mouseClicked(mouseX, mouseY, button);
	    }
	
	    @Override
	    public boolean isMouseOver(double mouseX, double mouseY) {
	        return super.isMouseOver(mouseX, mouseY);
	    }
	
	    private boolean updateLegend(int mx, int my) {
	        if (this.tile != null) {
	            int left = this.getX();
	            int top = this.getY();
	            float size = this.width;
	
	            int zoom = this.getZoom();
	            int width = Math.max(1, this.tile.getBlockSize().size() * zoom);
	            int height = Math.max(1, this.tile.getBlockSize().size() * zoom);
	            this.legendValues[0] = width + "x" + height;
	            if (mx >= left && mx <= left + size && my >= top && my <= top + size) {
	                float fx = (mx - left) / size;
	                float fz = (my - top) / size;
	                int ix = NoiseUtil.round(fx * this.tile.getBlockSize().size());
	                int iz = NoiseUtil.round(fz * this.tile.getBlockSize().size());
	                Cell cell = this.tile.lookup(ix, iz);
	                this.legendValues[1] = getTerrainName(cell);
	                this.legendValues[2] = getBiomeName(cell);
	
	                int dx = (ix - (this.tile.getBlockSize().size() / 2)) * zoom;
	                int dz = (iz - (this.tile.getBlockSize().size() / 2)) * zoom;
	
	                this.hoveredCoords = (this.centerX + dx) + ":" + (this.centerZ + dz);
	                return true;
	            } else {
	            	this.hoveredCoords = "";
	            }
	        }
	        return false;
	    }

	    private float getLegendScale() {
	        int index = this.screen.minecraft.options.guiScale().get() - 1;
	        if (index < 0 || index >= LEGEND_SCALES.length) {
	            // index=-1 == GuiScale(AUTO) which is the same as GuiScale(4)
	            // values above 4 don't exist but who knows what mods might try set it to
	            // in both cases use the smallest acceptable scale
	            index = LEGEND_SCALES.length - 1;
	        }
	        return LEGEND_SCALES[index];
	    }

	    private void renderLegend(GuiGraphics guiGraphics, int mx, int my, Component[] labels, String[] values, int left, int top, int lineHeight, int color) {
	        float scale = this.getLegendScale();
	        PoseStack pose = guiGraphics.pose();
	        	
	        pose.pushPose();
	        pose.translate(left + 3.75F * scale, top - lineHeight * (3.2F * scale), 0);
	        pose.scale(scale, scale, 1);
	
	        Minecraft mc = Minecraft.getInstance();
	        Font renderer = mc.font;
	        int spacing = 0;
	        for (Component s : labels) {
	            spacing = Math.max(spacing, renderer.width(s));
	        }
	
	        float maxWidth = (this.width - 4) / scale;
	        for (int i = 0; i < labels.length && i < values.length; i++) {
	        	Component label = labels[i];
	            String value = values[i];
	
	            while (value.length() > 0 && spacing + renderer.width(value) > maxWidth) {
	                value = value.substring(0, value.length() - 1);
	            }
	
	            guiGraphics.drawString(renderer, label, 0, i * lineHeight, color);
	            guiGraphics.drawString(renderer, value, spacing, i * lineHeight, color);
	        }
	
	        pose.popPose();
	
	        if (!this.hoveredCoords.isEmpty()) {
	        	guiGraphics.drawCenteredString(renderer, this.hoveredCoords, mx, my - 10, 0xFFFFFF);
	        }
	    }
	
	    private int getZoom() {
	        return NoiseUtil.round(1.5F * (101 - (float) this.zoomValue));
	    }
	
	    private static String getTerrainName(Cell cell) {
	        if (cell.terrain.isRiver()) {
	            return "river";
	        }
	        return cell.terrain.getName().toLowerCase();
	    }
	
	    private static String getBiomeName(Cell cell) {
	        String terrain = cell.terrain.getName().toLowerCase();
	        if (terrain.contains("ocean")) {
	            if (cell.temperature < 0.3F) {
	                return "cold_" + terrain;
	            }
	            if (cell.temperature > 0.6F) {
	                return "warm_" + terrain;
	            }
	            return terrain;
	        }
	        if (terrain.contains("river")) {
	            return "river";
	        }
	        return cell.biome.name().toLowerCase();
	    }
	}
}

package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.Util;
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
import raccoonman.reterraforged.concurrent.ThreadPools;
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
		this.preview.requestRegeneration(true);
	}
	
	@Override
	public void init() {
		super.init();

		if(this.preview != null) {
			try {
				this.preview.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		this.renderMode = PresetWidgets.createCycle(ImmutableList.copyOf(RenderMode.values()), this.renderMode != null ? this.renderMode.getValue() : RenderMode.BIOME_TYPE, Optional.empty(), (button, value) -> {
			if(this.preview != null) {
				this.preview.recolor();
			}
		}, RenderMode::name);
		this.seed = PresetWidgets.createRandomButton(RTFTranslationKeys.GUI_BUTTON_SEED, (int) this.screen.getSettings().options().seed(), (i) -> {
			this.screen.setSeed(i);
			this.regenerate();
		});

		this.preview = new Preview();
		this.preview.requestRegeneration(true);

		this.right.addWidget(this.renderMode);
		this.right.addWidget(this.seed);
		this.right.addWidget(this.preview);
	}
	
	@Override
	public void onClose() {
		super.onClose();
	
		try {
			this.preset.save();
			this.preview.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
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
	
	private record PreviewContext(GeneratorContext context, BlockPos spawnCenter, boolean replacingContext) {
	}

	private record GeneratedPreview(GeneratorContext context, BlockPos spawnCenter, int centerX, int centerZ, Tile tile) {
	}

	public class Preview extends AbstractWidget {
	    private static final int FACTOR = 4;
	    private static final int PREVIEW_BATCH_COUNT = 2;
	    public static final int SIZE = (1 << 4) << FACTOR;
	    private static final long REGENERATION_DELAY_MS = 250L;
	    private static final float[] LEGEND_SCALES = { 1, 0.9F, 0.75F, 0.6F };
	    private DynamicTexture texture = new DynamicTexture(new NativeImage(SIZE, SIZE, false));
	    private ResourceLocation textureId = Minecraft.getInstance().getTextureManager().register(RTFCommon.MOD_ID + "-preview-framebuffer", this.texture); 
	    private Tile tile;
	    private GeneratorContext generatorContext;
	    private CompletableFuture<GeneratedPreview> generationFuture;
	    private Levels levels;
	    private BlockPos spawnCenter = BlockPos.ZERO;
	    private int centerX, centerZ;
	    private volatile long requestedGeneration;
	    private long activeGeneration;
	    private boolean activeGenerationRebuildsContext;
	    private boolean regenerationPending;
	    private boolean rebuildContextPending;
	    private long regenerationDeadline;
	    private volatile boolean closed;
	    
	    private String hoveredCoords = "";
	    //TODO maybe make this a map or something instead?
	    private String[] legendValues = {"", "", ""};
	    private Component[] legendLabels = { Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_AREA), Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_TERRAIN), Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_BIOME) };
	    
	    private int offsetX, offsetZ;
	    private boolean clicked = false;
	    private int clickOffsetX, clickOffsetZ;
	    private int zoomValue = 68; // default zoom value
	
	    public Preview() {
	        super(-1, -1, -1, -1, CommonComponents.EMPTY);
	    }

		public void requestRegeneration(boolean rebuildContext) {
			this.requestRegeneration(rebuildContext, REGENERATION_DELAY_MS);
		}

		private void requestRegeneration(boolean rebuildContext, long delayMillis) {
			if(this.closed) {
				return;
			}
			this.requestedGeneration++;
			this.regenerationPending = true;
			this.rebuildContextPending |= rebuildContext;
			this.regenerationDeadline = Util.getMillis() + delayMillis;
		}

		private void startRegeneration(boolean rebuildContext) {
			if(this.closed || this.generationFuture != null) {
				return;
			}
			this.regenerationPending = false;
			this.rebuildContextPending = false;
			try {
				boolean replacingContext = rebuildContext || this.generatorContext == null;
				CompletableFuture<PreviewContext> contextFuture;
				if(replacingContext) {
					WorldCreationContext settings = PresetEditorPage.this.screen.getSettings();
					RegistryAccess.Frozen registries = settings.worldgenLoadContext();
					Preset presetSnapshot = PresetEditorPage.this.preset.getPreset().copy();
					int seed = (int)settings.options().seed();
					contextFuture = CompletableFuture.supplyAsync(() -> {
						return new PreviewContext(this.createGeneratorContext(presetSnapshot, registries, seed), BlockPos.ZERO, true);
					}, ThreadPools.WORLD_GEN);
				} else {
					contextFuture = CompletableFuture.completedFuture(new PreviewContext(this.generatorContext, this.spawnCenter, false));
				}

				int requestedOffsetX = this.offsetX;
				int requestedOffsetZ = this.offsetZ;
				int requestedZoom = this.getZoom();
				long generation = this.requestedGeneration;
				this.activeGeneration = generation;
				this.activeGenerationRebuildsContext = replacingContext;
				this.generationFuture = contextFuture.thenComposeAsync((previewContext) -> {
					return this.generatePreview(previewContext, requestedOffsetX, requestedOffsetZ, requestedZoom, generation);
				}, ThreadPools.WORLD_GEN);
			} catch(RuntimeException exception) {
				RTFCommon.LOGGER.error("Failed to start the terrain preview generation", exception);
			}
		}

		private CompletableFuture<GeneratedPreview> generatePreview(PreviewContext previewContext, int offsetX, int offsetZ, int zoom, long generation) {
			GeneratorContext context = previewContext.context();
			try {
				if(this.closed || generation != this.requestedGeneration) {
					if(previewContext.replacingContext()) {
						context.close();
					}
					return CompletableFuture.failedFuture(new CancellationException("Superseded terrain preview"));
				}
				BlockPos nextSpawnCenter = previewContext.replacingContext()
					? context.preset.world().properties.spawnType.getSearchCenter(context)
					: previewContext.spawnCenter();
				if(this.closed || generation != this.requestedGeneration) {
					if(previewContext.replacingContext()) {
						context.close();
					}
					return CompletableFuture.failedFuture(new CancellationException("Superseded terrain preview"));
				}
				int nextCenterX = nextSpawnCenter.getX() + offsetX;
				int nextCenterZ = nextSpawnCenter.getZ() + offsetZ;
				return context.generator.generateZoomed(nextCenterX, nextCenterZ, zoom, false, () -> {
					return this.closed || generation != this.requestedGeneration;
				}).handle((tile, exception) -> {
					if(exception != null) {
						if(previewContext.replacingContext()) {
							context.close();
						}
						throw new CompletionException(exception);
					}
					return new GeneratedPreview(context, nextSpawnCenter, nextCenterX, nextCenterZ, tile);
				});
			} catch(RuntimeException exception) {
				if(previewContext.replacingContext()) {
					context.close();
				}
				return CompletableFuture.failedFuture(exception);
			}
		}

		private void completeGeneration() {
			CompletableFuture<GeneratedPreview> future = this.generationFuture;
			if(future == null || !future.isDone()) {
				return;
			}

			long completedGeneration = this.activeGeneration;
			boolean rebuiltContext = this.activeGenerationRebuildsContext;
			this.generationFuture = null;
			this.activeGenerationRebuildsContext = false;

			GeneratedPreview generated;
			try {
				generated = future.join();
			} catch(RuntimeException exception) {
				if(rebuiltContext && this.regenerationPending && !this.closed) {
					this.rebuildContextPending = true;
				}
				Throwable cause = exception instanceof CompletionException && exception.getCause() != null ? exception.getCause() : exception;
				if(!(cause instanceof CancellationException)) {
					RTFCommon.LOGGER.error("Failed to generate the terrain preview", cause);
				}
				return;
			}

			GeneratorContext completedContext = generated.context();
			boolean replacingContext = completedContext != this.generatorContext;
			if(this.closed || completedGeneration != this.requestedGeneration) {
				generated.tile().close();
				if(replacingContext) {
					completedContext.close();
					if(!this.closed) {
						this.rebuildContextPending = true;
					}
				}
				return;
			}

			Tile previousTile = this.tile;
			if(replacingContext) {
				if(this.generatorContext != null) {
					this.generatorContext.close();
				}
				this.generatorContext = completedContext;
				this.spawnCenter = generated.spawnCenter();
			}
			this.centerX = generated.centerX();
			this.centerZ = generated.centerZ();
			this.tile = generated.tile();
			WorldSettings.Properties properties = completedContext.preset.world().properties;
			this.levels = new Levels(properties.terrainScaler(), properties.seaLevel);
			if(previousTile != null) {
				previousTile.close();
			}
			this.recolor();
		}

		private GeneratorContext createGeneratorContext(Preset presetSnapshot, RegistryAccess.Frozen registries, int seed) {
	        HolderLookup.Provider provider = presetSnapshot.buildPatch(registries);
	        HolderGetter<Preset> presets = provider.lookupOrThrow(RTFRegistries.PRESET);
	        HolderGetter<Noise> noises = provider.lookupOrThrow(RTFRegistries.NOISE);
	        Preset preset = presets.getOrThrow(Preset.KEY).value();
			return GeneratorContext.makeUncached(preset, noises, seed, FACTOR, 0, PREVIEW_BATCH_COUNT);
	    }

		private void recolor() {
			if(this.tile == null || this.levels == null || this.closed) {
				return;
			}
	        RenderMode renderMode = PresetEditorPage.this.renderMode.getValue();
	        int stroke = 2;
	        int width = this.tile.getBlockSize().size();
	        NativeImage pixels = this.texture.getPixels();
	        this.tile.iterate((cell, x, z) -> {
	            if (x < stroke || z < stroke || x >= width - stroke || z >= width - stroke) {
	                pixels.setPixelRGBA(x, z, Color.BLACK.getRGB());
	            } else {
	                pixels.setPixelRGBA(x, z, renderMode.getColor(cell, this.levels));
	            }
	        });
	        this.texture.upload();
	    }
	    
		public void close() throws Exception {
			if(this.closed) {
				return;
			}
			this.closed = true;
			this.regenerationPending = false;
			if(this.tile != null) {
				this.tile.close();
				this.tile = null;
			}

			GeneratorContext currentContext = this.generatorContext;
			CompletableFuture<GeneratedPreview> future = this.generationFuture;
			boolean rebuildingContext = this.activeGenerationRebuildsContext;
			this.generatorContext = null;
			this.generationFuture = null;
			this.activeGenerationRebuildsContext = false;
			if(future != null) {
				if(rebuildingContext && currentContext != null) {
					currentContext.close();
				}
				future.whenComplete((generated, exception) -> {
					if(generated != null) {
						generated.tile().close();
						generated.context().close();
					} else if(!rebuildingContext && currentContext != null) {
						currentContext.close();
					}
				});
			} else if(currentContext != null) {
				currentContext.close();
			}
			Minecraft.getInstance().getTextureManager().release(this.textureId);
		}
	
	    @Override
	    public void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
	        // No narration needed
	    }
	
	    @Override
	    public void renderWidget(GuiGraphics guiGraphics, int mx, int my, float partialTicks) {
			this.completeGeneration();
			if(this.generationFuture == null && this.regenerationPending && Util.getMillis() >= this.regenerationDeadline) {
				this.startRegeneration(this.rebuildContextPending);
			}
	        int x = this.getX();
	        int y = this.getY();
	        
	        this.height = this.getWidth(); // ensure height equals width for click area
	        RenderSystem.enableBlend();
	        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
	        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
	        guiGraphics.blit(this.textureId, x, y, 0, 0, this.width, this.height, this.width, this.height);

	        this.updateLegend(mx, my);

	        this.renderLegend(guiGraphics, mx, my, this.legendLabels, this.legendValues, x, y + this.width, 10, 0xFFFFFF);
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
	                PresetEditorPage.this.screen.minecraft.keyboardHandler.setClipboard(hoveredCoords);
	            }
	        } else {
				this.requestRegeneration(false, 0L);
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
	        int index = PresetEditorPage.this.screen.minecraft.options.guiScale().get() - 1;
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

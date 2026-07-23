package raccoonman.reterraforged.client.gui.widget;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

public class Label extends Button {
    
	public Label(int x, int y, int width, int height, Component component) {
    	this(x, y, width, height, (b) -> {}, component);
	}
	
	public Label(int x, int y, int width, int height, OnPress onPress, Component component) {
    	super(x, y, width, height, component, onPress, Supplier::get);
	}

	@Override
    public void playDownSound(SoundManager soundManager) {
    }

	@Override
	public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		Minecraft minecraft = Minecraft.getInstance();
		String text = this.getMessage().getString();
		int maxWidth = Math.max(0, this.getWidth() - 4);
		if(minecraft.font.width(text) > maxWidth) {
			String ellipsis = "...";
			int textWidth = Math.max(0, maxWidth - minecraft.font.width(ellipsis));
			text = minecraft.font.plainSubstrByWidth(text, textWidth) + (textWidth > 0 ? ellipsis : "");
		}
        graphics.drawString(minecraft.font, text, this.getX(), this.getY() + (this.height - 8) / 2, 0xFFFFFF);
	}
}

package com.simmc.meplayeractions.client.ui;

import net.minecraft.client.gui.DrawContext;

/** A 16px pixel cloud matching the existing flat, monochrome gallery controls. */
final class CloudUploadIcon {
    static void draw(DrawContext context,int x,int y,int color,boolean uploaded) {
        context.fill(x+5,y+1,x+10,y+2,color);
        context.fill(x+3,y+2,x+5,y+4,color);
        context.fill(x+10,y+2,x+12,y+5,color);
        context.fill(x+1,y+4,x+3,y+6,color);
        context.fill(x,y+6,x+1,y+10,color);
        context.fill(x+1,y+10,x+5,y+11,color);
        context.fill(x+12,y+5,x+14,y+7,color);
        context.fill(x+14,y+7,x+15,y+10,color);
        context.fill(x+10,y+10,x+14,y+11,color);
        if(uploaded) {
            context.fill(x+4,y+7,x+6,y+9,color);
            context.fill(x+6,y+9,x+8,y+11,color);
            context.fill(x+8,y+7,x+10,y+9,color);
            context.fill(x+10,y+5,x+12,y+7,color);
        } else {
            context.fill(x+7,y+5,x+9,y+14,color);
            context.fill(x+5,y+7,x+7,y+9,color);
            context.fill(x+9,y+7,x+11,y+9,color);
        }
    }
    private CloudUploadIcon() { }
}

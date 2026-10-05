package com.simmc.meplayeractions.client.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import java.math.BigDecimal;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.DoublePredicate;

/** Yarn port of OpenYSM ConfigCheckBox / AnimationSlider, shared by wheel and settings. */
public final class AuthorFormWidgets {
    private static final Identifier ATLAS=Identifier.of("meplayeractions","textures/gui/roulette.png");
    private AuthorFormWidgets() { }
    public static final class Check extends ButtonWidget {
        private final TextRenderer textRenderer;
        private final BooleanSupplier selected;
        private final BiPredicate<Double,Double> viewport;
        public Check(TextRenderer textRenderer,int x,int y,int width,int height,String label,
                     BooleanSupplier selected,Runnable action,BiPredicate<Double,Double> viewport) {
            super(x,y,Math.max(20,width),height,net.minecraft.text.Text.literal(label),button->action.run(),narration->narration.get());
            this.textRenderer=textRenderer;this.selected=selected;this.viewport=viewport;
        }
        public boolean selected() {return selected.getAsBoolean();}
        @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
            int y=getY()+(getHeight()-12)/2;
            context.fill(getX(),getY(),getRight(),getBottom(),0xcf000000);
            context.drawTexture(RenderPipelines.GUI_TEXTURED,ATLAS,getX(),y,selected.getAsBoolean()?128:0,
                    isHovered()?12:0,Math.min(128,getWidth()),12,256,256);
            if(isHovered() || isFocused())context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),-790560);
            context.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(getMessage().getString(),Math.max(1,getWidth()-16)),
                    getX()+14,getY()+(getHeight()-8)/2,active?0xfff3f0e0:0xff8a929c);
        }
        @Override public boolean mouseClicked(Click click,boolean doubled) {
            return viewport.test(click.x(),click.y()) && super.mouseClicked(click,doubled);
        }
    }
    public static final class Range extends SliderWidget {
        public final ModelConfigSchema.Form form;
        private final TextRenderer textRenderer;
        private final DoublePredicate apply;
        private final BiPredicate<Double,Double> viewport;
        private double applied;
        public Range(TextRenderer textRenderer,int x,int y,int width,int height,ModelConfigSchema.Form form,
                     double current,DoublePredicate apply,BiPredicate<Double,Double> viewport) {
            super(x,y,Math.max(20,width),height,Text.empty(),fraction(form,form.snap(current)));
            this.textRenderer=textRenderer;this.form=form;this.apply=apply;this.viewport=viewport;
            applied=form.snap(current);active=form.maximum()!=form.minimum();updateMessage();
        }
        private static double fraction(ModelConfigSchema.Form form,double value) {
            return form.maximum()==form.minimum()?0:(value-form.minimum())/(form.maximum()-form.minimum());
        }
        private double requested() {return form.snap(form.minimum()+value*(form.maximum()-form.minimum()));}
        public double currentValue() {return applied;}
        public void syncValue(double current) {applied=form.snap(current);value=fraction(form,applied);updateMessage();}
        @Override protected void updateMessage() {
            if(form!=null)setMessage(Text.literal(form.title()+": "+number(requested())));
        }
        @Override protected void applyValue() {
            double requested=requested();
            if(requested!=applied && !apply.test(requested))value=fraction(form,applied);
            else {applied=requested;value=fraction(form,requested);}
            updateMessage();
        }
        @Override public boolean keyPressed(KeyInput input) {
            if(active && (input.key()==GLFW.GLFW_KEY_LEFT || input.key()==GLFW.GLFW_KEY_RIGHT)) {
                double step=form.step()>0?form.step()*Math.signum(form.maximum()-form.minimum()):(form.maximum()-form.minimum())/Math.max(1,getWidth()-8);
                value=fraction(form,form.snap(applied+(input.key()==GLFW.GLFW_KEY_LEFT?-step:step)));
                applyValue();return true;
            }
            return super.keyPressed(input);
        }
        @Override public void renderWidget(DrawContext context,int mouseX,int mouseY,float delta) {
            int y=getY()+(getHeight()-15)/2;
            context.drawTexture(RenderPipelines.GUI_TEXTURED,ATLAS,getX(),y,0,24,getWidth()-4,15,256,256);
            context.drawTexture(RenderPipelines.GUI_TEXTURED,ATLAS,getRight()-4,y,196,24,4,15,256,256);
            int handle=getX()+(int)(value*(getWidth()-8)),v=isHovered()?84:64;
            context.drawTexture(RenderPipelines.GUI_TEXTURED,ATLAS,handle,y,0,v,4,15,256,256);
            context.drawTexture(RenderPipelines.GUI_TEXTURED,ATLAS,handle+4,y,196,v,4,15,256,256);
            context.drawCenteredTextWithShadow(textRenderer,textRenderer.trimToWidth(getMessage().getString(),Math.max(1,getWidth()-4)),
                    getX()+getWidth()/2,getY()+(getHeight()-8)/2,active?0xfff3f0e0:0xff8a929c);
        }
        @Override public boolean mouseClicked(Click click,boolean doubled) {
            return viewport.test(click.x(),click.y()) && super.mouseClicked(click,doubled);
        }
    }
    public static String number(double value) {return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
}

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
    private static final Identifier ATLAS = Identifier.of("meplayeractions", "textures/gui/roulette.png");

    private AuthorFormWidgets() {
    }

    public static final class Check extends ButtonWidget {
        private final TextRenderer textRenderer;
        private final BooleanSupplier selected;
        private final BiPredicate<Double, Double> viewport;

        public Check(TextRenderer textRenderer, int x, int y, int width, int height, String label,
                BooleanSupplier selected, Runnable action, BiPredicate<Double, Double> viewport) {
            super(x, y, Math.max(20, width), height, net.minecraft.text.Text.literal(label), button -> action.run(),
                    narration -> narration.get());
            this.textRenderer = textRenderer;
            this.selected = selected;
            this.viewport = viewport;
        }

        public boolean selected() {
            return this.selected.getAsBoolean();
        }

        @Override
        protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
            int y = getY() + (getHeight() - 12) / 2;
            context.fill(getX(), getY(), getRight(), getBottom(), 0xcf000000);
            context.drawTexture(RenderPipelines.GUI_TEXTURED, ATLAS, getX(), y, this.selected.getAsBoolean() ? 128 : 0,
                    isHovered() ? 12 : 0, Math.min(128, getWidth()), 12, 256, 256);
            if (isHovered() || isFocused()) {
                context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
            }

            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(getMessage().getString(), Math.max(1, getWidth() - 16)), getX() + 14,
                    getY() + (getHeight() - 8) / 2, super.active ? 0xfff3f0e0 : 0xff8a929c);
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
            return this.viewport.test(click.x(), click.y()) && super.mouseClicked(click, doubled);
        }
    }

    public static final class Range extends SliderWidget {
        public final ModelConfigSchema.Form form;
        private final TextRenderer textRenderer;
        private final DoublePredicate apply;
        private final BiPredicate<Double, Double> viewport;
        private double applied;

        public Range(TextRenderer textRenderer, int x, int y, int width, int height, ModelConfigSchema.Form form,
                double current, DoublePredicate apply, BiPredicate<Double, Double> viewport) {
            super(x, y, Math.max(20, width), height, Text.empty(), fraction(form, form.snap(current)));
            this.textRenderer = textRenderer;
            this.form = form;
            this.apply = apply;
            this.viewport = viewport;
            this.applied = form.snap(current);
            super.active = form.maximum() != form.minimum();
            updateMessage();
        }

        private static double fraction(ModelConfigSchema.Form form, double value) {
            return form.maximum() == form.minimum() ? 0 : (value - form.minimum()) / (form.maximum() - form.minimum());
        }

        private double requested() {
            return this.form.snap(this.form.minimum() + super.value * (this.form.maximum() - this.form.minimum()));
        }

        public double currentValue() {
            return this.applied;
        }

        public void syncValue(double current) {
            this.applied = this.form.snap(current);
            super.value = fraction(this.form, this.applied);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (this.form != null) {
                setMessage(Text.literal(this.form.title() + ": " + number(requested())));
            }

        }

        @Override
        protected void applyValue() {
            double requested = requested();
            if (requested != this.applied && !this.apply.test(requested)) {
                super.value = fraction(this.form, this.applied);
            }

            else {
                this.applied = requested;
                super.value = fraction(this.form, requested);
            }
            updateMessage();
        }

        @Override
        public boolean keyPressed(KeyInput input) {
            if (super.active && (input.key() == GLFW.GLFW_KEY_LEFT || input.key() == GLFW.GLFW_KEY_RIGHT)) {
                double step = this.form.step() > 0
                        ? this.form.step() * Math.signum(this.form.maximum() - this.form.minimum())
                        : (this.form.maximum() - this.form.minimum()) / Math.max(1, getWidth() - 8);
                super.value = fraction(this.form,
                        this.form.snap(this.applied + (input.key() == GLFW.GLFW_KEY_LEFT ? -step : step)));
                applyValue();
                return true;
            }
            return super.keyPressed(input);
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            int y = getY() + (getHeight() - 15) / 2;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, ATLAS, getX(), y, 0, 24, getWidth() - 4, 15, 256, 256);
            context.drawTexture(RenderPipelines.GUI_TEXTURED, ATLAS, getRight() - 4, y, 196, 24, 4, 15, 256, 256);
            int handle = getX() + (int) (super.value * (getWidth() - 8)), v = isHovered() ? 84 : 64;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, ATLAS, handle, y, 0, v, 4, 15, 256, 256);
            context.drawTexture(RenderPipelines.GUI_TEXTURED, ATLAS, handle + 4, y, 196, v, 4, 15, 256, 256);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(getMessage().getString(), Math.max(1, getWidth() - 4)),
                    getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, super.active ? 0xfff3f0e0 : 0xff8a929c);
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
            return this.viewport.test(click.x(), click.y()) && super.mouseClicked(click, doubled);
        }
    }

    public static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}

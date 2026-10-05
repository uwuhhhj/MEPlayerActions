package com.simmc.meplayeractions.client.model;

/** Sparkle-Morpher b1230a4 FirstOrder/SecondOrder simulation rules, without Minecraft dependencies. */
final class NativeYsmPhysics {
    private final boolean secondOrder;
    private float input, frequency = 1, damping = 1, response = 1;
    private float value, velocity, previousInput;

    NativeYsmPhysics(boolean secondOrder) { this.secondOrder = secondOrder; }
    void args(float input,float frequency,float damping,float response) {
        this.input=input;
        if (secondOrder) { this.frequency=frequency; this.damping=damping; this.response=response; }
        else this.response=frequency;
    }
    float value() { return value; }
    void update(float dt) {
        float target=finite(input);
        if (!secondOrder) {
            float duration=finite(response);
            if (!Float.isFinite(dt) || dt<=0 || duration<=0) { value=target; return; }
            float fraction=Math.min(dt,duration)/duration;
            value=(1-fraction)*value + fraction*target;
            return;
        }
        if (!Float.isFinite(dt) || dt<=0) {
            previousInput=target;
            if (!Float.isFinite(value) || !Float.isFinite(velocity)) snap(target);
            return;
        }
        dt=Math.min(dt,.1f);
        float f=clamp(finite(frequency),.001f,5), z=clamp(finite(damping),0,1), r=finite(response);
        float k1=z/(float)Math.PI/f, k2=1/(2*(float)Math.PI*f)/(2*(float)Math.PI*f);
        float k3=r*z/2/(float)Math.PI/f;
        if (!Float.isFinite(k1) || !Float.isFinite(k2) || k2<=0 || !Float.isFinite(k3)) { snap(target); return; }
        float derivative=(target-previousInput)/dt;
        if (!Float.isFinite(derivative)) derivative=0;
        previousInput=target;
        float maxStep=(float)Math.sqrt(4*k2+k1*k1)-k1;
        if (!Float.isFinite(maxStep) || maxStep<=0) maxStep=dt;
        int cycles=Math.max(1,Math.min(16,(int)Math.ceil(dt/maxStep)));
        float step=dt/cycles, y=Float.isFinite(value)?value:target, dy=Float.isFinite(velocity)?velocity:0;
        for (int i=0;i<cycles;i++) {
            y+=step*dy;
            dy+=step*(k3*derivative + target-y-k1*dy)/k2;
            if (!Float.isFinite(y) || !Float.isFinite(dy)) { snap(target); return; }
        }
        value=y; velocity=dy;
    }
    private void snap(float target) { previousInput=target; value=target; velocity=0; }
    private static float finite(float value) { return Float.isFinite(value) ? value : 0; }
    private static float clamp(float value,float min,float max) { return Math.max(min,Math.min(max,value)); }
}

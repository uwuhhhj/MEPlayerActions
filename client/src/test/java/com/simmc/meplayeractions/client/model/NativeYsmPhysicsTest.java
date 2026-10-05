package com.simmc.meplayeractions.client.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeYsmPhysicsTest {
    @Test void firstOrderUsesAuthorResponseAndClampsOversizedSteps() {
        var physics=new NativeYsmPhysics(false);physics.args(8,.1f,0,0);
        physics.update(.025f);assertEquals(2,physics.value(),1e-6);
        physics.update(.5f);assertEquals(8,physics.value(),1e-6);
    }
    @Test void nonPositiveFirstOrderResponseSnapsToInputLikeUpstream() {
        var physics=new NativeYsmPhysics(false);physics.args(9,0,0,0);
        physics.update(.02f);assertEquals(9,physics.value());
        physics.args(3,-1,0,0);physics.update(.02f);assertEquals(3,physics.value());
    }
    @Test void secondOrderClampsFrameCatchupToOneTenthOfASecond() {
        var a=new NativeYsmPhysics(true);var b=new NativeYsmPhysics(true);
        a.args(16,1,.5f,0);b.args(16,1,.5f,0);
        a.update(.25f);b.update(.1f);assertEquals(b.value(),a.value(),0);
        a.update(.05f);b.update(.05f);assertEquals(b.value(),a.value(),0);
    }
    @Test void zeroFrequencyUsesMinimumFrequencyAndRemainsFinite() {
        var physics=new NativeYsmPhysics(true);physics.args(16,0,.5f,0);
        for(int i=0;i<100;i++)physics.update(.05f);
        assertTrue(Float.isFinite(physics.value()));assertTrue(physics.value()>0);
    }
    @Test void invalidInputsAndTimeStepsAreSanitizedWithoutSharingState() {
        var a=new NativeYsmPhysics(true);var b=new NativeYsmPhysics(true);
        a.args(Float.NaN,Float.NaN,Float.NaN,Float.NaN);a.update(Float.NaN);
        assertEquals(0,a.value());
        b.args(4,1,.5f,0);for(int i=0;i<100;i++)b.update(.05f);
        assertEquals(0,a.value());assertEquals(4,b.value(),.01);
    }
}

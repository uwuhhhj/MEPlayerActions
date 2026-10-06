package com.simmc.meplayeractions.client.effects;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise the same production manager cache used by body, first-person arm and other components. */
class ModelEffectInstancesTest {
    private static final UUID OWNER = new UUID(1, 2), ENTITY = OWNER;
    private static final ModelEffectInstances.Key BODY = new ModelEffectInstances.Key(OWNER, ENTITY, "body");
    private static final ModelEffectInstances.Key ARMS = new ModelEffectInstances.Key(OWNER, ENTITY, "component:fp_arm:arms");
    private static final class Playing {
        final Object entity; final String instance, hash;
        int closes; boolean voice = true;
        Playing(Object entity, String instance, String hash) { this.entity=entity; this.instance=instance; this.hash=hash; }
        void close() { closes++; voice=false; }
    }
    private static ModelEffectInstances<Playing> managers(int maximum) {
        return new ModelEffectInstances<>(maximum, Playing::close);
    }
    private static Playing frame(ModelEffectInstances<Playing> managers,ModelEffectInstances.Key key,Object entity,String instance,String hash) {
        return managers.acquire(key, old->old.entity==entity && old.instance.equals(instance) && old.hash.equals(hash),
                ()->new Playing(entity,instance,hash));
    }

    @Test void alternatingBodyAndArmExtractionKeepsEachPlayingManagerAliveAcrossFrames() {
        var managers=managers(256); Object player=new Object();
        Playing body=frame(managers,BODY,player,"appearance-1","body-bytes");
        Playing arms=frame(managers,ARMS,player,"appearance-1","arm-bytes");
        for(int tick=0;tick<6;tick++) {
            assertSame(body,frame(managers,BODY,player,"appearance-1","body-bytes"));
            assertSame(arms,frame(managers,ARMS,player,"appearance-1","arm-bytes"));
        }
        assertTrue(body.voice); assertTrue(arms.voice);
        assertEquals(0,body.closes); assertEquals(0,arms.closes);
        assertEquals(2,managers.snapshot().size());
    }
    @Test void sharedTemplateBytesStillHaveSeparateProcessorGlobalAudioManagers() {
        var managers=managers(256); Object player=new Object();
        Playing body=frame(managers,BODY,player,"appearance-1","shared-template");
        Playing arms=frame(managers,ARMS,player,"appearance-1","shared-template");
        assertNotSame(body,arms);
        body.close();
        assertFalse(body.voice); assertTrue(arms.voice,"Stopping a body's global audio must not stop another processor's global manager");
    }
    @Test void bindingReplacementClosesOldProcessorThenPrunesTheRemainingOldAppearance() {
        var managers=managers(256); Object player=new Object();
        Playing body=frame(managers,BODY,player,"appearance-1","body-bytes");
        Playing arms=frame(managers,ARMS,player,"appearance-1","arm-bytes");
        Playing next=frame(managers,BODY,player,"appearance-2","body-bytes");
        assertEquals(1,body.closes); assertEquals(0,arms.closes); assertTrue(next.voice);
        managers.retain((key,state)->state.instance.equals("appearance-2"));
        assertEquals(1,arms.closes); assertEquals(0,next.closes);
        assertEquals(1,managers.snapshot().size());
    }
    @Test void componentRemovalAndOwnerExitReleaseAllTheirContextsWithoutClosingOtherOwners() {
        var managers=managers(256); Object player=new Object();
        Playing body=frame(managers,BODY,player,"appearance-1","body-bytes");
        Playing arms=frame(managers,ARMS,player,"appearance-1","arm-bytes");
        UUID other=new UUID(3,4);
        var otherKey=new ModelEffectInstances.Key(other,other,"body");
        Playing neighbor=frame(managers,otherKey,new Object(),"other-appearance","body-bytes");
        managers.retain((key,state)->!key.equals(ARMS));
        assertEquals(1,arms.closes); assertTrue(body.voice); assertTrue(neighbor.voice);
        managers.closeOwner(OWNER);
        assertEquals(1,body.closes); assertEquals(1,arms.closes); assertTrue(neighbor.voice);
        managers.close(); assertEquals(1,neighbor.closes); assertTrue(managers.snapshot().isEmpty());
    }
    @Test void entityRecreationAndCapacityLimitKeepTheExistingClosureAndBudgetBoundaries() {
        var managers=managers(2); Object oldPlayer=new Object(), replacementPlayer=new Object();
        Playing body=frame(managers,BODY,oldPlayer,"appearance-1","body-bytes");
        Playing replacement=frame(managers,BODY,replacementPlayer,"appearance-1","body-bytes");
        assertEquals(1,body.closes); assertTrue(replacement.voice);
        Playing arms=frame(managers,ARMS,replacementPlayer,"appearance-1","arm-bytes");
        AtomicInteger created=new AtomicInteger();
        var third=new ModelEffectInstances.Key(OWNER,new UUID(5,6),"component:projectile:arrow");
        assertNull(managers.acquire(third,old->true,()->{created.incrementAndGet();return new Playing(new Object(),"appearance-1","arrow");}));
        assertEquals(0,created.get());
        assertSame(arms,frame(managers,ARMS,replacementPlayer,"appearance-1","arm-bytes"));
        assertEquals(0,arms.closes); assertEquals(2,managers.snapshot().size());
    }
}

package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivateYsmBehaviorTest {
    @Test void movingPosturesAndOffhandUseAuthoredMovingClips() throws Exception {
        var model=BbModel.parse(YsmFolderModel.bundledDefault()); var policy=LocalMotionPolicy.forModel(model);
        assertEquals("sneaking",policy.clips().get("crouch-idle").animation());
        assertEquals("sneak",policy.clips().get("crouch-walk").animation());
        assertEquals("climbing",policy.clips().get("crawl-idle").animation());
        assertEquals("climb",policy.clips().get("crawl-walk").animation());
        assertEquals("swing_hand",policy.clips().get("swing-mainhand").animation());
        assertEquals("swing_offhand",policy.clips().get("swing-offhand").animation());
        var controller=new EntityAnimationController();
        controller.update(1,sample(0,true),policy,List.of());
        controller.update(2,sample(.2,true),policy,List.of());
        assertTrue(controller.layers().stream().anyMatch(layer->layer.animation().equals("sneak")));
    }
    private EntityAnimationController.Sample sample(double x, boolean crouched) {
        return new EntityAnimationController.Sample(x,0,0,true,false,false,false,false,false,crouched,false,"",false,0,false,false,true,false);
    }
    @Test void actualDefaultHeaddressOptionChangesTheAuthoredMesh() throws Exception {
        var model=BbModel.parse(YsmFolderModel.bundledDefault());
        var layers=List.of(new BbModel.Layer("posture","idle",0,1,"LOOP",0,0));
        var visible=new AnimationPlayer(model); var hidden=new AnimationPlayer(model);
        var a=visible.sample(0,layers,0,0,Map.of(),Map.of(),Map.of("variable.roaming.red_bow_headdress",1d));
        var b=hidden.sample(0,layers,0,0,Map.of(),Map.of(),Map.of("variable.roaming.red_bow_headdress",0d));
        assertNotEquals(a,b,"The setting must change real geometry rather than only a label");
        assertEquals(1d,visible.expressionVariables().get("variable.roaming.red_bow_headdress"));
        assertEquals(0d,hidden.expressionVariables().get("variable.roaming.red_bow_headdress"));
        assertEquals(a,visible.sample(0,layers,0,0,Map.of(),Map.of(),Map.of("variable.roaming.red_bow_headdress",1d)));
    }
    @Test void privateParametersCannotInjectQueriesOrCode() throws Exception {
        var player=new AnimationPlayer(BbModel.parse(YsmFolderModel.bundledDefault()));
        for (var invalid : List.of(Map.of("query.is_flying",1d),Map.of("variable.roaming.a;query.foo",1d),
                Map.of("variable.roaming.bow",Double.NaN),Map.of("variable.roaming.bow",2d)))
            assertThrows(IllegalArgumentException.class,()->player.sample(0,List.of(),0,0,Map.of(),Map.of(),invalid));
    }
}

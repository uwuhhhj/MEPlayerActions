package com.simmc.meplayeractions.client.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelCardActionRegionsTest {
    @Test void favoriteAndCloudCornersRemainSeparateFromModelBrowsing() {
        assertEquals(ModelCardActionRegions.Target.UPLOAD,ModelCardActionRegions.target(8,8,52,true));
        assertEquals(ModelCardActionRegions.Target.FAVORITE,ModelCardActionRegions.target(44,8,52,true));
        assertEquals(ModelCardActionRegions.Target.BROWSE,ModelCardActionRegions.target(26,8,52,true));
        assertEquals(ModelCardActionRegions.Target.BROWSE,ModelCardActionRegions.target(8,17,52,true));
        assertEquals(ModelCardActionRegions.Target.BROWSE,ModelCardActionRegions.target(44,76,52,true));
    }
    @Test void serverModelCardsHaveNoPrivateUploadAction() {
        assertEquals(ModelCardActionRegions.Target.BROWSE,ModelCardActionRegions.target(8,8,52,false));
        assertEquals(ModelCardActionRegions.Target.FAVORITE,ModelCardActionRegions.target(44,8,52,false));
    }
}

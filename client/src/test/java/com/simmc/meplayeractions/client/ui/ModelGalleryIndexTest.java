package com.simmc.meplayeractions.client.ui;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModelGalleryIndexTest {
    private static final List<String> MODELS=List.of("ysm:root","ysm:pack/alice.ysm","ysm:pack/sub/bob.zip","ysm:pack/sub/charlie","local:plain.bbmodel");
    @Test void realDirectoriesHaveImmediateCardsAndRecursiveCounts() {
        var root=ModelGalleryIndex.folders(MODELS,"ysm/");
        assertEquals(List.of(new ModelGalleryIndex.Folder("ysm/pack/","pack",3)),root);
        assertEquals(List.of(new ModelGalleryIndex.Folder("ysm/pack/sub/","sub",2)),ModelGalleryIndex.folders(MODELS,"ysm/pack/"));
        assertTrue(ModelGalleryIndex.direct("ysm/","ysm:root"));
        assertTrue(ModelGalleryIndex.direct("ysm/pack/","ysm:pack/alice.ysm"));
        assertFalse(ModelGalleryIndex.direct("ysm/pack/","ysm:pack/sub/bob.zip"));
        assertTrue(ModelGalleryIndex.contains("ysm/pack/","ysm:pack/sub/bob.zip"));
    }
    @Test void parentNavigationReturnsOneRealDirectoryAtATime() {
        assertEquals("ysm/pack/",ModelGalleryIndex.parent("ysm/pack/sub/"));
        assertEquals("ysm/",ModelGalleryIndex.parent("ysm/pack/"));
        assertEquals("",ModelGalleryIndex.parent("ysm/"));
        assertEquals("bbmodel/",ModelGalleryIndex.parent(ModelGalleryIndex.path("local:plain.bbmodel")));
    }
    @Test void invalidPathsCannotCreateFolderCardsAndCaseDistinctPacksStayDistinct() {
        assertEquals(List.of(),ModelGalleryIndex.folders(List.of("ysm:../escape","ysm:pack//name","local:dir/test.bbmodel"),""));
        assertFalse(ModelGalleryIndex.contains("ysm/","ysm:../escape"));
        assertEquals(2,ModelGalleryIndex.folders(List.of("ysm:Pack/a","ysm:pack/b"),"ysm/").size());
    }
}

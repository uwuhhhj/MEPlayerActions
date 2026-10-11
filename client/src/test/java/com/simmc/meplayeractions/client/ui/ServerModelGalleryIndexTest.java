package com.simmc.meplayeractions.client.ui;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServerModelGalleryIndexTest {
    @Test void homePrioritizesMpaAndKeepsMeUnknownAndHistoricalCacheSeparate() {
        var entries=List.of(entry("me_boss","modelengine","boss"),entry("ysm","own",""),entry("other","unknown",""),cached("old"));
        assertEquals(List.of("own/","modelengine/","unknown/","cache/"),ServerModelGalleryIndex.folders(entries,"").stream().map(ServerModelGalleryIndex.Folder::path).toList());
        assertTrue(entries.stream().noneMatch(entry->ServerModelGalleryIndex.direct("",entry)));
        assertEquals(List.of(1,1,1,1),ServerModelGalleryIndex.folders(entries,"").stream().map(ServerModelGalleryIndex.Folder::count).toList());
    }

    @Test void directRootFilesLiveInAnExplicitUnclassifiedFolder() {
        var root=entry("plain","own","");var nested=entry("winter","own","clothes");
        var folders=ServerModelGalleryIndex.folders(List.of(root,nested),"own/");
        assertEquals(List.of("clothes","未分类"),folders.stream().map(ServerModelGalleryIndex.Folder::label).toList());
        assertFalse(ServerModelGalleryIndex.direct("own/",root));
        assertTrue(ServerModelGalleryIndex.direct("own/unclassified/",root));
        assertFalse(ServerModelGalleryIndex.contains("own/unclassified/",nested));
        assertEquals("own/",ServerModelGalleryIndex.parent("own/unclassified/"));
    }

    @Test void realFoldersNavigateOneActualLevelAndKeepRecursiveCounts() {
        var first=entry("first","modelengine","boss");var second=entry("second","modelengine","boss/ice");
        var third=entry("third","modelengine","boss/ice/deeper");var entries=List.of(first,second,third);
        assertEquals(List.of(new ServerModelGalleryIndex.Folder("modelengine/folders/boss/","boss",3)),ServerModelGalleryIndex.folders(entries,"modelengine/"));
        assertEquals(List.of(new ServerModelGalleryIndex.Folder("modelengine/folders/boss/ice/","ice",2)),ServerModelGalleryIndex.folders(entries,"modelengine/folders/boss/"));
        assertTrue(ServerModelGalleryIndex.direct("modelengine/folders/boss/",first));
        assertFalse(ServerModelGalleryIndex.direct("modelengine/folders/boss/",second));
        assertEquals("modelengine/folders/boss/",ServerModelGalleryIndex.parent("modelengine/folders/boss/ice/"));
        assertEquals("modelengine/",ServerModelGalleryIndex.parent("modelengine/folders/boss/"));
        assertEquals("",ServerModelGalleryIndex.parent("modelengine/"));
    }

    @Test void theSameRelativeFolderNeverMergesDifferentServerSourcesOrCaches() {
        var own=entry("one","own","shared");var me=entry("two","modelengine","shared");
        assertFalse(ServerModelGalleryIndex.contains("own/",me));assertFalse(ServerModelGalleryIndex.contains("modelengine/",own));
        assertEquals(List.of(new ServerModelGalleryIndex.Folder("own/folders/shared/","shared",1)),ServerModelGalleryIndex.folders(List.of(own,me),"own/"));
        assertTrue(ServerModelGalleryIndex.location(cached("old")).contains("仅预览"));
        assertEquals("cache/unclassified/old",ServerModelGalleryIndex.path(cached("old")));
    }

    @Test void authoredFoldersNamedUnclassifiedOrFoldersDoNotCollideWithVirtualGroups() {
        var root=entry("root","own","");var actual=entry("actual","own","unclassified");
        assertNotEquals(ServerModelGalleryIndex.path(root),ServerModelGalleryIndex.path(actual));
        assertEquals(List.of("unclassified","未分类"),ServerModelGalleryIndex.folders(List.of(root,actual),"own/").stream().map(ServerModelGalleryIndex.Folder::label).toList());
        var nested=entry("nested","own","foo/folders/deeper");
        assertEquals(List.of(new ServerModelGalleryIndex.Folder("own/folders/foo/folders/","folders",1)),ServerModelGalleryIndex.folders(List.of(nested),"own/folders/foo/"));
        assertEquals("own/folders/foo/folders/",ServerModelGalleryIndex.parent("own/folders/foo/folders/deeper/"));
    }

    @Test void caseDistinctAndUnicodeFoldersRemainDistinctAndClearlyDescribeSearchResults() {
        var entries=List.of(entry("one","own","Pack"),entry("two","own","pack"),entry("three","own","服装/冬季"));
        assertEquals(3,ServerModelGalleryIndex.folders(entries,"own/").size());
        assertEquals("MEPlayerActions/models\n目录：服装/冬季",ServerModelGalleryIndex.location(entries.get(2)));
        assertEquals("MEPlayerActions/models / 服装/冬季",ServerModelGalleryIndex.directoryLabel("own/folders/服装/冬季/"));
    }

    @Test void legacyUncategorizedIdsStaySeparateFromCurrentServerPublicationAndDoNotChangeIds() {
        var unknown=entry("legacy","unknown","");var old=cached("cache:hash");
        assertEquals("legacy",unknown.id());assertEquals("cache:hash",old.id());
        assertEquals("服务器来源未分类 / 未分类",ServerModelGalleryIndex.directoryLabel("unknown/unclassified/"));
        assertEquals("本地历史缓存 / 未分类",ServerModelGalleryIndex.directoryLabel("cache/unclassified/"));
        assertFalse(ServerModelGalleryIndex.contains("unknown/",old));
    }
    private static ServerModelGalleryIndex.Entry entry(String id,String source,String folder) {return new ServerModelGalleryIndex.Entry(id,source,folder,false);}
    private static ServerModelGalleryIndex.Entry cached(String id) {return new ServerModelGalleryIndex.Entry(id,"unknown","",true);}
}

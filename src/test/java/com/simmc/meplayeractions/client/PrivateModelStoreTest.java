package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PrivateModelStoreTest {
    @TempDir Path temporary;
    @Test void uploadedDirectorySurvivesRestartAndOnlyItsOwnerCanReadTheImmutableSnapshot() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes),sourceId="ysm:角色/DS鲸鱼娘flash.ysm";
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        store.saveValidated(owner,sourceId,hash,"ysm",bytes);
        var receipt=new PrivateModelStore.UploadedModel(sourceId,hash,"ysm",bytes.length);
        assertEquals(List.of(receipt),store.listUploaded(owner));assertTrue(store.listUploaded(other).isEmpty());
        assertEquals(2,files(directory));
        var snapshot=store.snapshot();
        assertThrows(UnsupportedOperationException.class,()->snapshot.models(owner).clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.modelsByOwner().clear());
        var restarted=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        assertTrue(restarted.listUploaded(owner).isEmpty());restarted.refreshCatalog();
        assertEquals(List.of(receipt),restarted.listUploaded(owner));assertTrue(restarted.listUploaded(other).isEmpty());
        var unchanged=restarted.snapshot();restarted.refreshCatalog();assertSame(unchanged,restarted.snapshot());
        assertArrayEquals(bytes,restarted.load(owner,hash,"ysm",bytes.length));
        assertNull(restarted.load(other,hash,"ysm",bytes.length));
        assertSame(unchanged,restarted.snapshot());
    }
    @Test void legacyBundlesRemainReusableButAreNeverGivenAGuessedSourceId() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());store.saveValidated(owner,hash,bytes);
        var restarted=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());restarted.refreshCatalog();
        assertTrue(restarted.listUploaded(owner).isEmpty());assertArrayEquals(bytes,restarted.load(owner,hash,"ysm",bytes.length));
        assertEquals(1,files(directory));
        restarted.saveValidated(owner,"openysm_default",hash,"ysm",bytes);
        assertEquals(List.of(new PrivateModelStore.UploadedModel("openysm_default",hash,"ysm",bytes.length)),restarted.listUploaded(owner));
    }
    @Test void aValidatedCacheHitRecordsOnlyMetadataAndRequiresAnUnchangedOwnerScopedLoad() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());store.saveValidated(owner,hash,bytes);
        assertThrows(java.io.IOException.class,()->store.recordValidatedIdentity(owner,"ysm:renamed.ysm",hash,"ysm",bytes.length));
        assertArrayEquals(bytes,store.load(owner,hash,"ysm",bytes.length));
        Path bundle=directory.resolve(owner+"_"+hash+".zip");FileTime loadedTime=Files.getLastModifiedTime(bundle);
        store.recordValidatedIdentity(owner,"ysm:renamed.ysm",hash,"ysm",bytes.length);
        assertEquals(loadedTime,Files.getLastModifiedTime(bundle));assertArrayEquals(bytes,Files.readAllBytes(bundle));
        assertEquals("ysm:renamed.ysm",store.listUploaded(owner).getFirst().sourceId());
        assertThrows(java.io.IOException.class,()->store.recordValidatedIdentity(UUID.randomUUID(),"ysm:renamed.ysm",hash,"ysm",bytes.length));
        assertThrows(java.io.IOException.class,()->store.recordValidatedIdentity(owner,"ysm:renamed.ysm",hash,"bbmodel",bytes.length));
        Files.setLastModifiedTime(bundle,FileTime.fromMillis(1));
        assertThrows(java.io.IOException.class,()->store.recordValidatedIdentity(owner,"ysm:changed.ysm",hash,"ysm",bytes.length));
    }
    @Test void aHashKeepsItsLatestSourceNameAndASourceNameUsesItsMostRecentlyAccessedBundle() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] first=bundle(1,0),second=bundle(2,0);
        String firstHash=PrivateModelBundle.hash(first),secondHash=PrivateModelBundle.hash(second);
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        store.saveValidated(owner,"ysm:old.ysm",firstHash,"ysm",first);
        store.saveValidated(owner,"ysm:current.ysm",firstHash,"ysm",first);
        assertEquals("ysm:current.ysm",store.listUploaded(owner).getFirst().sourceId());assertEquals(1,store.listUploaded(owner).size());
        Files.setLastModifiedTime(directory.resolve(owner+"_"+firstHash+".zip"),FileTime.fromMillis(1));
        store.saveValidated(owner,"ysm:current.ysm",secondHash,"ysm",second);store.refreshCatalog();
        assertEquals(List.of(new PrivateModelStore.UploadedModel("ysm:current.ysm",secondHash,"ysm",second.length)),store.listUploaded(owner));
        assertEquals(4,files(directory));
    }
    @Test void evictionAndCorruptionRemoveTheMatchingPersistentReceipt() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] first=bundle(1,0),second=bundle(2,0);
        String firstHash=PrivateModelBundle.hash(first),secondHash=PrivateModelBundle.hash(second);
        var store=new PrivateModelStore(directory,new PrivateModelStore.Settings(true,8*1024*1024,1,1));
        store.saveValidated(owner,"ysm:first.ysm",firstHash,"ysm",first);
        store.saveValidated(owner,"ysm:second.ysm",secondHash,"ysm",second);
        assertEquals(2,files(directory));assertFalse(Files.exists(metadata(directory,owner,firstHash)));
        assertEquals(secondHash,store.listUploaded(owner).getFirst().hash());
        Path path=directory.resolve(owner+"_"+secondHash+".zip");byte[] corrupt=second.clone();corrupt[0]^=1;Files.write(path,corrupt);
        Files.setLastModifiedTime(path,FileTime.fromMillis(2));store.refreshCatalog();
        assertTrue(store.listUploaded(owner).isEmpty());assertFalse(Files.exists(path));assertFalse(Files.exists(metadata(directory,owner,secondHash)));
    }
    @Test void changedBundleSizeAndAnInvalidSidecarCannotKeepAnOldReceiptVisible() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());store.saveValidated(owner,"ysm:model.ysm",hash,"ysm",bytes);
        Files.write(directory.resolve(owner+"_"+hash+".zip"),new byte[23]);
        assertNull(store.load(owner,hash,"ysm",bytes.length));assertTrue(store.listUploaded(owner).isEmpty());
        store.refreshCatalog();assertFalse(Files.exists(metadata(directory,owner,hash)));
        store.saveValidated(owner,"ysm:model.ysm",hash,"ysm",bytes);Files.writeString(metadata(directory,owner,hash),"{}");
        store.refreshCatalog();assertTrue(store.listUploaded(owner).isEmpty());assertFalse(Files.exists(metadata(directory,owner,hash)));
        assertTrue(Files.exists(directory.resolve(owner+"_"+hash+".zip")));
    }
    @Test void sidecarsCannotChangeOwnerHashSizeKindOrSourceIdentity() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);
        List<String> invalid=List.of(
                receiptJson(UUID.randomUUID(),"ysm:model.ysm",hash,"ysm",bytes.length),
                receiptJson(owner,"ysm:model.ysm","0".repeat(64),"ysm",bytes.length),
                receiptJson(owner,"ysm:model.ysm",hash,"ysm",bytes.length+1),
                receiptJson(owner,"ysm:../escape.ysm",hash,"ysm",bytes.length),
                receiptJson(owner,"ysm:model.ysm",hash,"bbmodel",bytes.length),
                receiptJson(owner,"ysm:model.ysm",hash,"ysm",bytes.length).replace("\"format\":1","\"format\":1,\"format\":1"),
                receiptJson(owner,"ysm:model.ysm",hash,"ysm",bytes.length).replace("\"format\":1","\"format\":1,\"unknown\":0"),
                " ".repeat(4097));
        for(String json:invalid) {
            var writer=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());writer.saveValidated(owner,"ysm:model.ysm",hash,"ysm",bytes);
            Files.writeString(metadata(directory,owner,hash),json,StandardCharsets.UTF_8);
            var restarted=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());restarted.refreshCatalog();
            assertTrue(restarted.listUploaded(owner).isEmpty(),json);assertFalse(Files.exists(metadata(directory,owner,hash)));
        }
    }
    @Test void bootstrapUsesABoundedValidationBatchAndSnapshotsDoNotTouchTheFilesystem() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();
        var settings=new PrivateModelStore.Settings(true,8*1024*1024,16,16);
        var writer=new PrivateModelStore(directory,settings);
        for(int marker=0;marker<16;marker++) {
            byte[] bytes=bundle(marker,0);writer.saveValidated(owner,"ysm:model"+marker+".ysm",PrivateModelBundle.hash(bytes),"ysm",bytes);
        }
        var restarted=new PrivateModelStore(directory,settings);restarted.refreshCatalog();assertEquals(8,restarted.listUploaded(owner).size());
        restarted.refreshCatalog();assertEquals(16,restarted.listUploaded(owner).size());
        var snapshot=restarted.snapshot();Path moved=temporary.resolve("moved");Files.move(directory,moved);
        assertSame(snapshot,restarted.snapshot());assertEquals(16,restarted.listUploaded(owner).size());
        restarted.refreshCatalog();assertTrue(restarted.listUploaded(owner).isEmpty());assertEquals(16,snapshot.models(owner).size());
        assertEquals(32,files(moved));
    }
    @Test void loweredLimitsAndOrphanMetadataAreReconciledOnceInTheSharedBackgroundRefresh() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();
        var writer=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        byte[] first=bundle(1,0),second=bundle(2,0);String firstHash=PrivateModelBundle.hash(first),secondHash=PrivateModelBundle.hash(second);
        writer.saveValidated(owner,"ysm:first.ysm",firstHash,"ysm",first);writer.saveValidated(owner,"ysm:second.ysm",secondHash,"ysm",second);
        Files.setLastModifiedTime(directory.resolve(owner+"_"+firstHash+".zip"),FileTime.fromMillis(1));
        Path orphan=metadata(directory,UUID.randomUUID(),"0".repeat(64));Files.writeString(orphan,"{}");
        var restricted=new PrivateModelStore(directory,new PrivateModelStore.Settings(true,8*1024*1024,1,1));restricted.refreshCatalog();
        assertEquals(secondHash,restricted.listUploaded(owner).getFirst().hash());assertEquals(2,files(directory));assertFalse(Files.exists(orphan));
    }
    @Test void sourceIdsFollowLocalNamesRatherThanTheServerModelCommandAlphabet() throws Exception {
        for(String id:List.of("openysm_default","openysm_alex","openysm_steve","wine_fox_01_taisho_maid","wine_fox_02_new_year",
                "wine_fox_03_astronaut","ysm:DS鲸鱼娘flash.ysm","ysm:人物/酒狐.zip","local:鲸鱼娘.bbmodel"))
            assertTrue(PrivateModelStore.validSourceId(id),id);
        for(String id:List.of("ysm:","ysm:../model","ysm:.hidden","ysm:model//skin","ysm:model\\skin","ysm:model\n",
                "ysm: model","ysm:model ","ysm:"+"x".repeat(125),"local:.bbmodel","local:model.json","unknown_default","ysm:"+"a/".repeat(9)+"b"))
            assertFalse(PrivateModelStore.validSourceId(id),id);
        assertFalse(PrivateModelStore.validSourceId(null));
        var store=new PrivateModelStore(temporary.resolve("private-models"),PrivateModelStore.Settings.defaults());byte[] bytes=bundle(1,0);
        assertThrows(java.io.IOException.class,()->store.saveValidated(UUID.randomUUID(),"ysm:../bad",PrivateModelBundle.hash(bytes),"ysm",bytes));
        assertFalse(Files.exists(temporary.resolve("private-models")));
    }
    @Test void deletingTheOwnersExactReceiptRemovesOnlyItsPrivateZipAndSidecarAcrossRestart() throws Exception {
        Path directory=temporary.resolve("private-models"),original=temporary.resolve("原始鲸鱼娘.bbmodel");
        Files.writeString(original,"original local source stays intact");UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        byte[] first=bundle(1,0),second=bundle(2,0);String firstHash=PrivateModelBundle.hash(first),secondHash=PrivateModelBundle.hash(second);
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        store.saveValidated(owner,"ysm:鲸鱼娘.ysm",firstHash,"ysm",first);
        store.saveValidated(owner,"ysm:another.ysm",secondHash,"ysm",second);
        store.saveValidated(other,"ysm:other.ysm",firstHash,"ysm",first);
        assertTrue(store.deleteValidated(owner,"ysm:鲸鱼娘.ysm",firstHash,"ysm",first.length));
        assertFalse(Files.exists(directory.resolve(owner+"_"+firstHash+".zip")));assertFalse(Files.exists(metadata(directory,owner,firstHash)));
        assertEquals(secondHash,store.listUploaded(owner).getFirst().hash());assertEquals(firstHash,store.listUploaded(other).getFirst().hash());
        assertEquals(4,files(directory));assertEquals("original local source stays intact",Files.readString(original));
        assertFalse(store.deleteValidated(owner,"ysm:鲸鱼娘.ysm",firstHash,"ysm",first.length));
        var restarted=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());restarted.refreshCatalog();
        assertEquals(secondHash,restarted.listUploaded(owner).getFirst().hash());assertEquals(firstHash,restarted.listUploaded(other).getFirst().hash());
        assertNull(restarted.load(owner,firstHash,"ysm",first.length));assertArrayEquals(first,restarted.load(other,firstHash,"ysm",first.length));
    }
    @Test void deletingTheLatestReceiptRemovesAllVerifiedHistoryOfThatOwnersSourceWithoutResurrectingAnOlderVersion() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        byte[] old=bundle(1,0),latest=bundle(2,0),unrelated=bundle(3,0);
        String oldHash=PrivateModelBundle.hash(old),latestHash=PrivateModelBundle.hash(latest),unrelatedHash=PrivateModelBundle.hash(unrelated),source="ysm:角色.ysm";
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        store.saveValidated(owner,source,oldHash,"ysm",old);
        Files.setLastModifiedTime(directory.resolve(owner+"_"+oldHash+".zip"),FileTime.fromMillis(1));store.refreshCatalog();
        store.saveValidated(owner,source,latestHash,"ysm",latest);store.saveValidated(owner,"ysm:unrelated.ysm",unrelatedHash,"ysm",unrelated);
        store.saveValidated(other,source,oldHash,"ysm",old);
        assertTrue(store.deleteValidated(owner,source,latestHash,"ysm",latest.length));
        for(String hash:List.of(oldHash,latestHash)) {
            assertFalse(Files.exists(directory.resolve(owner+"_"+hash+".zip")));assertFalse(Files.exists(metadata(directory,owner,hash)));
        }
        assertEquals(List.of(new PrivateModelStore.UploadedModel("ysm:unrelated.ysm",unrelatedHash,"ysm",unrelated.length)),store.listUploaded(owner));
        assertEquals(List.of(new PrivateModelStore.UploadedModel(source,oldHash,"ysm",old.length)),store.listUploaded(other));assertEquals(4,files(directory));
        store.refreshCatalog();assertEquals(1,store.listUploaded(owner).size());
        var restarted=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());restarted.refreshCatalog();
        assertEquals(store.listUploaded(owner),restarted.listUploaded(owner));assertEquals(store.listUploaded(other),restarted.listUploaded(other));
    }
    @Test void anOldConfirmationCannotExpandToANewerVersionAndEveryHistoryFingerprintIsCheckedBeforeAnyDeletion() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] old=bundle(1,0),latest=bundle(2,0);
        String oldHash=PrivateModelBundle.hash(old),latestHash=PrivateModelBundle.hash(latest),source="ysm:角色.ysm";
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());store.saveValidated(owner,source,oldHash,"ysm",old);
        Path oldPath=directory.resolve(owner+"_"+oldHash+".zip"),latestPath=directory.resolve(owner+"_"+latestHash+".zip");
        Files.setLastModifiedTime(oldPath,FileTime.fromMillis(1));store.refreshCatalog();store.saveValidated(owner,source,latestHash,"ysm",latest);
        assertFalse(store.deleteValidated(owner,source,oldHash,"ysm",old.length));assertTrue(Files.exists(oldPath));assertTrue(Files.exists(latestPath));
        Files.setLastModifiedTime(oldPath,FileTime.fromMillis(2));
        assertThrows(java.io.IOException.class,()->store.deleteValidated(owner,source,latestHash,"ysm",latest.length));
        assertTrue(Files.exists(oldPath));assertTrue(Files.exists(latestPath));assertEquals(4,files(directory));
        assertEquals(latestHash,store.listUploaded(owner).getFirst().hash());
        store.refreshCatalog();assertTrue(store.deleteValidated(owner,source,latestHash,"ysm",latest.length));assertEquals(0,files(directory));
    }
    @Test void unverifiedSameSourceHistoryBlocksDeletionUntilTheBoundedBackgroundBootstrapChecksIt() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();
        var settings=new PrivateModelStore.Settings(true,8*1024*1024,16,16);var writer=new PrivateModelStore(directory,settings);
        byte[] old=bundle(1,0),latest=bundle(9,0);String oldHash=PrivateModelBundle.hash(old),latestHash=PrivateModelBundle.hash(latest),source="ysm:角色.ysm";
        writer.saveValidated(owner,source,oldHash,"ysm",old);
        Files.setLastModifiedTime(directory.resolve(owner+"_"+oldHash+".zip"),FileTime.fromMillis(1));
        for(int marker=2;marker<=8;marker++) {
            byte[] bytes=bundle(marker,0);String hash=PrivateModelBundle.hash(bytes);
            writer.saveValidated(owner,"ysm:unrelated"+marker+".ysm",hash,"ysm",bytes);
            Files.setLastModifiedTime(directory.resolve(owner+"_"+hash+".zip"),FileTime.fromMillis(marker));
        }
        writer.saveValidated(owner,source,latestHash,"ysm",latest);
        Files.setLastModifiedTime(directory.resolve(owner+"_"+latestHash+".zip"),FileTime.fromMillis(100));
        var restarted=new PrivateModelStore(directory,settings);restarted.refreshCatalog();assertEquals(8,restarted.listUploaded(owner).size());
        var snapshot=restarted.snapshot();
        java.io.IOException pending=assertThrows(java.io.IOException.class,()->restarted.deleteValidated(owner,source,latestHash,"ysm",latest.length));
        assertEquals("cache_delete_pending_validation",pending.getMessage());assertSame(snapshot,restarted.snapshot());assertEquals(18,files(directory));
        restarted.refreshCatalog();assertTrue(restarted.deleteValidated(owner,source,latestHash,"ysm",latest.length));
        assertEquals(7,restarted.listUploaded(owner).size());assertEquals(14,files(directory));
        assertFalse(Files.exists(directory.resolve(owner+"_"+oldHash+".zip")));assertFalse(Files.exists(directory.resolve(owner+"_"+latestHash+".zip")));
        restarted.refreshCatalog();assertTrue(restarted.listUploaded(owner).stream().noneMatch(model->model.sourceId().equals(source)));
    }
    @Test void deletingWithAnotherOwnersIdentityOrAChangedHashKindSizeOrSourceNeverTouchesTheBundle() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        store.saveValidated(owner,"ysm:mine.ysm",hash,"ysm",bytes);store.saveValidated(other,"ysm:other.ysm",hash,"ysm",bytes);
        assertFalse(store.deleteValidated(UUID.randomUUID(),"ysm:mine.ysm",hash,"ysm",bytes.length));
        assertFalse(store.deleteValidated(other,"ysm:mine.ysm",hash,"ysm",bytes.length));
        assertFalse(store.deleteValidated(owner,"ysm:mine.ysm","0".repeat(64),"ysm",bytes.length));
        assertFalse(store.deleteValidated(owner,"ysm:mine.ysm",hash,"bbmodel",bytes.length));
        assertFalse(store.deleteValidated(owner,"ysm:mine.ysm",hash,"ysm",bytes.length+1));
        assertFalse(store.deleteValidated(owner,"ysm:not-mine.ysm",hash,"ysm",bytes.length));
        assertThrows(java.io.IOException.class,()->store.deleteValidated(owner,"ysm:../outside",hash,"ysm",bytes.length));
        assertThrows(java.io.IOException.class,()->store.deleteValidated(owner,"ysm:mine.ysm","../"+hash,"ysm",bytes.length));
        assertEquals(4,files(directory));assertArrayEquals(bytes,Files.readAllBytes(directory.resolve(owner+"_"+hash+".zip")));
        assertEquals(1,store.listUploaded(owner).size());assertEquals(1,store.listUploaded(other).size());
    }
    @Test void deletionRejectsServerSideFileChangesAndMissingResourcesReconcileAsNotFound() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());store.saveValidated(owner,"ysm:model.ysm",hash,"ysm",bytes);
        Path zip=directory.resolve(owner+"_"+hash+".zip"),sidecar=metadata(directory,owner,hash);
        Files.setLastModifiedTime(zip,FileTime.fromMillis(1));
        assertThrows(java.io.IOException.class,()->store.deleteValidated(owner,"ysm:model.ysm",hash,"ysm",bytes.length));
        assertTrue(Files.exists(zip));assertTrue(Files.exists(sidecar));assertTrue(store.listUploaded(owner).isEmpty());
        store.refreshCatalog();assertEquals(1,store.listUploaded(owner).size());
        Files.writeString(sidecar,receiptJson(owner,"ysm:renamed.ysm",hash,"ysm",bytes.length));Files.setLastModifiedTime(sidecar,FileTime.fromMillis(2));
        assertThrows(java.io.IOException.class,()->store.deleteValidated(owner,"ysm:model.ysm",hash,"ysm",bytes.length));
        assertTrue(Files.exists(zip));assertTrue(Files.exists(sidecar));assertTrue(store.listUploaded(owner).isEmpty());
        store.refreshCatalog();assertEquals("ysm:renamed.ysm",store.listUploaded(owner).getFirst().sourceId());
        Files.delete(sidecar);assertFalse(store.deleteValidated(owner,"ysm:renamed.ysm",hash,"ysm",bytes.length));
        assertTrue(Files.exists(zip));assertTrue(store.listUploaded(owner).isEmpty());
        store.saveValidated(owner,"ysm:model.ysm",hash,"ysm",bytes);Files.delete(zip);
        assertFalse(store.deleteValidated(owner,"ysm:model.ysm",hash,"ysm",bytes.length));
        assertFalse(Files.exists(sidecar));assertTrue(store.listUploaded(owner).isEmpty());
        store.saveValidated(owner,"ysm:model.ysm",hash,"ysm",bytes);Files.delete(zip);
        Path outside=temporary.resolve("outside.zip");Files.write(outside,bytes);
        try{Files.createSymbolicLink(zip,outside);}
        catch(UnsupportedOperationException | java.io.IOException unavailable) {
            System.out.println("SYMLINK_BRANCH_NOT_EXECUTED: delete links unavailable; fingerprint and missing-file guards were exercised");return;
        }
        assertThrows(java.io.IOException.class,()->store.deleteValidated(owner,"ysm:model.ysm",hash,"ysm",bytes.length));
        assertTrue(Files.isSymbolicLink(zip));assertArrayEquals(bytes,Files.readAllBytes(outside));assertTrue(store.listUploaded(owner).isEmpty());
        Files.delete(zip);store.saveValidated(owner,"ysm:model.ysm",hash,"ysm",bytes);
        Path moved=temporary.resolve("saved-private-models"),outsideDirectory=temporary.resolve("outside-directory");
        Files.move(directory,moved);Files.createDirectory(outsideDirectory);
        Path outsideMetadata=metadata(outsideDirectory,owner,hash);Files.writeString(outsideMetadata,"outside metadata remains");
        Files.createSymbolicLink(directory,outsideDirectory);
        assertThrows(java.io.IOException.class,()->store.deleteValidated(owner,"ysm:model.ysm",hash,"ysm",bytes.length));
        assertEquals("outside metadata remains",Files.readString(outsideMetadata));assertTrue(store.listUploaded(owner).isEmpty());
    }
    @Test void metadataSymlinksAreNeverFollowedOrOverwrittenAndInvalidRootsFailClosed() throws Exception {
        Path directory=temporary.resolve("private-models"),outside=temporary.resolve("outside.json");UUID owner=UUID.randomUUID();
        byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        store.saveValidated(owner,"ysm:model.ysm",hash,"ysm",bytes);
        Path moved=temporary.resolve("moved");Files.move(directory,moved);Files.writeString(directory,"occupied");
        assertThrows(java.io.IOException.class,store::refreshCatalog);assertTrue(store.listUploaded(owner).isEmpty());
        Files.delete(directory);Files.move(moved,directory);Files.delete(metadata(directory,owner,hash));Files.writeString(outside,"outside remains");
        try{Files.createSymbolicLink(metadata(directory,owner,hash),outside);}
        catch(UnsupportedOperationException | java.io.IOException unavailable) {
            System.out.println("SYMLINK_BRANCH_NOT_EXECUTED: metadata links unavailable; fail-closed invalid directory was exercised");return;
        }
        assertThrows(java.io.IOException.class,()->store.saveValidated(owner,"ysm:model.ysm",hash,"ysm",bytes));
        store.refreshCatalog();assertTrue(store.listUploaded(owner).isEmpty());assertEquals("outside remains",Files.readString(outside));
    }
    @Test void onlyTheUploadingOwnerCanReuseAnExactlyMatchingValidatedBundleAcrossRestarts() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());store.saveValidated(owner,hash,bytes);
        var restarted=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        assertArrayEquals(bytes,restarted.load(owner,hash,"ysm",bytes.length));
        assertNull(restarted.load(UUID.randomUUID(),hash,"ysm",bytes.length));
        assertNull(restarted.load(owner,hash,"ysm",bytes.length+1));
        assertThrows(java.io.IOException.class,()->restarted.load(owner,"../"+hash,"ysm",bytes.length));
        assertEquals(1,files(directory));
    }
    @Test void corruptDiskBytesAndUnexpectedModelKindsCannotBecomePublications() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());store.saveValidated(owner,hash,bytes);
        Path path=directory.resolve(owner+"_"+hash+".zip");byte[] corrupt=bytes.clone();corrupt[0]^=1;Files.write(path,corrupt);
        assertNull(store.load(owner,hash,"ysm",bytes.length));assertFalse(Files.exists(path));
        store.saveValidated(owner,hash,bytes);assertNull(store.load(owner,hash,"bbmodel",bytes.length));assertFalse(Files.exists(path));
    }
    @Test void leastRecentlyUsedFilesRespectBothPerOwnerAndGlobalModelCounts() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID(),other=UUID.randomUUID(),third=UUID.randomUUID();
        var store=new PrivateModelStore(directory,new PrivateModelStore.Settings(true,8*1024*1024,2,1));
        byte[] first=bundle(1,0),second=bundle(2,0),next=bundle(3,0),last=bundle(4,0);
        String hash=PrivateModelBundle.hash(first);store.saveValidated(owner,hash,first);
        store.saveValidated(owner,PrivateModelBundle.hash(second),second);assertNull(store.load(owner,hash,"ysm",first.length));assertEquals(1,files(directory));
        store.saveValidated(other,PrivateModelBundle.hash(next),next);
        Files.setLastModifiedTime(directory.resolve(owner+"_"+PrivateModelBundle.hash(second)+".zip"),FileTime.fromMillis(1));
        store.saveValidated(third,PrivateModelBundle.hash(last),last);
        assertEquals(2,files(directory));assertNull(store.load(owner,PrivateModelBundle.hash(second),"ysm",second.length));
        assertArrayEquals(next,store.load(other,PrivateModelBundle.hash(next),"ysm",next.length));
    }
    @Test void aCacheHitAlsoAppliesLimitsLoweredAfterRestart() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();
        var generous=new PrivateModelStore(directory,new PrivateModelStore.Settings(true,16*1024*1024,10,4));
        byte[] first=bundle(1,0),second=bundle(2,0),last=bundle(3,0);
        generous.saveValidated(owner,PrivateModelBundle.hash(first),first);generous.saveValidated(owner,PrivateModelBundle.hash(second),second);
        generous.saveValidated(owner,PrivateModelBundle.hash(last),last);
        Files.setLastModifiedTime(directory.resolve(owner+"_"+PrivateModelBundle.hash(first)+".zip"),FileTime.fromMillis(1));
        Files.setLastModifiedTime(directory.resolve(owner+"_"+PrivateModelBundle.hash(second)+".zip"),FileTime.fromMillis(2));
        var restricted=new PrivateModelStore(directory,new PrivateModelStore.Settings(true,8*1024*1024,1,1));
        assertArrayEquals(last,restricted.load(owner,PrivateModelBundle.hash(last),"ysm",last.length));assertEquals(1,files(directory));
    }
    @Test void byteBudgetEvictsAnOlderBundleBeforeWritingTheNewOne() throws Exception {
        Path directory=temporary.resolve("private-models");UUID firstOwner=UUID.randomUUID(),secondOwner=UUID.randomUUID();
        var store=new PrivateModelStore(directory,new PrivateModelStore.Settings(true,8*1024*1024,10,4));
        byte[] first=bundle(1,5*1024*1024),second=bundle(2,5*1024*1024);
        assertTrue(first.length+second.length>8*1024*1024);store.saveValidated(firstOwner,PrivateModelBundle.hash(first),first);
        store.saveValidated(secondOwner,PrivateModelBundle.hash(second),second);
        assertEquals(1,files(directory));assertNull(store.load(firstOwner,PrivateModelBundle.hash(first),"ysm",first.length));
        assertArrayEquals(second,store.load(secondOwner,PrivateModelBundle.hash(second),"ysm",second.length));
    }
    @Test void disabledCacheDoesNotCreateOrReadItsDirectory() throws Exception {
        Path directory=temporary.resolve("private-models");UUID owner=UUID.randomUUID();byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);
        var disabled=new PrivateModelStore(directory,new PrivateModelStore.Settings(false,8*1024*1024,10,4));
        disabled.saveValidated(owner,hash,bytes);assertFalse(Files.exists(directory));assertNull(disabled.load(owner,hash,"ysm",bytes.length));
        new PrivateModelStore(directory,PrivateModelStore.Settings.defaults()).saveValidated(owner,hash,bytes);
        assertNull(disabled.load(owner,hash,"ysm",bytes.length));assertEquals(1,files(directory));
    }
    @Test void invalidStorageTargetsNeverResolvePrivateBundlesOutsideTheDirectory() throws Exception {
        Path outside=temporary.resolve("outside");Files.createDirectory(outside);Path directory=temporary.resolve("private-models");
        UUID owner=UUID.randomUUID();byte[] bytes=bundle(1,0);String hash=PrivateModelBundle.hash(bytes);
        Files.writeString(directory,"occupied root");var occupied=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        assertThrows(java.io.IOException.class,()->occupied.saveValidated(owner,hash,bytes));
        assertThrows(java.io.IOException.class,()->occupied.load(owner,hash,"ysm",bytes.length));assertEquals(0,files(outside));
        Files.delete(directory);Files.createDirectory(directory);Path occupiedTarget=directory.resolve(owner+"_"+hash+".zip");Files.createDirectory(occupiedTarget);
        assertNull(occupied.load(owner,hash,"ysm",bytes.length));assertThrows(java.io.IOException.class,()->occupied.saveValidated(owner,hash,bytes));
        assertTrue(Files.isDirectory(occupiedTarget));assertEquals(0,files(outside));Files.delete(occupiedTarget);Files.delete(directory);
        try {Files.createSymbolicLink(directory,outside);}catch(UnsupportedOperationException | java.io.IOException unavailable){
            System.out.println("SYMLINK_BRANCH_NOT_EXECUTED: platform does not permit fixture symbolic links; occupied file/directory guards were exercised");return;
        }
        var store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
        assertThrows(java.io.IOException.class,()->store.saveValidated(owner,hash,bytes));assertEquals(0,files(outside));
        Files.delete(directory);Files.createDirectory(directory);Path target=outside.resolve("original.zip");Files.write(target,bytes);
        Files.createSymbolicLink(directory.resolve(owner+"_"+hash+".zip"),target);
        assertNull(store.load(owner,hash,"ysm",bytes.length));assertThrows(java.io.IOException.class,()->store.saveValidated(owner,hash,bytes));
        assertArrayEquals(bytes,Files.readAllBytes(target));
    }
    private static long files(Path directory) throws Exception {try(var stream=Files.list(directory)){return stream.count();}}
    private static Path metadata(Path directory,UUID owner,String hash){return directory.resolve(owner+"_"+hash+".meta.json");}
    private static String receiptJson(UUID owner,String sourceId,String hash,String kind,int bytes) {
        return "{\"format\":1,\"owner\":\""+owner+"\",\"sourceId\":\""+sourceId+"\",\"hash\":\""+hash+"\",\"kind\":\""+kind+"\",\"bytes\":"+bytes+"}";
    }
    private static byte[] bundle(int marker,int soundBytes) throws Exception {
        Map<String,byte[]> files=new LinkedHashMap<>();files.put("manifest.json","{\"format\":1,\"kind\":\"ysm\",\"entry\":\"ysm.json\"}".getBytes(StandardCharsets.UTF_8));
        files.put("ysm.json",("{\"marker\":"+marker+"}").getBytes(StandardCharsets.UTF_8));
        if(soundBytes>0){byte[] sound=new byte[soundBytes];new Random(marker).nextBytes(sound);System.arraycopy(new byte[]{'O','g','g','S'},0,sound,0,4);files.put("sounds/example.ogg",sound);}
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(output)){for(var file:files.entrySet()){ZipEntry entry=new ZipEntry(file.getKey());entry.setTime(0);zip.putNextEntry(entry);zip.write(file.getValue());zip.closeEntry();}}
        byte[] bundle=output.toByteArray();PrivateModelBundle.validate(bundle,"ysm");return bundle;
    }
}

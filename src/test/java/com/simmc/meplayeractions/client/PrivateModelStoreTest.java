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
    private static byte[] bundle(int marker,int soundBytes) throws Exception {
        Map<String,byte[]> files=new LinkedHashMap<>();files.put("manifest.json","{\"format\":1,\"kind\":\"ysm\",\"entry\":\"ysm.json\"}".getBytes(StandardCharsets.UTF_8));
        files.put("ysm.json",("{\"marker\":"+marker+"}").getBytes(StandardCharsets.UTF_8));
        if(soundBytes>0){byte[] sound=new byte[soundBytes];new Random(marker).nextBytes(sound);System.arraycopy(new byte[]{'O','g','g','S'},0,sound,0,4);files.put("sounds/example.ogg",sound);}
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(output)){for(var file:files.entrySet()){ZipEntry entry=new ZipEntry(file.getKey());entry.setTime(0);zip.putNextEntry(entry);zip.write(file.getValue());zip.closeEntry();}}
        byte[] bundle=output.toByteArray();PrivateModelBundle.validate(bundle,"ysm");return bundle;
    }
}

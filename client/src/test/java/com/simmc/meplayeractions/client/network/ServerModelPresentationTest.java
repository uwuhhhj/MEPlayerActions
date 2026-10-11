package com.simmc.meplayeractions.client.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerModelPresentationTest {
    @Test void ownSourceMissingOrPendingDoesNotClaimThatTheFileWasObtained() {
        var fixture=new Fixture();fixture.source="own";
        for(String status:List.of("missing","pending","invalid")) {
            fixture.serverStatus=status;var display=fixture.display();
            assertTrue(display.tooltip().contains("客户端资源发布目录：plugins/MEPlayerActions/models"));
            assertFalse(display.tooltip().contains("取自 plugins/MEPlayerActions/models"));
        }
        fixture.serverStatus="ready";
        assertTrue(fixture.display().tooltip().contains("取自 plugins/MEPlayerActions/models 的 BBModel"));
    }
    @Test void explicitNoPublicationExplainsMeOnlyWithoutPromisingAFutureAutomaticDownload() {
        var fixture=new Fixture();var display=fixture.display(new ServerModelPresentation.CatalogResource(false,"modelengine","boss"));
        assertEquals(ServerModelPresentation.Cloud.UNAVAILABLE,display.cloud());
        assertTrue(display.tooltip().contains("MPA models 未发布客户端资源"));
        assertTrue(display.tooltip().contains("仅支持服务器 ME 伪装"));
        assertTrue(display.tooltip().contains("不会自动下载或本地接管"));
        assertFalse(display.tooltip().contains("尚未确认"));assertFalse(display.tooltip().contains("等待服务器授权推送"));
    }

    @Test void historicalCacheRemainsAPreviewFactWhenTheCurrentServerPublishesNoResource() {
        var fixture=new Fixture();fixture.cached=true;var display=fixture.display(new ServerModelPresentation.CatalogResource(false,"unknown",""));
        assertEquals(ServerModelPresentation.Cloud.CACHED,display.cloud());
        assertTrue(display.tooltip().contains("本机已有缓存"));
        assertTrue(display.tooltip().contains("旧缓存仅供预览"));
        assertTrue(display.tooltip().contains("本地接管：不可用，仅服务器 ME 伪装"));assertFalse(display.tooltip().contains("已启动"));
    }

    @Test void aPublishedFileStillNeedsValidationAndDoesNotGrantADownloadOrRenderLease() {
        var fixture=new Fixture();var display=fixture.display(new ServerModelPresentation.CatalogResource(true,"own","服装"));
        assertEquals(ServerModelPresentation.Cloud.UNKNOWN,display.cloud());
        assertTrue(display.tooltip().contains("已发布文件，等待校验"));assertTrue(display.tooltip().contains("不等于已校验或已授权下载"));
        assertFalse(display.tooltip().contains("已启动"));assertFalse(display.tooltip().contains("正在下载"));
    }

    @Test void aStaleDirectoryHintCannotOverrideTheCurrentBindingOrAcknowledgedRendering() {
        var fixture=new Fixture();fixture.current=true;fixture.serverStatus="ready";fixture.prepared=true;fixture.active=true;
        var stale=new ServerModelPresentation.CatalogResource(false,"modelengine","");
        assertTrue(fixture.display(stale).tooltip().contains("服务器资源：已找到"));
        assertTrue(fixture.display(stale).tooltip().contains("本地接管：已启动"));
        assertFalse(fixture.display(stale).tooltip().contains("未发布客户端资源"));
        fixture.serverStatus="";
        assertTrue(fixture.display(stale).tooltip().contains("本地接管：已启动"));
        assertFalse(fixture.display(stale).tooltip().contains("未发布客户端资源"));
    }

    @Test void offlineDirectoryHintsCannotDescribeTheCurrentServerAsAvailableOrMissing() {
        var fixture=new Fixture();fixture.online=false;fixture.cached=true;
        var display=fixture.display(new ServerModelPresentation.CatalogResource(false,"own",""));
        assertTrue(display.tooltip().contains("离线，无法确认"));assertFalse(display.tooltip().contains("未发布客户端资源"));
        assertEquals(ServerModelPresentation.Cloud.CACHED,display.cloud());
    }
    @Test void anUnboundDirectoryEntryDoesNotPromiseADownloadOrInferServerAvailability() {
        var display=new Fixture().display();
        assertEquals(ServerModelPresentation.Cloud.UNKNOWN,display.cloud());
        assertTrue(display.tooltip().contains("服务器资源：尚未确认"));
        assertTrue(display.tooltip().contains("使用后服务器检查资源"));
        assertFalse(display.tooltip().contains("已找到"));
        assertFalse(display.tooltip().contains("正在下载"));
        assertFalse(display.tooltip().contains("已启动"));
    }

    @Test void aValidatedLocalCacheDoesNotProveThisServerHasTheResource() {
        var fixture=new Fixture();fixture.cached=true;
        var display=fixture.display();
        assertEquals(ServerModelPresentation.Cloud.CACHED,display.cloud());
        assertEquals(ServerModelPresentation.READY,display.cloudColor());
        assertTrue(display.tooltip().contains("服务器资源：尚未确认"));
        assertTrue(display.tooltip().contains("本机已有缓存"));
        assertFalse(display.tooltip().contains("已启动"));
    }

    @Test void offlineCachedModelsArePreviewOnly() {
        var fixture=new Fixture();fixture.online=false;fixture.cached=true;
        var display=fixture.display();
        assertEquals(ServerModelPresentation.Cloud.CACHED,display.cloud());
        assertTrue(display.tooltip().contains("服务器资源：离线，无法确认"));
        assertTrue(display.tooltip().contains("本地接管：离线，仅可预览"));
    }

    @Test void readyServerSourceWithoutACacheShowsDownloadAvailabilityInsteadOfCached() {
        var fixture=new Fixture();fixture.serverStatus="ready";fixture.source="own";
        var display=fixture.display();
        assertEquals(ServerModelPresentation.Cloud.AVAILABLE,display.cloud());
        assertEquals(ServerModelPresentation.NORMAL,display.cloudColor());
        assertTrue(display.tooltip().contains("plugins/MEPlayerActions/models 的 BBModel"));
        assertFalse(display.tooltip().contains("原始公式完整"));
    }

    @ParameterizedTest @ValueSource(strings={"missing","invalid","server-only","model_complexity"})
    void serverUnavailableStatesKeepTheServerDisguiseAndClearlyDisableTakeover(String serverStatus) {
        var fixture=new Fixture();fixture.current=true;fixture.serverStatus=serverStatus;fixture.reason="服务器给出的原因";
        fixture.source=serverStatus.equals("missing")?"none":"own";
        var display=fixture.display();
        assertEquals(ServerModelPresentation.Cloud.UNAVAILABLE,display.cloud());
        assertEquals(ServerModelPresentation.ERROR,display.cloudColor());
        assertTrue(display.tooltip().contains("服务器伪装：当前使用"));
        assertTrue(display.tooltip().contains("本地接管：不可用，保持服务器显示"));
        assertTrue(display.tooltip().contains("原因：服务器给出的原因"));
        assertFalse(display.tooltip().contains("正在下载"));
    }

    @Test void modelEngineBlueprintsAreIdentifiedWithoutClaimingAuthorScriptsAreComplete() {
        var fixture=new Fixture();fixture.serverStatus="ready";fixture.source="modelengine";
        assertTrue(fixture.display().tooltip().contains("ModelEngine 蓝图；可能缺少作者公式"));
        assertTrue(fixture.display().tooltip().contains("MPA models 目录与内置资源未找到同名 BBModel"));
    }

    @Test void transferAndValidationAreVisibleEvenWhileRenderingIsDisabled() {
        var fixture=new Fixture();fixture.current=true;fixture.serverStatus="ready";fixture.loading=true;
        fixture.renderEnabled=false;fixture.resourceState="正在下载服务器模型";
        var display=fixture.display();
        assertEquals(ServerModelPresentation.Cloud.LOADING,display.cloud());
        assertTrue(display.tooltip().contains("正在下载服务器模型"));
        assertTrue(display.tooltip().contains("渲染已关闭，保持服务器显示"));
        fixture.resourceState="正在校验下载模型";
        assertTrue(fixture.display().tooltip().contains("正在校验下载模型"));
    }

    @Test void cachedDoesNotMeanRenderingAndAStaleAssetStateCannotClaimTakeover() {
        var fixture=new Fixture();fixture.current=true;fixture.serverStatus="ready";fixture.prepared=true;
        fixture.cached=true;fixture.renderEnabled=false;fixture.resourceState="本地渲染";fixture.active=true;
        var display=fixture.display();
        assertEquals(ServerModelPresentation.Cloud.CACHED,display.cloud());
        assertTrue(display.tooltip().contains("客户端资源：模型资源已就绪"));
        assertTrue(display.tooltip().contains("本地接管：渲染已关闭，保持服务器显示"));
        assertFalse(display.tooltip().contains("本地接管：已启动"));
    }

    @Test void preparedAssetsWaitForTheActualServerRenderAcknowledgement() {
        var fixture=new Fixture();fixture.current=true;fixture.serverStatus="ready";fixture.prepared=true;fixture.cached=true;
        assertTrue(fixture.display().tooltip().contains("等待服务器渲染确认"));
        fixture.active=true;
        assertTrue(fixture.display().tooltip().contains("本地接管：已启动"));
        fixture.selfVisible=false;
        assertTrue(fixture.display().tooltip().contains("已启动 · 本人伪装已隐藏"));
    }

    @Test void serverSelfPolicyExplainsWhyPreparedOwnAssetsHaveNoTakeoverLease() {
        var fixture=new Fixture();fixture.current=true;fixture.prepared=true;fixture.serverSelfAllowed=false;
        assertTrue(fixture.display().tooltip().contains("服务器未允许本人显示"));
        assertFalse(fixture.display().tooltip().contains("等待服务器渲染确认"));
    }

    @Test void failedCacheValidationDoesNotKeepAGreenCachedIcon() {
        var fixture=new Fixture();fixture.cached=true;fixture.cacheFailed=true;
        var display=fixture.display();
        assertEquals(ServerModelPresentation.Cloud.UNAVAILABLE,display.cloud());
        assertTrue(display.tooltip().contains("本机缓存校验失败"));
    }

    @Test void installedResourcePackAssetsArePreparedWithoutClaimingAPersistentCache() {
        var fixture=new Fixture();fixture.current=true;fixture.serverStatus="ready";fixture.prepared=true;
        var display=fixture.display();
        assertEquals(ServerModelPresentation.Cloud.PREPARED,display.cloud());
        assertEquals(ServerModelPresentation.NORMAL,display.cloudColor());
        assertTrue(display.tooltip().contains("模型资源已就绪 · 磁盘缓存尚未确认"));
        assertFalse(display.tooltip().contains("本机已有缓存"));
        assertFalse(display.tooltip().contains("正在下载"));
    }

    @Test void memoryPreparationDoesNotHideAnEarlierDiskCacheFailure() {
        var fixture=new Fixture();fixture.current=true;fixture.serverStatus="ready";fixture.cached=true;
        fixture.cacheFailed=true;fixture.prepared=true;fixture.active=true;
        var display=fixture.display();
        assertEquals(ServerModelPresentation.Cloud.PREPARED,display.cloud());
        assertTrue(display.tooltip().contains("模型资源已就绪 · 本机缓存校验失败"));
        assertTrue(display.tooltip().contains("本地接管：已启动"));
    }

    @Test void retainedDisguiseStateWhileOfflineCannotWaitForAnOnlineRenderAcknowledgement() {
        var fixture=new Fixture();fixture.current=true;fixture.online=false;fixture.prepared=true;fixture.active=true;
        var display=fixture.display();
        assertTrue(display.tooltip().contains("本地接管：离线，仅可预览"));
        assertFalse(display.tooltip().contains("本地接管：已启动"));
        assertFalse(display.tooltip().contains("等待服务器渲染确认"));
        fixture.prepared=false;
        assertFalse(fixture.display().tooltip().contains("等待服务器授权推送资源"));
    }

    @Test void cachedIdentityMustMatchTheCurrentBindingAndCannotSatisfyAnUnavailableHash() {
        assertTrue(ServerModelPresentation.cacheMatchesCurrentResource(false,"","old"));
        assertTrue(ServerModelPresentation.cacheMatchesCurrentResource(true,"new","new"));
        assertFalse(ServerModelPresentation.cacheMatchesCurrentResource(true,"new","old"));
        assertFalse(ServerModelPresentation.cacheMatchesCurrentResource(true,"","old"));
        assertFalse(ServerModelPresentation.cacheMatchesCurrentResource(false,"",""));
    }

    @Test void synchronizationFailureIsDistinctFromMissingServerResources() {
        var fixture=new Fixture();fixture.current=true;fixture.serverStatus="ready";fixture.source="jar";
        fixture.resourceError="服务器未授权此次客户端接管";
        var display=fixture.display();
        assertTrue(display.tooltip().contains("服务器资源：已找到 · 插件内置资源"));
        assertTrue(display.tooltip().contains("MPA models 目录未找到同名 BBModel，使用内置资源"));
        assertTrue(display.tooltip().contains("同步失败：服务器未授权此次客户端接管"));
        assertTrue(display.tooltip().contains("本地接管：未启动，保持服务器显示"));
    }

    private static final class Fixture {
        boolean online=true,current,requestPending,renderEnabled=true,serverSelfAllowed=true,selfVisible=true,
                active,prepared,loading,cached,cacheLoading,cacheFailed;
        String serverStatus="",source="",reason="",resourceState="",resourceError="";
        ServerModelPresentation.Display display() {
            return display(ServerModelPresentation.CatalogResource.unknown());
        }
        ServerModelPresentation.Display display(ServerModelPresentation.CatalogResource catalog) {
            return ServerModelPresentation.describe(new ServerModelPresentation.Facts(online,current,requestPending,renderEnabled,
                    serverSelfAllowed,selfVisible,active,prepared,loading,cached,cacheLoading,cacheFailed,
                    serverStatus,source,reason,resourceState,resourceError),catalog);
        }
    }
}

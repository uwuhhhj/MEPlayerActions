package com.simmc.meplayeractions.client.network;

import java.util.ArrayList;
import java.util.List;

/** Binding, server source, local cache and rendering are separate facts, never inferred from a preview. */
public final class ServerModelPresentation {
    public static final int NORMAL=0xff92b9df, READY=0xff8df2b5, WAITING=0xffffd589,
            ERROR=0xffff9292, MUTED=0xffb4becb;
    public enum Cloud { CACHED, PREPARED, AVAILABLE, LOADING, UNAVAILABLE, UNKNOWN }
    public record Facts(boolean online, boolean current, boolean requestPending, boolean renderEnabled,
                        boolean serverSelfAllowed, boolean selfVisible, boolean active, boolean prepared,
                        boolean loading, boolean cached, boolean cacheLoading, boolean cacheFailed,
                        String serverStatus, String source, String reason, String resourceState, String resourceError) {
        public Facts {
            serverStatus=clean(serverStatus);source=clean(source);reason=clean(reason);
            resourceState=clean(resourceState);resourceError=clean(resourceError);
        }
    }
    public record Line(String text,int color) { }
    public record Display(List<Line> lines, Cloud cloud, int cloudColor, String placeholder, String tooltip) { }

    public static Display describe(Facts facts) {
        var lines=new ArrayList<Line>();
        boolean unavailable=!facts.serverStatus().isEmpty() && !List.of("ready","pending").contains(facts.serverStatus());
        String source=sourceLabel(facts.source());
        if(!facts.online())lines.add(new Line("离线模式 · 本机缓存仅供预览",MUTED));
        else if(facts.requestPending())lines.add(new Line("服务器伪装：等待确认",WAITING));
        else if(facts.current())lines.add(new Line("服务器伪装：当前使用",READY));

        String server=switch(facts.serverStatus()) {
            case "ready" -> "服务器资源：已找到"+(source.isEmpty()?"":" · "+source);
            case "pending" -> "服务器资源：正在检查／准备";
            case "missing" -> "服务器资源：未找到客户端 BBModel";
            case "invalid" -> "服务器资源：BBModel 校验失败"+(source.isEmpty()?"":" · "+source);
            case "server-only" -> "服务器资源：此模型不允许客户端接管";
            case "model_complexity" -> "服务器资源：模型超出客户端预算";
            case "asset_too_large" -> "服务器资源：模型超过大小限制";
            case "memory_limit", "memory_budget_exceeded" -> "服务器资源：资产内存预算不足";
            case "asset_queue_full", "server_busy", "tps_protection" -> "服务器资源：准备暂被限流";
            default -> !facts.serverStatus().isEmpty()?"服务器资源：当前不可用":facts.online()?"服务器资源：尚未确认":"服务器资源：离线，无法确认";
        };
        lines.add(new Line(server,unavailable?ERROR: facts.serverStatus().equals("ready")?READY:WAITING));
        if(facts.source().equals("own") && !facts.serverStatus().equals("invalid"))
            lines.add(new Line("取自 plugins/MEPlayerActions/models 的 BBModel",NORMAL));
        else if(facts.source().equals("jar"))
            lines.add(new Line("MPA models 目录未找到同名 BBModel，使用内置资源",MUTED));
        else if(facts.source().equals("modelengine")) {
            lines.add(new Line("MPA models 目录与内置资源未找到同名 BBModel",MUTED));
            lines.add(new Line("取自 ModelEngine 蓝图；可能缺少作者公式",WAITING));
        }
        else if(facts.source().equals("none") && facts.serverStatus().equals("missing"))
            lines.add(new Line("MPA models、内置资源与 ME 蓝图均未找到",ERROR));
        if(!facts.reason().isEmpty())lines.add(new Line("原因："+facts.reason(),unavailable?ERROR:WAITING));

        String resource;
        int resourceColor;
        if(facts.loading()) {resource=facts.resourceState().isEmpty()?"正在接收／校验服务器资源":facts.resourceState();resourceColor=WAITING;}
        else if(facts.prepared()) {resource="模型资源已就绪"+(facts.cacheFailed()?" · 本机缓存校验失败":facts.cached()?"":" · 磁盘缓存尚未确认");resourceColor=facts.cacheFailed()?WAITING:READY;}
        else if(!facts.resourceError().isEmpty() && !unavailable) {resource="同步失败："+facts.resourceError();resourceColor=ERROR;}
        else if(facts.cacheLoading()) {resource="正在校验本机缓存预览";resourceColor=WAITING;}
        else if(facts.cacheFailed()) {resource="本机缓存校验失败，预览不可用";resourceColor=ERROR;}
        else if(facts.cached()) {resource="本机已有缓存 · 尚未准备渲染";resourceColor=READY;}
        else if(unavailable) {resource="未能取得可接管资源";resourceColor=ERROR;}
        else if(facts.current() && facts.online()) {resource="等待服务器授权推送资源";resourceColor=WAITING;}
        else if(!facts.online()) {resource="本机尚无此模型缓存";resourceColor=MUTED;}
        else {resource="尚未缓存 · 使用后服务器检查资源";resourceColor=MUTED;}
        lines.add(new Line("客户端资源："+resource,resourceColor));
        if(facts.prepared() && !facts.resourceError().isEmpty() && !facts.resourceError().equals(facts.reason()))
            lines.add(new Line("接管／同步提示："+facts.resourceError(),ERROR));

        String takeover;
        int takeoverColor=MUTED;
        if(!facts.online())takeover="本地接管：离线，仅可预览";
        else if(!facts.current())takeover="本地接管：尚未使用此服务器模型";
        else if(!facts.renderEnabled())takeover="本地接管：渲染已关闭，保持服务器显示";
        else if(unavailable) {takeover="本地接管：不可用，保持服务器显示";takeoverColor=ERROR;}
        else if(!facts.serverSelfAllowed())takeover="本地接管：服务器未允许本人显示";
        else if(facts.active()) {takeover="本地接管：已启动"+(facts.selfVisible()?"":" · 本人伪装已隐藏");takeoverColor=READY;}
        else if(!facts.resourceError().isEmpty()) {takeover="本地接管：未启动，保持服务器显示";takeoverColor=ERROR;}
        else if(facts.prepared()) {takeover="本地接管：等待服务器渲染确认";takeoverColor=WAITING;}
        else {takeover="本地接管：等待资源，保持服务器显示";takeoverColor=WAITING;}
        lines.add(new Line(takeover,takeoverColor));
        if(facts.current() && !facts.renderEnabled())lines.add(new Line("设置 → 客户端渲染，可开启本地接管",NORMAL));

        Cloud cloud=unavailable?Cloud.UNAVAILABLE: facts.loading()||facts.cacheLoading()?Cloud.LOADING
                :facts.cacheFailed()?facts.prepared()?Cloud.PREPARED:Cloud.UNAVAILABLE: facts.cached()?Cloud.CACHED
                :facts.prepared()?Cloud.PREPARED: !facts.resourceError().isEmpty()?Cloud.UNAVAILABLE
                :facts.serverStatus().equals("ready")?Cloud.AVAILABLE:Cloud.UNKNOWN;
        int tint=switch(cloud){case CACHED->READY;case PREPARED,AVAILABLE->NORMAL;case LOADING->WAITING;case UNAVAILABLE->ERROR;case UNKNOWN->MUTED;};
        String placeholder=unavailable?server: resource;
        var tooltipLines=new ArrayList<Line>();
        for(String prefix:List.of("服务器资源：","客户端资源：","本地接管："))
            lines.stream().filter(line->line.text().startsWith(prefix)).forEach(tooltipLines::add);
        lines.stream().filter(line->!tooltipLines.contains(line)).forEach(tooltipLines::add);
        String tooltip=tooltipLines.stream().map(Line::text).reduce((left,right)->left+"\n"+right).orElse("");
        return new Display(List.copyOf(lines),cloud,tint,placeholder,tooltip);
    }

    private static String sourceLabel(String value) {
        return switch(value) {case "own"->"MPA models 目录";case "jar"->"插件内置资源";case "modelengine"->"ModelEngine 蓝图";default->"";};
    }
    private static String clean(String value) {return value==null?"":value;}
    /** Cached history cannot stand in for a bound model's new or unavailable hash. */
    public static boolean cacheMatchesCurrentResource(boolean bound,String currentHash,String cachedHash) {
        return cachedHash!=null && !cachedHash.isEmpty() && (!bound || currentHash!=null && !currentHash.isEmpty() && currentHash.equals(cachedHash));
    }
    private ServerModelPresentation() { }
}

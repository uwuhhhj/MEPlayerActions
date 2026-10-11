package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.network.PrivateModelSyncClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Passive private-model inspection. Every action here is local; this screen never creates a gameplay binding. */
public final class PrivateSharedCacheScreen extends Screen {
    private static final int MAX_CURRENT_ENTRIES=16;
    private record Entry(String key,String hash,String label,String texture,int bytes,boolean cached,
                         PrivateModelSyncClient.ReceivedModel receiving) { }
    private static final class Preview {LocalModelLibrary.Loaded loaded;String error="";boolean pending;}
    private final ClientRuntime runtime;
    private final Screen parent;
    private final ModelPreview preview;
    private PrivateSharedCacheLibrary library;
    private GalleryPanelLayout.Bounds layout;
    private final Map<String,Preview> previews=new HashMap<>();
    private List<PrivateSharedCacheLibrary.Entry> cached=List.of();
    private List<PrivateModelSyncClient.ReceivedModel> received=List.of();
    private List<Entry> entries=List.of();
    private List<Entry> visible=List.of();
    private String selected="",message="";
    private int page,inFlight,ticks,pendingScanTick=-1;
    private long epoch;
    private boolean open,scanning,dragging;
    private float yaw,pitch=-8;

    public PrivateSharedCacheScreen(ClientRuntime runtime,Screen parent) {
        super(Text.literal("私人分享缓存 · 只读预览"));
        this.runtime=runtime;this.parent=parent;preview=new ModelPreview(runtime);
    }
    @Override protected void init() {
        open=true;layout=GalleryPanelLayout.create(width,height,38,29);
        if(library==null) {
            library=new PrivateSharedCacheLibrary(runtime.localPrivateModelCacheDirectory());
            received=runtime.receivedPrivateModels();scan();
        }
        buildEntries();buildControls();pump();
    }
    private ButtonWidget button(String label,int x,int y,int w,Runnable action,String tooltip) {
        var button=ButtonWidget.builder(Text.literal(label),ignored->action.run()).dimensions(x,y,w,20).build();
        button.setTooltip(ModelUiTooltip.of(textRenderer,width,tooltip));return addDrawableChild(button);
    }
    private void buildControls() {
        clearChildren();int x=layout.right()+5,y=layout.top()+5,w=layout.rightWidth()-10;
        int segment=(w-6)/4;
        button("本人已上传",x,y,segment,this::openUploads,"本人服务器存档\n到客户端云朵查看、分享或删除\n这里的观看缓存不属于本人上传目录");
        button("刷新",x+segment+2,y,segment,()->scan(),"重新检查本机私人缓存\n只读取已有文件，不申请下载");
        button("缓存目录",x+2*(segment+2),y,segment,this::openDirectory,"打开本机私人分享缓存\nconfig/meplayeractions/cache/private-models\n不是服务器伪装模型目录");
        button("教程",x+3*(segment+2),y,w-3*(segment+2),()->client.setScreen(new ModelHelpScreen(runtime,this,true)),
                "私人分享教程\n接收、缓存、显示授权与本人上传管理");
        for(int i=0;i<visible.size();i++) {
            int cardX=layout.right()+5+i%layout.columns()*(GalleryPanelLayout.CARD_WIDTH+GalleryPanelLayout.CARD_GAP);
            int cardY=layout.top()+29+i/layout.columns()*(GalleryPanelLayout.CARD_HEIGHT+GalleryPanelLayout.CARD_GAP);
            addDrawableChild(new Card(visible.get(i),cardX,cardY));
        }
        int navigationWidth=Math.max(35,Math.min(62,(layout.rightWidth()-58)/2));
        button("上一页",layout.right()+5,layout.bottom()-21,navigationWidth,()->changePage(page-1),"上一页私人缓存").active=page>0;
        button("下一页",layout.right()+layout.rightWidth()-navigationWidth-5,layout.bottom()-21,navigationWidth,
                ()->changePage(page+1),"下一页私人缓存").active=page+1<pageCount();
        button("返回",layout.left(),layout.footerY(),48,this::close,"返回服务器模型图库");
        pump();
    }
    private void openUploads() {
        var gallery=new PlayerModelScreen(runtime,this);gallery.showUploadedPrivateModels();client.setScreen(gallery);
    }
    private void openDirectory() {
        try {
            Files.createDirectories(runtime.localPrivateModelCacheDirectory());
            Util.getOperatingSystem().open(runtime.localPrivateModelCacheDirectory());message="已打开本机私人分享缓存";
        }catch(IOException|SecurityException failure){message="无法打开缓存目录，请检查本机文件权限";}
    }
    private void scan() {
        if(scanning||library==null)return;
        scanning=true;message="正在检查本机私人缓存";long requestEpoch=epoch;
        library.scan().whenComplete((found,error)->client.execute(()->{
            if(!open||requestEpoch!=epoch)return;
            scanning=false;if(error==null){cached=found;message="只读预览 · 不下载、不应用伪装";}
            else message="无法读取本机私人缓存";
            // A manual refresh can recover a restored file without requiring the screen to be reopened.
            previews.entrySet().removeIf(entry->!entry.getValue().error.isEmpty());
            received=runtime.receivedPrivateModels();buildEntries();buildControls();
        }));
    }
    private void buildEntries() {
        var byHash=new LinkedHashMap<String,PrivateSharedCacheLibrary.Entry>();cached.forEach(entry->byHash.put(entry.hash(),entry));
        var result=new ArrayList<Entry>();Set<String> liveHashes=new HashSet<>();
        var current=received.stream().sorted(java.util.Comparator.comparingInt(
                (PrivateModelSyncClient.ReceivedModel model)->model.phase()==PrivateModelSyncClient.ReceivingPhase.FAILED?1:0))
                .limit(MAX_CURRENT_ENTRIES).toList();
        for(var remote:current) {
            var file=byHash.get(remote.hash());liveHashes.add(remote.hash());
            String owner=ownerName(remote.owner());String source=remote.modelId().isEmpty()?shortHash(remote.hash()):remote.modelId();
            result.add(new Entry(remote.owner()+":"+remote.hash(),remote.hash(),owner+" · "+source,remote.textureId(),
                    remote.bytes(),file!=null,remote));
        }
        for(var file:cached)if(!liveHashes.contains(file.hash()))
            result.add(new Entry("history:"+file.hash(),file.hash(),"历史缓存 · "+shortHash(file.hash()),"",file.bytes(),true,null));
        entries=List.copyOf(result);page=Math.min(page,pageCount()-1);
        if(entries.stream().noneMatch(entry->entry.key().equals(selected)))selected=entries.isEmpty()?"":entries.getFirst().key();
        int start=page*pageSize();visible=entries.subList(Math.min(start,entries.size()),Math.min(start+pageSize(),entries.size()));
        Set<String> retained=new HashSet<>();visible.forEach(entry->retained.add(previewKey(entry)));
        entries.stream().filter(entry->entry.key().equals(selected)).findFirst().ifPresent(entry->retained.add(previewKey(entry)));
        previews.entrySet().removeIf(entry->{
            if(retained.contains(entry.getKey()))return false;
            preview.release(entry.getKey());return true;
        });
    }
    private String ownerName(UUID owner) {
        var player=client==null||client.getNetworkHandler()==null?null:client.getNetworkHandler().getPlayerListEntry(owner);
        return player==null?owner.toString().substring(0,8):player.getProfile().name();
    }
    private int pageSize(){return layout.columns()*layout.rows();}
    private int pageCount(){return Math.max(1,(entries.size()+pageSize()-1)/pageSize());}
    private void changePage(int next){page=Math.max(0,Math.min(pageCount()-1,next));buildEntries();buildControls();}
    private static String shortHash(String hash){return hash.length()<12?hash:hash.substring(0,12);}
    private static String previewKey(Entry entry){return "private-cache:"+entry.hash()+":"+entry.texture();}
    private void select(Entry entry){selected=entry.key();buildEntries();preview.restart(previewKey(entry),ModelPreview.Context.SELECTED);pump();}
    private void pump() {
        if(library==null||!open)return;
        var wanted=new ArrayList<Entry>();entries.stream().filter(entry->entry.key().equals(selected)).findFirst().ifPresent(wanted::add);
        wanted.addAll(visible);
        for(var entry:wanted) {
            if(inFlight>=2)break;
            if(!entry.cached())continue;
            String key=previewKey(entry);if(previews.containsKey(key))continue;
            var pending=new Preview();pending.pending=true;previews.put(key,pending);inFlight++;long requestEpoch=epoch;
            library.preview(entry.hash(),entry.texture()).whenComplete((loaded,error)->client.execute(()->{
                if(!open||requestEpoch!=epoch)return;
                inFlight=Math.max(0,inFlight-1);
                if(previews.get(key)==pending){pending.pending=false;pending.loaded=loaded;pending.error=error==null?"":"缓存无法解析或已被清理";}
                pump();
            }));
        }
    }
    @Override public void tick() {
        ticks++;
        if(ticks%20==0) {
            var current=runtime.receivedPrivateModels();
            if(!current.equals(received)) {
                received=current;buildEntries();buildControls();pendingScanTick=ticks+20;
            }
        }
        // A new receipt may be saved asynchronously. Reinspect once after that change, never every tick.
        if(pendingScanTick>=0&&ticks>=pendingScanTick){pendingScanTick=-1;scan();}
    }
    private String status(Entry entry) {
        if(entry.receiving()==null)return "历史缓存 · 仅预览";
        var remote=entry.receiving();
        return switch(remote.phase()) {
            case CHECKING_CACHE->"正在核对缓存";
            case WAITING_DOWNLOAD->"等待服务器分发";
            case DOWNLOADING->"接收中 "+(remote.bytes()==0?0:remote.receivedBytes()*100L/remote.bytes())+"%";
            case DECODING->"正在准备客户端模型";
            case WAITING_ACK->"已准备 · 等待服务器确认";
            case ACTIVE->runtime.options.enabled?"已获准显示":"已获准 · 客户端渲染暂停";
            case FAILED->"分享显示失败";
        };
    }
    private int statusColor(Entry entry) {
        if(entry.receiving()==null)return 0xff92b9df;
        return switch(entry.receiving().phase()) {case ACTIVE->0xff8df2b5;case FAILED->0xffff8585;default->0xffffd589;};
    }
    private String tooltip(Entry entry) {
        String owner=entry.receiving()==null?"历史资源未保存发布者身份": "发布者："+ownerName(entry.receiving().owner());
        String detail=entry.receiving()!=null&&!entry.receiving().failure().isEmpty()?"\n失败原因："+entry.receiving().failure():"";
        return entry.label()+"\n"+owner+"\n"+status(entry)+detail+"\n本机文件："+localCacheStatus(entry)
                +"\n点击只预览，不请求下载\n显示人物仍需当次服务器授权\n不能伪装或上传他人资源"
                +"\n完整资源："+Math.max(1,(entry.bytes()+1023)/1024)+" KiB · "+shortHash(entry.hash());
    }
    private String localCacheStatus(Entry entry) {
        var prepared=previews.get(previewKey(entry));
        if(prepared!=null&&!prepared.error.isEmpty())return "已有文件 · 预览校验失败";
        if(prepared!=null&&prepared.loaded!=null)return "已校验并可预览";
        return entry.cached()?"已有文件 · 预览时校验":"尚未保存";
    }
    @Override public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        preview.beginFrame(context);context.fill(layout.left()-4,layout.frameTop(),layout.left()+layout.panelWidth()+4,layout.frameBottom(),0xDD171F2A);
        context.fill(layout.left(),layout.top(),layout.left()+layout.previewWidth(),layout.bottom(),0xE6222730);
        context.fill(layout.right(),layout.top(),layout.right()+layout.rightWidth(),layout.bottom(),0xE6222730);
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,layout.titleY(),0xfff3f6ff);
        if(!layout.compact())context.drawCenteredTextWithShadow(textRenderer,"私人分享与服务器伪装独立 · 本页仅查看已收到的资源",width/2,layout.titleY()+13,0xff92b9df);
        var selectedEntry=entries.stream().filter(entry->entry.key().equals(selected)).findFirst().orElse(null);
        if(selectedEntry!=null)renderSelected(context,selectedEntry,delta);
        else {
            wrapped(context,"尚未收到私人模型\n发布者需主动分享，并由服务器授予观看权限。\n点击刷新可检查本机历史缓存。",layout.left()+6,layout.top()+8,layout.previewWidth()-12,0xffbccce0,10);
            wrapped(context,scanning?"正在读取缓存…":"暂无私人观看缓存\n此页面不会主动申请资源",layout.right()+8,layout.top()+42,layout.rightWidth()-16,0xffbccce0,4);
        }
        context.drawCenteredTextWithShadow(textRenderer,(page+1)+" / "+pageCount(),layout.right()+layout.rightWidth()/2,layout.bottom()-15,0xffe6ecf4);
        clipped(context,message.isEmpty()?"只读 · 最多16项接收状态、40项历史缓存":message,layout.left()+54,layout.footerY()+6,layout.panelWidth()-54,0xffffd589);
        super.render(context,mouseX,mouseY,delta);
    }
    private void renderSelected(DrawContext context,Entry entry,float delta) {
        int x=layout.left()+6,w=layout.previewWidth()-12;
        clipped(context,entry.label(),x,layout.top()+6,w,0xfff3f6ff);
        clipped(context,status(entry),x,layout.top()+17,w,statusColor(entry));
        int y=layout.top()+32,h=Math.max(20,layout.bottom()-y-76);
        var ready=previews.get(previewKey(entry));
        if(ready!=null&&ready.loaded!=null) {
            var loaded=ready.loaded;
            if(!preview.render(context,loaded.model(),previewKey(entry),x,y,w,h,yaw,pitch,ticks+delta,Map.of(),
                    loaded.previewAnimation(),loaded.profile(),ModelPreview.Context.SELECTED))clipped(context,"预览暂不可用",x,y+h/2,w,0xffffd589);
        }else wrapped(context,ready!=null&&!ready.error.isEmpty()?ready.error:entry.cached()?"正在准备预览…":"资源尚未保存到本机\n可以等待分发后点击刷新",x,y+h/2,w,0xffffd589,3);
        wrapped(context,"本机："+localCacheStatus(entry)+"\n只读预览，不改变人物外观\n当次授权结束后仅保留历史预览\n本人上传请点“本人已上传”",x,layout.bottom()-66,w,0xffa7b5c8,6);
    }
    private void clipped(DrawContext context,String value,int x,int y,int w,int color) {
        context.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(value,Math.max(1,w)),x,y,color);
    }
    private void wrapped(DrawContext context,String value,int x,int y,int w,int color,int maxLines) {
        var lines=textRenderer.wrapLines(Text.literal(value),Math.max(20,w));
        for(int index=0;index<Math.min(maxLines,lines.size());index++)context.drawTextWithShadow(textRenderer,lines.get(index),x,y+index*10,color);
    }
    private final class Card extends ButtonWidget {
        private final Entry entry;
        private String lastTooltip="";
        Card(Entry entry,int x,int y) {
            super(x,y,GalleryPanelLayout.CARD_WIDTH,GalleryPanelLayout.CARD_HEIGHT,net.minecraft.text.Text.literal(entry.label()),ignored->select(entry),DEFAULT_NARRATION_SUPPLIER);
            this.entry=entry;setTooltip(ModelUiTooltip.of(textRenderer,PrivateSharedCacheScreen.this.width,tooltip(entry)));
        }
        @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
            context.fill(getX(),getY(),getRight(),getBottom(),0xff303d4e);
            var ready=previews.get(previewKey(entry));boolean drawn=false;
            if(ready!=null&&ready.loaded!=null) {
                var loaded=ready.loaded;context.enableScissor(getX()+1,getY()+1,getRight()-1,getBottom()-20);
                drawn=preview.render(context,loaded.model(),previewKey(entry),getX()+1,getY()+1,getWidth()-2,getHeight()-21,0,-8,ticks+delta,
                        Map.of(),loaded.previewAnimation(),loaded.profile(),ModelPreview.Context.CARD);context.disableScissor();
            }
            if(!drawn)wrapped(context,entry.cached()?ready!=null&&!ready.error.isEmpty()?"预览失败":"加载中…":"等待资源",getX()+4,getY()+32,getWidth()-8,0xffbccce0,2);
            boolean verified=ready!=null&&ready.loaded!=null;
            int cloudColor=verified?0xff8df2b5:ready!=null&&!ready.error.isEmpty()?0xffff8585:entry.cached()?0xffffd589:0xff92b9df;
            CloudUploadIcon.draw(context,getX()+2,getY()+2,cloudColor,verified);
            String explanation=tooltip(entry);
            if(!explanation.equals(lastTooltip)){lastTooltip=explanation;setTooltip(ModelUiTooltip.of(textRenderer,PrivateSharedCacheScreen.this.width,explanation));}
            context.fill(getX()+1,getBottom()-20,getRight()-1,getBottom()-1,0xff263240);
            wrapped(context,entry.label(),getX()+3,getBottom()-19,getWidth()-6,0xfff3f0e0,2);
            if(hovered||isFocused()||entry.key().equals(selected))context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),0xff92b9df);
        }
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(vertical!=0&&x>=layout.right()&&x<layout.right()+layout.rightWidth()){changePage(page+(vertical>0?-1:1));return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean mouseClicked(net.minecraft.client.gui.Click click,boolean doubled) {
        if(click.button()==0&&click.x()>=layout.left()&&click.x()<layout.left()+layout.previewWidth()
                &&click.y()>=layout.top()+32&&click.y()<layout.bottom()-76) {
            dragging=true;if(doubled){yaw=0;pitch=-8;}return true;
        }
        return super.mouseClicked(click,doubled);
    }
    @Override public boolean mouseDragged(net.minecraft.client.gui.Click click,double dx,double dy) {
        if(dragging){yaw=(yaw+(float)dx*1.5f)%360;pitch=Math.max(-90,Math.min(90,pitch-(float)dy));return true;}
        return super.mouseDragged(click,dx,dy);
    }
    @Override public boolean mouseReleased(net.minecraft.client.gui.Click click) {if(dragging){dragging=false;return true;}return super.mouseReleased(click);}
    @Override public void removed() {open=false;epoch++;inFlight=0;scanning=false;previews.clear();preview.clear();if(library!=null){library.close();library=null;}}
    @Override public void close(){client.setScreen(parent);}
    @Override public boolean shouldPause(){return false;}
}

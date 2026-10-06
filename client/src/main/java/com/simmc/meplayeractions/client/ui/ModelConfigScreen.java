package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientOptions;
import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.*;

/** Author-ordered model controls; edits are staged and atomically applied by the runtime engine. */
public final class ModelConfigScreen extends Screen {
    private final ClientRuntime runtime;
    private final String modelId;
    private final Screen parent;
    private final Map<String,String> scripts=new LinkedHashMap<>();
    private final Map<String,Integer> radios=new LinkedHashMap<>();
    private final Map<String,Double> draftValues=new LinkedHashMap<>();
    private final Map<String,Double> readValues=new HashMap<>();
    private final List<ClickableWidget> formWidgets=new ArrayList<>();
    private final List<FormRow> formRows=new ArrayList<>();
    private LocalModelLibrary.Loaded loaded;
    private ModelConfigSchema schema;
    private String groupId,message="";
    private int groupPage,left,panelWidth,listX,listWidth,rowsTop,rowsBottom,formScroll,contentHeight;
    private boolean activeView,pending;
    private long request;
    private ButtonWidget up,down;
    private record FormRow(ModelConfigSchema.Form form,int top,int height) { }

    public ModelConfigScreen(ClientRuntime runtime,String modelId,String groupId,Screen parent) {
        super(Text.literal("模型配置 / 皮肤"));this.runtime=runtime;this.modelId=modelId;
        this.groupId=groupId==null?"":groupId;this.parent=parent;
    }
    public ModelConfigScreen(ClientRuntime runtime,String modelId,Screen parent) {this(runtime,modelId,"",parent);}
    private String locale() {return client==null?"zh_cn":client.getLanguageManager().getLanguage();}
    @Override protected void init() {
        activeView=true;panelWidth=Math.max(200,Math.min(720,width-20));left=(width-panelWidth)/2;
        int groupWidth=Math.max(65,Math.min(150,panelWidth/4));
        listX=left+groupWidth+8;listWidth=panelWidth-groupWidth-8;rowsTop=78;rowsBottom=Math.max(rowsTop+22,height-80);
        formWidgets.clear();formRows.clear();readValues.clear();contentHeight=0;up=null;down=null;
        if(schema!=null && !schema.groups().isEmpty()) {
            if(schema.group(groupId).isEmpty())groupId=schema.groups().getFirst().id();
            int groupSize=Math.max(1,(rowsBottom-rowsTop)/24);
            groupPage=Math.max(0,Math.min(groupPage,(schema.groups().size()-1)/groupSize));
            for(int index=groupPage*groupSize;index<Math.min(schema.groups().size(),(groupPage+1)*groupSize);index++) {
                var group=schema.groups().get(index);
                button((group.id().equals(groupId)?"✓ ":"")+group.name(),left,rowsTop+(index%groupSize)*24,groupWidth,()->{
                    groupId=group.id();formScroll=0;clearAndInit();
                },group.description());
            }
            int navWidth=(groupWidth-4)/2;
            button("‹",left,height-71,navWidth,()->{groupPage--;clearAndInit();},"上一组").active=groupPage>0;
            button("›",left+navWidth+4,height-71,navWidth,()->{groupPage++;clearAndInit();},"下一组").active=(groupPage+1)*groupSize<schema.groups().size();
            for(var form:schema.group(groupId).orElseThrow().forms()) {
                int start=contentHeight;contentHeight+=addForm(form,rowsTop+start);
                formRows.add(new FormRow(form,start,contentHeight-start));
            }
            int nav=Math.max(40,(listWidth-12)/3);
            up=button("向上",listX,height-71,nav,()->scroll(formScroll-Math.max(22,viewportHeight()-22)),"向上浏览作者配置");
            down=button("向下",listX+nav+6,height-71,nav,()->scroll(formScroll+Math.max(22,viewportHeight()-22)),"向下浏览作者配置");
            button("重置配置",listX+2*(nav+6),height-71,nav,this::reset,"恢复此模型的作者默认变量和皮肤，不改变模型启用、缩放或位置");
            int saved=formScroll;formScroll=0;scroll(saved);
        }
        var skin=button(loaded==null?"加载皮肤…":"皮肤："+loaded.profile().selectedTexture(),left,48,panelWidth,this::nextTexture,
                "切换作者提供的原始皮肤并立即保存");
        skin.active=loaded!=null && loaded.profile().textures().size()>1;
        button("返回",left,height-27,58,this::close,"未应用的模型参数不保存");
        button("应用并保存",left+panelWidth-110,height-27,110,this::applyChanges,"执行完整作者配置脚本并保存").active=schema!=null;
        load();
    }
    private ButtonWidget button(String label,int x,int y,int w,Runnable callback,String description) {
        var button=ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(label,Math.max(1,w-8))),b->callback.run())
                .dimensions(x,y,Math.max(20,w),20).build();
        if(!description.isEmpty())button.setTooltip(ModelUiTooltip.of(textRenderer,width,description));
        return addDrawableChild(button);
    }
    private void load() {
        if(loaded!=null || pending)return;
        pending=true;long token=++request;
        runtime.loadLocalPreview(modelId).whenComplete((value,error)->client.execute(()->{
            if(!activeView || request!=token)return;
            pending=false;
            if(error!=null){message=LocalAppearanceScreen.loadError(error);return;}
            try {
                loaded=value;schema=ModelConfigSchema.from(value.profile(),locale());
                if(scripts.isEmpty()) {
                    radios.clear();radios.putAll(runtime.options.modelProfile(modelId).radioSelections());
                    draftValues.clear();draftValues.putAll(runtime.localModelVariables(modelId));
                }
                clearAndInit();
            }catch(RuntimeException invalid){loaded=null;message="模型配置无法使用："+invalid.getMessage();}
        }));
    }
    private double read(ModelConfigSchema.Form form) {
        if(readValues.containsKey(form.key()))return readValues.get(form.key());
        try {
            double value=runtime.readLocalModelExpression(modelId,form.expression(),draftValues);
            value=Double.isFinite(value)?value:0;readValues.put(form.key(),value);return value;
        }catch(RuntimeException invalid){readValues.put(form.key(),0d);return 0;}
    }
    private int selected(ModelConfigSchema.Form form) {
        return scripts.containsKey(form.key()) && radios.containsKey(form.key())?radios.get(form.key()):form.selectedIndex(read(form));
    }
    private <T extends ClickableWidget> T formWidget(T widget,String description) {
        if(!description.isEmpty())widget.setTooltip(ModelUiTooltip.of(textRenderer,width,description));
        formWidgets.add(widget);addSelectableChild(widget);return widget;
    }
    private int addForm(ModelConfigSchema.Form form,int y) {
        switch(form.kind()) {
            case CHECKBOX -> {
                formWidget(new AuthorFormWidgets.Check(textRenderer,listX,y,listWidth,20,form.title(),()->read(form)>0,
                        ()->{stage(form,form.checkboxScript(read(form)<=0));clearAndInit();},this::insideViewport),form.description());
                return 24;
            }
            case RANGE -> {
                int entryWidth=Math.min(86,Math.max(52,listWidth/4));
                TextFieldWidget[] input=new TextFieldWidget[1];
                var slider=formWidget(new AuthorFormWidgets.Range(textRenderer,listX,y,listWidth-entryWidth-4,20,form,read(form),value->{
                    stage(form,form.rangeScript(value));
                    if(input[0]!=null)input[0].setText(AuthorFormWidgets.number(value));
                    return true;
                },this::insideViewport),form.description());
                var field=new TextFieldWidget(textRenderer,listX+listWidth-entryWidth,y,entryWidth,20,Text.literal(form.title())) {
                    @Override public boolean mouseClicked(Click click,boolean doubled) {
                        return insideViewport(click.x(),click.y()) && super.mouseClicked(click,doubled);
                    }
                };
                field.setMaxLength(24);field.setText(AuthorFormWidgets.number(read(form)));
                input[0]=field;
                field.setChangedListener(text->{
                    try{double value=form.snap(Double.parseDouble(text.strip()));stage(form,form.rangeScript(value));slider.syncValue(value);}
                    catch(IllegalArgumentException invalid){scripts.put(form.key(),"");message="请输入有效数字";}
                });
                formWidget(field,form.description()+" ["+AuthorFormWidgets.number(form.minimum())+" … "+AuthorFormWidgets.number(form.maximum())+"] 步长 "+AuthorFormWidgets.number(form.step()));
                return 24;
            }
            case RADIO -> {
                int widest=0;
                for(var choice:form.choices())widest=Math.max(widest,textRenderer.getWidth(choice.label()));
                int columns=AuthorFormLayout.radioColumns(listWidth,widest,form.choices().size()),cellWidth=Math.max(20,listWidth/columns);
                for(int index=0;index<form.choices().size();index++) {
                    int selectedIndex=index;
                    formWidget(new AuthorFormWidgets.Check(textRenderer,listX+index%columns*cellWidth,y+14+index/columns*22,
                            cellWidth-2,20,form.choices().get(index).label(),()->selected(form)==selectedIndex,
                            ()->choose(form,selectedIndex),this::insideViewport),form.description());
                }
                return 18+AuthorFormLayout.radioRows(form.choices().size(),columns)*22;
            }
        }
        throw new IllegalStateException("Unknown author form");
    }
    private void choose(ModelConfigSchema.Form form,int index) {
        if(!radios.containsKey(form.key()) && radios.size()>=ClientOptions.MAX_RADIO_SELECTIONS){message="单选配置保存数量已达上限，请先重置不再使用的配置";return;}
        radios.put(form.key(),index);stage(form,form.radioScript(index));clearAndInit();
    }
    private void stage(ModelConfigSchema.Form form,String script) {
        try {
            var context=new com.simmc.meplayeractions.expression.Molang.Context();draftValues.forEach(context::set);
            var program=com.simmc.meplayeractions.expression.Molang.compile(script);
            if(program.references().stream().anyMatch(name->name.startsWith("query.") || name.startsWith("context.")))
                throw new IllegalStateException("Actual player context required");
            program.evaluate(context);
            for(String key:form.variables())if(context.has(key))draftValues.put(key,context.get(key));
        }catch(RuntimeException liveContextRequired){
            // The complete script is evaluated against the actual player only when explicitly applied.
        }
        scripts.remove(form.key());scripts.put(form.key(),script);message="参数待应用";
        readValues.clear();
    }
    public boolean applyChanges() {
        if(scripts.values().stream().anyMatch(String::isEmpty)){message="请先修正无效的数字";return false;}
        if(scripts.isEmpty()){message="没有待应用的修改";return true;}
        if(scripts.values().stream().mapToLong(String::length).sum()>32768){message="本次配置脚本过长，请分批应用";return false;}
        if(!runtime.runLocalScripts(modelId,List.copyOf(scripts.values()),Map.copyOf(radios))){message="配置未应用，原设置已保留；请检查作者脚本或保存空间";return false;}
        scripts.clear();draftValues.clear();draftValues.putAll(runtime.localModelVariables(modelId));
        message="已保存并应用";clearAndInit();return true;
    }
    private void nextTexture() {
        if(loaded==null || loaded.profile().textures().isEmpty())return;
        var textures=loaded.profile().textures();int index=0;
        for(int i=0;i<textures.size();i++)if(textures.get(i).id().equals(loaded.profile().selectedTexture()))index=i;
        if(runtime.selectLocalTexture(modelId,textures.get((index+1)%textures.size()).id())) {
            loaded=null;pending=false;request++;message="皮肤已保存";clearAndInit();
        }else message="皮肤未保存，请检查模型配置或保存空间";
    }
    private void reset() {
        if(!runtime.canEditLocalAppearance())return;
        if(!runtime.options.resetModelProfile(modelId)){message="重置失败，原设置已保留";return;}
        scripts.clear();radios.clear();draftValues.clear();formScroll=0;
        if(modelId.equals("openysm_default")){runtime.options.defaultHeaddress=true;runtime.options.defaultBlueTexture=false;runtime.options.save();}
        runtime.refreshLocalAppearance();loaded=null;pending=false;request++;message="已恢复此模型默认配置";clearAndInit();
    }
    private int viewportHeight() {return Math.max(1,rowsBottom-rowsTop);}
    private boolean insideViewport(double x,double y) {return x>=listX && x<listX+listWidth && y>=rowsTop && y<rowsBottom;}
    private void scroll(int requested) {
        int next=AuthorFormLayout.clampScroll(requested,contentHeight,viewportHeight()),shift=formScroll-next;formScroll=next;
        for(var widget:formWidgets) {
            widget.setY(widget.getY()+shift);widget.visible=widget.getBottom()>rowsTop && widget.getY()<rowsBottom;
            widget.active=widget.visible && (!(widget instanceof AuthorFormWidgets.Range range) || range.form.maximum()!=range.form.minimum());
            if(!widget.visible && getFocused()==widget){setFocused(null);setDragging(false);}
        }
        if(up!=null)up.active=formScroll>0;
        if(down!=null)down.active=formScroll<Math.max(0,contentHeight-viewportHeight());
    }
    public Map<String,Object> diagnostics() {
        return Map.of("modelId",modelId,"group",groupId,"page",formScroll/viewportHeight(),"groupPage",groupPage,
                "forms",formRows.stream().filter(row->row.top+row.height>formScroll && row.top<formScroll+viewportHeight())
                        .map(row->Map.of("key",row.form.key(),"kind",row.form.kind().name(),"value",read(row.form))).toList(),
                "pendingScripts",scripts.size(),"texture",loaded==null?"":loaded.profile().selectedTexture(),"scroll",formScroll,"contentHeight",contentHeight);
    }
    @Override public void tick() {if(!runtime.canEditLocalAppearance())client.setScreen(new PlayerModelScreen(runtime));}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(vertical!=0 && x>=listX && schema!=null){scroll(formScroll+(vertical>0?-22:22));return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        context.fill(0,0,width,height,0xE0101823);
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,9,0xffffffff);
        context.drawCenteredTextWithShadow(textRenderer,textRenderer.trimToWidth("按模型保存 · "+modelId,Math.max(1,width-20)),width/2,26,0xffa9c8dc);
        context.fill(left,rowsTop-4,left+panelWidth,rowsBottom+2,0xA025303C);
        super.render(context,mouseX,mouseY,delta);
        context.enableScissor(listX,rowsTop,listX+listWidth,rowsBottom);
        for(var row:formRows)if(row.form.kind()==ModelConfigSchema.Kind.RADIO)
            context.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(row.form.title(),Math.max(1,listWidth)),listX,rowsTop+row.top-formScroll+2,0xffe3edf8);
        for(var widget:formWidgets)widget.render(context,mouseX,insideViewport(mouseX,mouseY)?mouseY:-1000,delta);
        context.disableScissor();
        if(schema!=null && schema.groups().isEmpty())context.drawCenteredTextWithShadow(textRenderer,"此模型未定义额外配置",width/2,rowsTop+12,0xffc6d5ec);
        context.drawCenteredTextWithShadow(textRenderer,textRenderer.trimToWidth(message.isEmpty()?pending?"正在加载作者配置…":"修改后点击应用并保存；滚轮浏览配置":message,
                Math.max(1,width-20)),width/2,height-44,0xffffd589);
    }
    @Override public void removed(){activeView=false;request++;pending=false;}
    @Override public void close(){client.setScreen(parent);}
    @Override public boolean shouldPause(){return false;}
}

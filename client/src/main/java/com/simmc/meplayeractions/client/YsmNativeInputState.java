package com.simmc.meplayeractions.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.consume.UseAction;
import net.minecraft.util.Hand;
import java.util.Set;

/**
 * InputStateKey port from Sparkle-Morpher b1230a4 (MIT), adapted to actual Fabric 1.21.11 input.
 * Only real game input/ticks mutate this state. Rendering and Molang reads never emit gameplay packets.
 */
public final class YsmNativeInputState {
    private static final YsmInputPulseClock CLOCK=new YsmInputPulseClock();
    private static Hand swingHand=Hand.MAIN_HAND,useHand=Hand.MAIN_HAND;
    private static ItemStack useItem=ItemStack.EMPTY;
    private static boolean lastAttackDown,lastUseDown,mouseAttackPending,mouseUsePending;
    private static Object activeWorld;
    private YsmNativeInputState() { }

    public static void mouseButton(MinecraftClient client,int button,int action) {
        if(!ready(client)||(button!=0&&button!=1))return;
        if(button==0&&action==1&&!usingOffhandShield(client.player)) {
            mouseAttackPending=true;swingHand=Hand.MAIN_HAND;CLOCK.swing();
        } else if(button==1&&action==0)clearUse();
        else if(button==1&&action==1) {
            mouseUsePending=true;startUse(client);
        }
    }
    public static void tick(MinecraftClient client) {
        if(client.world!=activeWorld) {reset();activeWorld=client.world;}
        if(client.player==null) {reset();return;}
        boolean rawAttack=mouseAttackPending,rawUse=mouseUsePending;mouseAttackPending=false;mouseUsePending=false;
        if(ready(client)) {
            boolean attack=client.options.attackKey.isPressed(),use=client.options.useKey.isPressed();
            boolean newAttack=attack!=lastAttackDown,newUse=use!=lastUseDown;
            lastAttackDown=attack;lastUseDown=use;
            if(attack&&newAttack&&!rawAttack&&!usingOffhandShield(client.player)) {swingHand=Hand.MAIN_HAND;CLOCK.swing();}
            // The same mature mouse-use policy also supports Minecraft's rebindable keyboard use key.
            if(use&&newUse&&!rawUse)startUse(client);
            if(!use&&newUse)clearUse();
        }
        CLOCK.tick(client.player.isUsingItem(),validUse(client.player,useHand));
        if(CLOCK.useTicks()==0)useItem=ItemStack.EMPTY;
    }
    public static void reset() {
        CLOCK.reset();useItem=ItemStack.EMPTY;swingHand=Hand.MAIN_HAND;useHand=Hand.MAIN_HAND;
        lastAttackDown=false;lastUseDown=false;mouseAttackPending=false;mouseUsePending=false;activeWorld=null;
    }
    private static void startUse(MinecraftClient client) {
        var player=client.player;ItemStack off=player.getOffHandStack(),main=player.getMainHandStack();
        if(!off.isEmpty()&&Set.of("sword","axe","pickaxe","shovel","hoe","spear","lance","mace").contains(VanillaYsmQueries.itemState(main).kind()))return;
        Hand hand=isShield(off)||main.isEmpty()&&!off.isEmpty()&&!off.isOf(Items.TOTEM_OF_UNDYING)?Hand.OFF_HAND:Hand.MAIN_HAND;
        ItemStack item=player.getStackInHand(hand);
        if(item.isEmpty()||item.getUseAction()==UseAction.NONE) {clearUse();return;}
        useHand=hand;useItem=item.copy();CLOCK.use();
    }
    private static void clearUse() { CLOCK.clearUse();useItem=ItemStack.EMPTY; }
    private static boolean validUse(LivingEntity entity,Hand hand) {
        return entity!=null&&CLOCK.useTicks()>0&&useHand==hand&&!useItem.isEmpty()&&!entity.getStackInHand(hand).isEmpty()
                &&entity.getStackInHand(hand).getItem()==useItem.getItem();
    }
    private static boolean local(MinecraftClient client,LivingEntity entity) {
        return client.player!=null&&(entity==client.player||entity instanceof PlayerEntity player&&player.getUuid().equals(client.player.getUuid()));
    }
    public static boolean isUsing(MinecraftClient client,LivingEntity entity,Hand hand) {
        return entity!=null&&!entity.isSleeping()&&(entity.isUsingItem()&&entity.getActiveHand()==hand||local(client,entity)&&validUse(entity,hand));
    }
    public static Hand usedHand(MinecraftClient client,LivingEntity entity) {
        return entity.isUsingItem()?entity.getActiveHand():local(client,entity)&&validUse(entity,useHand)?useHand:Hand.MAIN_HAND;
    }
    public static int useTicks(MinecraftClient client,LivingEntity entity) {
        return entity.isUsingItem()?Math.max(0,entity.getItemUseTime()):local(client,entity)&&validUse(entity,useHand)?Math.max(1,CLOCK.useAge()):0;
    }
    public static ItemStack activeItem(MinecraftClient client,LivingEntity entity) {
        return entity.isUsingItem()?entity.getActiveItem():local(client,entity)&&validUse(entity,useHand)?entity.getStackInHand(useHand):ItemStack.EMPTY;
    }
    public static boolean isSwinging(MinecraftClient client,LivingEntity entity,Hand hand) {
        if(entity==null||entity.isSleeping()||hand==Hand.MAIN_HAND&&local(client,entity)&&usingOffhandShield(client.player))return false;
        if(entity.handSwinging&&entity.preferredHand==hand)return true;
        if(!local(client,entity)&&hand==Hand.MAIN_HAND&&entity.getHandSwingProgress(0)>0)return true;
        return local(client,entity)&&CLOCK.swingTicks()>0&&swingHand==hand;
    }
    public static Hand swingingHand(MinecraftClient client,LivingEntity entity) {
        return entity.handSwinging?entity.preferredHand:local(client,entity)&&CLOCK.swingTicks()>0?swingHand:Hand.MAIN_HAND;
    }
    public static float swingTicks(MinecraftClient client,LivingEntity entity,float fraction) {
        if(entity==null||entity.isSleeping()||local(client,entity)&&usingOffhandShield(client.player))return 0;
        return CLOCK.swingTicks(entity.handSwinging,entity.handSwingTicks,fraction,local(client,entity));
    }
    public static float attackProgress(MinecraftClient client,LivingEntity entity,float fraction) {
        if(entity==null||entity.isSleeping()||local(client,entity)&&usingOffhandShield(client.player))return 0;
        float nativeProgress=entity.getHandSwingProgress(fraction);if(nativeProgress>0)return nativeProgress;
        return CLOCK.attackProgress(nativeProgress,local(client,entity)&&swingHand==Hand.MAIN_HAND,
                swingTicks(client,entity,fraction));
    }
    public static long swingSequence(MinecraftClient client,LivingEntity entity) {
        return local(client,entity)&&CLOCK.swingTicks()>0?CLOCK.sequence():0;
    }
    private static boolean usingOffhandShield(PlayerEntity player) {
        return player!=null&&player.isUsingItem()&&player.getActiveHand()==Hand.OFF_HAND&&isShield(player.getActiveItem());
    }
    private static boolean isShield(ItemStack stack) { return !stack.isEmpty()&&VanillaYsmQueries.itemState(stack).kind().equals("shield"); }
    private static boolean ready(MinecraftClient client) {
        return client.player!=null&&client.world!=null&&client.currentScreen==null&&client.getOverlay()==null
                &&client.mouse.isCursorLocked()&&client.isWindowFocused();
    }
}

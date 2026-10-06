package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.expression.Molang;
import com.simmc.meplayeractions.client.render.NativePlayerPresentation;
import com.simmc.meplayeractions.client.mixin.YsmArrowQueryAccessor;
import com.simmc.meplayeractions.client.mixin.YsmFishingQueryAccessor;
import com.simmc.meplayeractions.client.mixin.YsmThrownItemQueryInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.projectile.SpectralArrowEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/** Read-only native-entity bindings. Remote owners never need the client mod or an input packet. */
public final class VanillaYsmQueries implements Molang.FunctionResolver {
    private static final Map<Entity, NativeFrame> FRAMES = new WeakHashMap<>();
    private static final List<String> CONTROL_STATES = List.of("death","riptide","sleep","swim","climb","climbing",
            "ladder_up","ladder_stillness","ladder_down","fly","elytra_fly","swim_stand","attacked","jump",
            "sneak","sneaking","run","walk","idle");
    private final MinecraftClient client;
    private final Entity entity;
    private final LivingEntity living;
    private final Motion motion;
    private final Vec3d position;
    private final boolean nativeYsm;
    private int remaining = 512;
    private VanillaYsmQueries(MinecraftClient client,Entity entity,Motion motion,Vec3d position,boolean nativeYsm) {
        this.client=client;this.entity=entity;this.living=entity instanceof LivingEntity value?value:null;
        this.motion=motion;this.position=position;this.nativeYsm=nativeYsm;
    }

    /**
     * A component's spatial/environment queries belong to the component, never its appearance owner.
     * Equipment and health are available only when the actual component is a living entity.
     * Owner equipment may be delegated explicitly by the caller; this entry point never inherits it.
     */
    public static void populateEntity(MinecraftClient client,Entity entity,int food,String texture,Molang.Context c) {
        if(entity instanceof PlayerEntity player){populate(client,player,food,texture,c);return;}
        if(entity==null||client.world==null)return;
        float fraction=client.getRenderTickCounter().getTickProgress(false);
        Vec3d position=entity.getLerpedPos(fraction);
        Motion motion=frame(entity).motion.sample(client.world.getTime()+fraction,position.x,position.y,position.z,
                Math.max(0,client.getRenderTickCounter().getDynamicDeltaTicks()));
        LivingEntity living=entity instanceof LivingEntity value?value:null;
        clearUnsupportedQueries(c,living!=null);
        populateSpatial(c,position,motion,entity.getVelocity(),MathHelper.wrapDegrees(entity.getYaw()-entity.lastYaw)*20);
        long time=entity.getEntityWorld().getTimeOfDay();
        q(c,"life_time",(entity.age+(double)fraction)/20);
        q(c,"time_stamp",time);q(c,"time_of_day",timeOfDay(time));q(c,"moon_phase",time/24000%8);
        q(c,"actor_count",client.world.getRegularEntityCount());
        q(c,"distance_from_camera",client.gameRenderer.getCamera().getCameraPos().distanceTo(entity.getEntityPos()));
        q(c,"walk_distance",entity.distanceTraveled);q(c,"modified_distance_moved",entity.distanceTraveled);
        q(c,"cardinal_facing_2d",entity.getHorizontalFacing().getIndex());
        q(c,"eye_target_x_rotation",entity.getPitch(fraction));q(c,"eye_target_y_rotation",entity.getYaw(fraction));
        q(c,"is_first_person",false);q(c,"has_rider",entity.hasPassengers());q(c,"is_riding",entity.hasVehicle());
        q(c,"is_in_water",entity.isTouchingWater());q(c,"is_in_water_or_rain",entity.isTouchingWaterOrRain());
        q(c,"is_on_fire",entity.isOnFire());q(c,"is_on_ground",entity.isOnGround());
        q(c,"is_sneaking",entity.isOnGround()&&entity.isInSneakingPose());q(c,"is_spectator",entity.isSpectator());
        q(c,"is_sprinting",entity.isSprinting());q(c,"is_swimming",entity.isSwimming());
        y(c,"fps",client.getCurrentFps());y(c,"person_view",2);y(c,"rendering_in_paperdoll",false);y(c,"rendering_in_inventory",false);
        y(c,"input_vertical",inputDirection(motion.x(),motion.z(),entity.getYaw(fraction),false));
        y(c,"input_horizontal",inputDirection(motion.x(),motion.z(),entity.getYaw(fraction),true));
        y(c,"weather",entity.getEntityWorld().isThundering()?2:entity.getEntityWorld().isRaining()?1:0);
        c.stringQuery("ysm.dimension_name",entity.getEntityWorld().getRegistryKey().getValue().toString());
        if(living!=null) {c.stringQuery("ysm.entity_type",Registries.ENTITY_TYPE.getId(entity.getType()).getPath());y(c,"is_player",false);y(c,"is_maid",false);}
        else for(String name:List.of("entity_type","is_player","is_maid"))c.query("ysm."+name,(Object)null);
        y(c,"is_passenger",entity.hasVehicle());
        y(c,"is_sleep",entity.getPose()==EntityPose.SLEEPING);y(c,"is_sneak",entity.isOnGround()&&entity.isInSneakingPose());
        y(c,"eye_in_water",entity.isSubmergedInWater());c.query("ysm.biome_category",(Object)null);
        BlockPos block=entity.getBlockPos();
        y(c,"is_open_air",entity.getEntityWorld().isSkyVisible(block)
                && entity.getEntityWorld().getTopY(Heightmap.Type.MOTION_BLOCKING,block.getX(),block.getZ())<=block.getY());
        y(c,"block_light",entity.getEntityWorld().getLightLevel(LightType.BLOCK,block));
        y(c,"sky_light",entity.getEntityWorld().getLightLevel(LightType.SKY,block));
        y(c,"frozen_ticks",entity.getFrozenTicks());y(c,"air_supply",entity.getAir());
        y(c,"delta_movement_length",entity.getVelocity().length());
        populateDebugDisabled(c);
        populateBedrockCompatibility(c,entity,fraction);
        populateProjectile(client,entity,c);
        if(living!=null)populateLivingComponent(client,living,fraction,c);
        populateMovement(c,entity,motion,fraction,false);
        if(living!=null)populateControl(c,living,fraction,false);
        c.functions(new VanillaYsmQueries(client,entity,motion,position,c.nativeYsm()));
    }

    private static void populateLivingComponent(MinecraftClient client,LivingEntity entity,float fraction,Molang.Context c){
        ItemStack active=entity.getActiveItem();
        q(c,"body_x_rotation",entity.getPitch(fraction));
        q(c,"body_y_rotation",MathHelper.wrapDegrees(MathHelper.lerp(fraction,entity.lastBodyYaw,entity.bodyYaw)));
        q(c,"health",entity.getHealth());q(c,"max_health",entity.getMaxHealth());q(c,"hurt_time",entity.hurtTime);
        q(c,"is_eating",active.getUseAction().name().equals("EAT"));q(c,"is_playing_dead",entity.isDead());
        q(c,"is_sleeping",entity.isSleeping());q(c,"is_using_item",entity.isUsingItem());
        q(c,"item_in_use_duration",entity.getItemUseTime()/20d);q(c,"item_remaining_use_duration",entity.getItemUseTimeLeft()/20d);
        q(c,"item_max_use_duration",active.isEmpty()?0:active.getMaxUseTime(entity)/20d);
        int equipment=0;
        for(var entry:Map.of(EquipmentSlot.HEAD,"has_helmet",EquipmentSlot.CHEST,"has_chest_plate",
                EquipmentSlot.LEGS,"has_leggings",EquipmentSlot.FEET,"has_boots",EquipmentSlot.MAINHAND,"has_mainhand",
                EquipmentSlot.OFFHAND,"has_offhand").entrySet()){
            boolean present=!entity.getEquippedStack(entry.getKey()).isEmpty();y(c,entry.getValue(),present);
            if(present&&entry.getKey()!=EquipmentSlot.MAINHAND&&entry.getKey()!=EquipmentSlot.OFFHAND)equipment++;
        }
        q(c,"equipment_count",equipment);y(c,"food_level",20); // Source fallback for a non-player living entity.
        y(c,"has_elytra",entity.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER));
        y(c,"is_riptide",entity.isUsingRiptide());y(c,"armor_value",entity.getArmor());y(c,"hurt_time",entity.hurtTime);
        y(c,"is_close_eyes",closeEyes(c.get("query.life_time")*20,entity.getUuid().getLeastSignificantBits(),entity.isSleeping()));
        y(c,"on_ladder",entity.isClimbing());
        y(c,"ladder_facing",entity.getClimbingPos().flatMap(pos->entity.getEntityWorld().getBlockState(pos)
                .getOrEmpty(net.minecraft.state.property.Properties.HORIZONTAL_FACING)).map(direction->direction.getHorizontalQuarterTurns()).orElse(0));
        y(c,"arrow_count",entity.getStuckArrowCount());y(c,"stinger_count",entity.getStingerCount());
        y(c,"xxa",entity.sidewaysSpeed);y(c,"yya",entity.upwardSpeed);y(c,"zza",entity.forwardSpeed);
        y(c,"mainhand_charged_crossbow",charged(entity.getMainHandStack()));y(c,"offhand_charged_crossbow",charged(entity.getOffHandStack()));
        y(c,"swinging",entity.handSwinging);y(c,"swing_time",entity.handSwingTicks);
        y(c,"swinging_arm",entity.preferredHand==Hand.OFF_HAND?1:0);y(c,"attack_time",entity.getHandSwingProgress(fraction));
        populateLivingSupplement(client,c,entity,fraction);
    }

    static void populateSpatial(Molang.Context c,Vec3d position,Motion motion,Vec3d velocity,double yawSpeed){
        q(c,"position_0",position.x);q(c,"position_1",position.y);q(c,"position_2",position.z);
        q(c,"position_delta_0",motion.x());q(c,"position_delta_1",motion.y());q(c,"position_delta_2",motion.z());
        q(c,"ground_speed",motionGroundSpeed(motion)>1e-4?motionGroundSpeed(motion):Math.hypot(velocity.x,velocity.z)*20);
        q(c,"vertical_speed",motion.ticks()>0?motion.y()*20/motion.ticks():0);
        q(c,"yaw_speed",yawSpeed);q(c,"delta_time",motion.ticks()/20);
        y(c,"ground_speed2",motion.ticks()>0?Math.hypot(motion.x(),motion.z())*20/motion.ticks():0);
        y(c,"time_delta",motion.ticks()/20);
    }

    static void clearUnsupportedQueries(Molang.Context c,boolean living){
        clearProjectileQueries(c);
        for(String name:List.of("query.is_jumping","query.player_level","query.has_cape","query.cape_flap_amount",
                "ysm.texture_name","ysm.first_person_mod_hide","ysm.has_left_shoulder_parrot","ysm.has_right_shoulder_parrot",
                "ysm.left_shoulder_parrot_variant","ysm.right_shoulder_parrot_variant","ysm.attack_damage","ysm.attack_speed",
                "ysm.attack_knockback","ysm.movement_speed","ysm.knockback_resistance","ysm.luck","ysm.block_reach",
                "ysm.entity_reach","ysm.swim_speed","ysm.entity_gravity","ysm.step_height_addition","ysm.nametag_distance",
                "ysm.in_shield_block_cooldown","ysm.elytra_rot_x","ysm.elytra_rot_y","ysm.elytra_rot_z",
                "ysm.hit_target_id","ysm.hit_target_type","ysm.is_fishing"))c.query(name,(Object)null);
        if(!living)for(String name:List.of("query.body_x_rotation","query.body_y_rotation","query.health","query.max_health",
                "query.hurt_time","query.is_eating","query.is_playing_dead","query.is_sleeping","query.is_using_item",
                "query.item_in_use_duration","query.item_max_use_duration","query.item_remaining_use_duration","query.equipment_count",
                "ysm.has_helmet","ysm.has_chest_plate","ysm.has_leggings","ysm.has_boots","ysm.has_mainhand","ysm.has_offhand",
                "ysm.has_elytra","ysm.is_riptide","ysm.armor_value","ysm.hurt_time","ysm.is_close_eyes","ysm.on_ladder",
                "ysm.ladder_facing","ysm.arrow_count","ysm.stinger_count","ysm.food_level","ysm.xxa","ysm.yya","ysm.zza",
                "ysm.mainhand_charged_crossbow","ysm.offhand_charged_crossbow","ysm.swinging","ysm.swing_time",
                "ysm.swinging_arm","ysm.attack_time","query.is_swinging","query.swing_time","query.attack_time",
                "ysm.swim_amount","ysm.item_use_normalized","ysm.is_holding_right","ysm.is_holding_left"))c.query(name,(Object)null);
        if(!living)VanillaYsmWeaponQueries.unavailable(c);
        y(c,"native_living_available",living);y(c,"native_player_available",false);y(c,"in_shield_block_cooldown_available",false);
        for(String state:CONTROL_STATES)c.query("ctrl."+state,0d);
    }

    public static void populate(MinecraftClient client,PlayerEntity player,int food,String texture,Molang.Context c) {
        populate(client,player,food,texture,player!=null && player.getAbilities().flying,c);
    }
    public static void populate(MinecraftClient client,PlayerEntity player,int food,String texture,boolean flying,Molang.Context c) {
        if(player==null || client.world==null)return;
        var presentation=NativePlayerPresentation.frame(player);
        float fraction=presentation.tickDelta();
        Vec3d position=presentation.entityPosition();
        NativeFrame frame=frame(player);
        Motion motion=frame.motion.sample(client.world.getTime()+fraction,position.x,position.y,position.z,
                Math.max(0,client.getRenderTickCounter().getDynamicDeltaTicks()));
        Vec3d delta=new Vec3d(motion.x(),motion.y(),motion.z());
        clearUnsupportedQueries(c,true);
        populateSpatial(c,position,motion,player.getVelocity(),MathHelper.wrapDegrees(player.getYaw()-player.lastYaw)*20);
        y(c,"native_living_available",true);y(c,"native_player_available",true);
        ItemStack active=player.getActiveItem();
        double horizontal=motion.ticks()>0?Math.hypot(delta.x,delta.z)*20/motion.ticks():0;
        long time=client.world.getTimeOfDay();Vec3d velocity=player.getVelocity();
        q(c,"yaw_speed",MathHelper.wrapDegrees(player.getYaw()-player.lastYaw)*20);
        q(c,"position_0",position.x);q(c,"position_1",position.y);q(c,"position_2",position.z);
        q(c,"position_delta_0",delta.x);q(c,"position_delta_1",delta.y);q(c,"position_delta_2",delta.z);
        q(c,"time_stamp",time);q(c,"time_of_day",timeOfDay(time));q(c,"moon_phase",time/24000%8);
        q(c,"delta_time",motion.ticks()/20);q(c,"distance_from_camera",client.gameRenderer.getCamera().getCameraPos().distanceTo(player.getEntityPos()));
        q(c,"actor_count",client.world.getRegularEntityCount());
        q(c,"walk_distance",player.distanceTraveled);q(c,"modified_distance_moved",player.distanceTraveled);
        q(c,"cardinal_facing_2d",player.getHorizontalFacing().getIndex());
        q(c,"eye_target_x_rotation",player.getPitch(fraction));q(c,"eye_target_y_rotation",player.getYaw(fraction));
        q(c,"body_x_rotation",player.getPitch(fraction));q(c,"body_y_rotation",MathHelper.wrapDegrees(MathHelper.lerp(fraction,player.lastBodyYaw,player.bodyYaw)));
        q(c,"is_first_person",player==client.player&&player==client.getCameraEntity()&&client.options.getPerspective().isFirstPerson());
        q(c,"has_rider",player.hasPassengers());q(c,"is_riding",player.hasVehicle());
        q(c,"is_in_water",player.isTouchingWater());q(c,"is_in_water_or_rain",player.isTouchingWaterOrRain());q(c,"is_on_fire",player.isOnFire());
        q(c,"is_on_ground",player.isOnGround());q(c,"is_sneaking",player.isOnGround()&&player.isInSneakingPose());
        q(c,"is_spectator",player.isSpectator());q(c,"is_sprinting",player.isSprinting());q(c,"is_swimming",player.isSwimming());
        q(c,"health",player.getHealth());q(c,"max_health",player.getMaxHealth());q(c,"hurt_time",player.hurtTime);
        q(c,"is_eating",active.getUseAction().name().equals("EAT"));q(c,"is_playing_dead",player.isDead());
        q(c,"is_sleeping",player.isSleeping());q(c,"is_using_item",player.isUsingItem());
        q(c,"item_in_use_duration",player.getItemUseTime()/20d);q(c,"item_max_use_duration",active.isEmpty()?0:active.getMaxUseTime(player)/20d);
        q(c,"item_remaining_use_duration",player.getItemUseTimeLeft()/20d);
        q(c,"is_jumping",!flying&&!player.hasVehicle()&&!player.isOnGround()&&!player.isTouchingWater());
        q(c,"player_level",player.experienceLevel);
        int equipment=0;for(EquipmentSlot slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))if(!player.getEquippedStack(slot).isEmpty())equipment++;
        q(c,"equipment_count",equipment);
        if(player instanceof AbstractClientPlayerEntity avatar) {
            q(c,"has_cape",!player.isInvisible()&&player.isModelPartVisible(PlayerModelPart.CAPE)&&avatar.getSkin().cape()!=null);
            var cape=avatar.getState();
            q(c,"cape_flap_amount",capeFlap(cape.lerpX(fraction)-position.x,cape.lerpY(fraction)-position.y,
                    cape.lerpZ(fraction)-position.z,player.bodyYaw,cape.getLerpedDistanceMoved(fraction),
                    cape.lerpMovement(fraction),player.isInSneakingPose()));
        }
        y(c,"food_level",food);y(c,"ground_speed2",horizontal);y(c,"time_delta",motion.ticks()/20);y(c,"fps",client.getCurrentFps());
        y(c,"input_vertical",inputDirection(delta.x,delta.z,player.getYaw(fraction),false));
        y(c,"input_horizontal",inputDirection(delta.x,delta.z,player.getYaw(fraction),true));
        y(c,"person_view",player==client.player&&player==client.getCameraEntity()?client.options.getPerspective().ordinal():2);
        y(c,"rendering_in_paperdoll",false);y(c,"rendering_in_inventory",false);
        y(c,"weather",client.world.isThundering()?2:client.world.isRaining()?1:0);
        c.stringQuery("ysm.dimension_name",client.world.getRegistryKey().getValue().toString());
        c.stringQuery("ysm.entity_type",Registries.ENTITY_TYPE.getId(player.getType()).getPath());c.stringQuery("ysm.texture_name",texture==null?"":texture);
        y(c,"is_player",true);y(c,"is_maid",false);y(c,"is_passenger",player.hasVehicle());y(c,"is_sleep",player.isSleeping());
        y(c,"is_sneak",player.isOnGround()&&player.isInSneakingPose());y(c,"eye_in_water",player.isSubmergedInWater());
        // Explicitly deprecated in OpenYSM 0306e1f: returns null, not a biome classification.
        c.query("ysm.biome_category",(Object)null);
        y(c,"is_open_air",client.world.isSkyVisible(player.getBlockPos())
                && client.world.getTopY(Heightmap.Type.MOTION_BLOCKING,player.getBlockX(),player.getBlockZ())<=player.getBlockY());
        y(c,"block_light",client.world.getLightLevel(LightType.BLOCK,player.getBlockPos()));y(c,"sky_light",client.world.getLightLevel(LightType.SKY,player.getBlockPos()));
        y(c,"frozen_ticks",player.getFrozenTicks());y(c,"air_supply",player.getAir());y(c,"delta_movement_length",player.getVelocity().length());
        y(c,"has_helmet",!player.getEquippedStack(EquipmentSlot.HEAD).isEmpty());y(c,"has_chest_plate",!player.getEquippedStack(EquipmentSlot.CHEST).isEmpty());
        y(c,"has_leggings",!player.getEquippedStack(EquipmentSlot.LEGS).isEmpty());y(c,"has_boots",!player.getEquippedStack(EquipmentSlot.FEET).isEmpty());
        y(c,"has_mainhand",!player.getMainHandStack().isEmpty());y(c,"has_offhand",!player.getOffHandStack().isEmpty());
        y(c,"has_elytra",player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER));
        y(c,"is_riptide",player.isUsingRiptide());y(c,"armor_value",player.getArmor());y(c,"hurt_time",player.hurtTime);
        y(c,"is_close_eyes",closeEyes(c.get("query.life_time")*20,player.getUuid().getLeastSignificantBits(),player.isSleeping()));y(c,"on_ladder",player.isClimbing());
        y(c,"ladder_facing",player.getClimbingPos().flatMap(pos->client.world.getBlockState(pos).getOrEmpty(net.minecraft.state.property.Properties.HORIZONTAL_FACING))
                .map(direction->direction.getHorizontalQuarterTurns()).orElse(0));y(c,"arrow_count",player.getStuckArrowCount());y(c,"stinger_count",player.getStingerCount());
        y(c,"xxa",player.sidewaysSpeed);y(c,"yya",player.upwardSpeed);y(c,"zza",player.forwardSpeed);
        y(c,"mainhand_charged_crossbow",charged(player.getMainHandStack()));y(c,"offhand_charged_crossbow",charged(player.getOffHandStack()));
        y(c,"is_fishing",player.fishHook!=null);y(c,"swinging",player.handSwinging);y(c,"swing_time",player.handSwingTicks);
        y(c,"swinging_arm",player.preferredHand==Hand.OFF_HAND?1:0);y(c,"attack_time",player.getHandSwingProgress(fraction));
        populateBedrockCompatibility(c,player,fraction);
        populateLivingSupplement(client,c,player,fraction);
        y(c,"has_left_shoulder_parrot",player.getLeftShoulderParrotVariant().isPresent());y(c,"has_right_shoulder_parrot",player.getRightShoulderParrotVariant().isPresent());
        c.stringQuery("ysm.left_shoulder_parrot_variant",player.getLeftShoulderParrotVariant().map(v->v.name().toLowerCase(Locale.ROOT)).orElse("empty"));
        c.stringQuery("ysm.right_shoulder_parrot_variant",player.getRightShoulderParrotVariant().map(v->v.name().toLowerCase(Locale.ROOT)).orElse("empty"));
        y(c,"attack_damage",player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE));y(c,"attack_speed",player.getAttributeValue(EntityAttributes.ATTACK_SPEED));
        y(c,"attack_knockback",player.getAttributeValue(EntityAttributes.ATTACK_KNOCKBACK));y(c,"movement_speed",player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED));
        y(c,"knockback_resistance",player.getAttributeValue(EntityAttributes.KNOCKBACK_RESISTANCE));y(c,"luck",player.getLuck());
        y(c,"block_reach",player.getAttributeValue(EntityAttributes.BLOCK_INTERACTION_RANGE));y(c,"entity_reach",player.getAttributeValue(EntityAttributes.ENTITY_INTERACTION_RANGE));
        y(c,"entity_gravity",player.getAttributeValue(EntityAttributes.GRAVITY));
        // Original declared fallbacks for extension attributes absent from native Fabric.
        y(c,"swim_speed",1);y(c,"step_height_addition",0);y(c,"nametag_distance",64);
        y(c,"elytra_rot_x",Math.toDegrees(player.elytraFlightController.leftWingPitch(fraction)));
        y(c,"elytra_rot_y",Math.toDegrees(player.elytraFlightController.leftWingYaw(fraction)));
        y(c,"elytra_rot_z",Math.toDegrees(player.elytraFlightController.leftWingRoll(fraction)));
        if(frame.shield.observed())y(c,"in_shield_block_cooldown",frame.shield.inCooldown(client.world.getTime()));
        y(c,"in_shield_block_cooldown_available",frame.shield.observed());y(c,"first_person_mod_hide",false);
        if(player==client.player) {
            c.stringQuery("ysm.hit_target_id",hitTargetId(client));
            // The reference's current hit_target_type method always returns an empty string.
            c.stringQuery("ysm.hit_target_type","");
        }
        populateDebugDisabled(c);
        populateMovement(c,player,motion,fraction,player!=client.player);
        populateControl(c,player,fraction,flying);
        c.functions(new VanillaYsmQueries(client,player,motion,position,c.nativeYsm()));
    }
    /** Source MovementQuery prioritizes observed deltas, and suppresses synthetic movement for remote players. */
    private static void populateMovement(Molang.Context c,Entity entity,Motion motion,float fraction,boolean remotePlayer) {
        Vec3d tickDelta=new Vec3d(entity.getX()-entity.lastX,entity.getY()-entity.lastY,entity.getZ()-entity.lastZ);
        Vec3d velocity=entity.getVelocity();
        double limb=entity instanceof LivingEntity living?Math.abs(living.limbAnimator.getAmplitude(fraction)):0;
        double speed=groundSpeed(motion,velocity,tickDelta,limb,remotePlayer);
        Vec3d delta=positionDelta(motion,velocity,tickDelta,remotePlayer);
        q(c,"ground_speed",speed);y(c,"ground_speed2",speed);
        q(c,"vertical_speed",verticalSpeed(motion,velocity.y,tickDelta.y));
        q(c,"position_delta_0",delta.x);q(c,"position_delta_1",delta.y);q(c,"position_delta_2",delta.z);
        y(c,"delta_movement_length",remotePlayer?new Vec3d(motion.x(),motion.y(),motion.z()).length():
                velocity.lengthSquared()>1e-8?velocity.length():delta.length());
    }
    static double motionGroundSpeed(Motion motion) {
        return motion.ticks()>1e-4?Math.hypot(motion.x(),motion.z())*20/motion.ticks():0;
    }
    /** MovementQuery's fallback is native entity data; remote walk must never use predicted momentum. */
    public record MovementFallback(double groundSpeed,double verticalSpeed) { }
    public static MovementFallback movementFallback(MinecraftClient client,LivingEntity entity) {
        if(entity==null)return new MovementFallback(0,0);
        Vec3d tickDelta=new Vec3d(entity.getX()-entity.lastX,entity.getY()-entity.lastY,entity.getZ()-entity.lastZ);
        Vec3d velocity=entity.getVelocity();
        boolean remote=entity instanceof PlayerEntity player&&player!=client.player;
        return new MovementFallback(groundSpeed(new Motion(0,0,0,0),velocity,tickDelta,
                Math.abs(entity.limbAnimator.getAmplitude(1)),remote),verticalSpeed(new Motion(0,0,0,0),velocity.y,tickDelta.y));
    }
    static double groundSpeed(Motion motion,Vec3d velocity,Vec3d tickDelta,double limb,boolean remotePlayer) {
        double tracked=motionGroundSpeed(motion);if(tracked>1e-4)return tracked;
        if(remotePlayer)return 0;
        if(Double.isFinite(limb)&&limb>1e-4)return limb;
        double speed=Math.hypot(velocity.x,velocity.z)*20;if(Double.isFinite(speed)&&speed>1e-4)return speed;
        speed=Math.hypot(tickDelta.x,tickDelta.z)*20;return Double.isFinite(speed)&&speed>0?speed:0;
    }
    static double verticalSpeed(Motion motion,double velocityY,double tickY) {
        if(motion.ticks()>1e-4&&Math.abs(motion.y())>1e-4)return motion.y()*20/motion.ticks();
        if(Double.isFinite(velocityY)&&Math.abs(velocityY)>.1)return velocityY*20;
        return Double.isFinite(tickY)?tickY*20:0;
    }
    static Vec3d positionDelta(Motion motion,Vec3d velocity,Vec3d tickDelta,boolean remotePlayer) {
        Vec3d tracked=new Vec3d(motion.x(),motion.y(),motion.z());
        if(tracked.lengthSquared()>1e-8)return tracked;
        if(remotePlayer)return Vec3d.ZERO;
        if(tickDelta.lengthSquared()>1e-8)return tickDelta;
        return velocity.lengthSquared()>1e-8?velocity.multiply(motion.ticks()>1e-4?motion.ticks():1):Vec3d.ZERO;
    }
    private static void populateControl(Molang.Context c,LivingEntity entity,float fraction,boolean flying) {
        Entity vehicle=entity.getVehicle();
        String control=controlState(new ControlSample(entity.isDead(),entity.isUsingRiptide(),entity.isSleeping(),
                entity.isSwimming(),entity.getPose()==EntityPose.SWIMMING,entity.isClimbing(),c.get("query.vertical_speed"),
                flying,entity.getPose()==EntityPose.GLIDING&&entity.isGliding(),entity.isTouchingWater(),entity.isOnGround(),
                entity.hurtTime>0,entity.isInSneakingPose(),entity.isSprinting(),c.get("query.ground_speed")>.05,
                vehicle!=null&&vehicle.isAlive()));
        for(String state:CONTROL_STATES)c.query("ctrl."+state,state.equals(control)?1d:0d);
    }
    private static void populateBedrockCompatibility(Molang.Context c,Entity entity,float fraction) {
        double pitch=c.get("ysm.head_pitch");
        y(c,"tcos0",bedrockTcos(c.get("query.life_time"),entity.distanceTraveled));
        y(c,"map_angle",Math.max(0,Math.min(1,1-pitch/45.1)));
        y(c,"modified_move_speed",entity.distanceTraveled*20);
        if(entity instanceof LivingEntity living) {
            y(c,"swim_amount",living.isSwimming());y(c,"item_use_normalized",living.isUsingItem());
            y(c,"is_holding_right",!living.getMainHandStack().isEmpty());y(c,"is_holding_left",!living.getOffHandStack().isEmpty());
        }
        if(entity instanceof PlayerEntity player) {
            y(c,"target_x_rotation",pitch);y(c,"target_y_rotation",c.get("ysm.head_yaw"));
            y(c,"sleep_rotation",0);y(c,"item_is_charged",charged(player.getOffHandStack()));
        } else for(String key:List.of("target_x_rotation","target_y_rotation","sleep_rotation","item_is_charged"))c.query("ysm."+key,(Object)null);
    }
    static double bedrockTcos(double seconds,double moveDistance) {
        return Math.cos(seconds*103.2*Math.PI/180)*Math.min(1,moveDistance*4)*20;
    }
    private static void populateLivingSupplement(MinecraftClient client,Molang.Context c,LivingEntity entity,float fraction) {
        boolean swinging=YsmNativeInputState.isSwinging(client,entity,Hand.MAIN_HAND)||YsmNativeInputState.isSwinging(client,entity,Hand.OFF_HAND);
        q(c,"is_swinging",swinging);q(c,"swing_time",YsmNativeInputState.swingTicks(client,entity,fraction)/20d);
        q(c,"attack_time",YsmNativeInputState.attackProgress(client,entity,fraction));
        Hand used=YsmNativeInputState.usedHand(client,entity);boolean intent=YsmNativeInputState.isUsing(client,entity,used);
        int useTicks=YsmNativeInputState.useTicks(client,entity);
        q(c,"is_using_item",intent);q(c,"item_in_use_duration",useTicks/20d);
        q(c,"item_remaining_use_duration",entity.isUsingItem()?entity.getItemUseTimeLeft()/20d:intent?Math.max(1,6-useTicks)/20d:0);
        y(c,"is_fishing",entity instanceof PlayerEntity player&&player.fishHook!=null);
        ItemStack item=entity.getMainHandStack();Set<String> tags=new HashSet<>();item.streamTags().forEach(tag->tags.add(tag.id().toString()));
        boolean using=entity.isUsingItem()&&entity.getItemUseTimeLeft()>0&&entity.getActiveHand()==Hand.MAIN_HAND;
        boolean attack=entity.handSwinging&&entity.preferredHand==Hand.MAIN_HAND;
        VanillaYsmWeaponQueries.populate(c,new VanillaYsmWeaponQueries.Sample(itemKind(Registries.ITEM.getId(item.getItem()).toString(),tags),
                using,item.getUseAction().name().toLowerCase(Locale.ROOT),attack,entity.isUsingRiptide(),entity.hasVehicle(),entity.isGliding(),
                entity.isOnGround(),Math.hypot(entity.getVelocity().x,entity.getVelocity().z),entity.getVelocity().y,entity.fallDistance,
                Math.max(0,entity.getItemUseTime()+fraction),Math.max(0,entity.handSwingTicks+fraction),item.getMaxUseTime(entity)));
    }
    private static void populateDebugDisabled(Molang.Context context) {
        for(String name:List.of("dump_mods","dump_effects","dump_biome"))context.query("ysm."+name,(Object)null);
    }
    static void clearProjectileQueries(Molang.Context c) {
        for(String key:List.of("projectile_owner","throwable_item","hooked_in","is_biting","on_ground_time","in_ground","is_spectral_arrow","shoot_item_id"))
            c.query("ysm."+key,(Object)null);
    }
    private static void populateProjectile(MinecraftClient client,Entity entity,Molang.Context c) {
        if(!(entity instanceof ProjectileEntity projectile))return;
        Entity owner=projectile.getOwner();
        c.query("ysm.projectile_owner",owner==null?null:(Molang.ContextValue)()-> {
            Molang.Context child=new Molang.Context();child.enableNativeYsm();
            populateEntity(client,owner,owner instanceof PlayerEntity player?player.getHungerManager().getFoodLevel():20,"",child);
            return child;
        });
        if(entity instanceof ThrownItemEntity thrown&&thrown instanceof YsmThrownItemQueryInvoker nativeItem)
            c.stringQuery("ysm.throwable_item",Registries.ITEM.getId(nativeItem.mpa$defaultItem()).toString());
        if(entity instanceof FishingBobberEntity fishing) {
            Entity hooked=fishing.getHookedEntity();c.stringQuery("ysm.hooked_in",hooked==null?"":Registries.ENTITY_TYPE.getId(hooked.getType()).toString());
            if(fishing instanceof YsmFishingQueryAccessor state)y(c,"is_biting",state.mpa$isBiting());
        }
        if(entity instanceof PersistentProjectileEntity arrow) {
            if(arrow instanceof YsmArrowQueryAccessor state) {y(c,"on_ground_time",state.mpa$inGroundTime());y(c,"in_ground",state.mpa$isInGround());}
            y(c,"is_spectral_arrow",arrow instanceof SpectralArrowEntity);
            ItemStack weapon=arrow.getWeaponStack();
            c.stringQuery("ysm.shoot_item_id",weapon!=null&&!weapon.isEmpty()?Registries.ITEM.getId(weapon.getItem()).toString():
                    arrow instanceof YsmArrowFiringItem captured?captured.mpa$firingItemId():"");
        }
    }
    /** Exact native projectile predicate ordering; only arrow embedded state means ground. */
    public static String projectileAnimationState(Entity entity) {
        if(entity==null)return "";
        return projectileAnimationState(entity.isTouchingWater(),entity.isOnFire(),entity instanceof YsmArrowQueryAccessor arrow&&arrow.mpa$isInGround());
    }
    static String projectileAnimationState(boolean water,boolean fire,boolean inGround) {
        return water?"water":fire?"fire":inGround?"ground":"air";
    }
    /** Paperdolls are explicit source contexts. Call after binding their dummy or native preview entity. */
    public static void populatePreviewDefaults(Molang.Context context) {
        populatePreviewDefaults(context,true);
    }
    public static void populatePreviewDefaults(Molang.Context context,boolean paperdoll) {
        for(String state:CONTROL_STATES)context.query("ctrl."+state,0d);
        context.query("query.eye_target_x_rotation",0d);context.query("query.eye_target_y_rotation",0d);
        context.query("query.body_x_rotation",0d);context.query("query.body_y_rotation",0d);
        context.query("ysm.rendering_in_paperdoll",paperdoll?1d:0d);context.query("ysm.rendering_in_inventory",1d);
        context.query("ysm.is_first_person",0d);context.query("query.is_first_person",0d);context.query("ysm.person_view",2d);
    }
    /** One real vanilla snapshot for body and arm controllers, including armor and living passengers. */
    public static VanillaYsmAnimations.VanillaState vanillaState(LivingEntity entity) {
        return vanillaState(MinecraftClient.getInstance(),entity);
    }
    public static VanillaYsmAnimations.VanillaState vanillaState(MinecraftClient client,LivingEntity entity) {
        if(entity==null)return VanillaYsmAnimations.VanillaState.NONE;
        Entity vehicle=entity.getVehicle(),passenger=entity.getFirstPassenger();
        Map<String,VanillaYsmAnimations.ItemState> armor=new LinkedHashMap<>();
        for(String slot:List.of("head","chest","legs","feet"))armor.put(slot,itemState(entity.getEquippedStack(slot(slot))));
        boolean swinging=YsmNativeInputState.isSwinging(client,entity,Hand.MAIN_HAND)||YsmNativeInputState.isSwinging(client,entity,Hand.OFF_HAND);
        Hand swingHand=YsmNativeInputState.swingingHand(client,entity);
        return new VanillaYsmAnimations.VanillaState(entity.isDead(),entity.hurtTime,entity.isUsingRiptide(),entity.isSleeping(),entity.isSwimming(),
                itemState(entity.getMainHandStack()),itemState(entity.getOffHandStack()),
                !entity.isUsingItem()?VanillaYsmAnimations.Hand.NONE:entity.getActiveHand()==Hand.OFF_HAND?VanillaYsmAnimations.Hand.OFF:VanillaYsmAnimations.Hand.MAIN,
                Math.max(0,entity.getItemUseTime()),!swinging?VanillaYsmAnimations.Hand.NONE:
                    swingHand==Hand.OFF_HAND?VanillaYsmAnimations.Hand.OFF:VanillaYsmAnimations.Hand.MAIN,
                Math.max(0,(int)YsmNativeInputState.swingTicks(client,entity,0)),entity instanceof PlayerEntity player&&player.fishHook!=null,
                entityId(vehicle),entityTags(vehicle),vehicle!=null&&vehicle.isAlive(),
                vehicle instanceof LivingEntity living&&!living.getEquippedStack(EquipmentSlot.SADDLE).isEmpty(),
                armor,entityId(passenger),entityTags(passenger),passenger!=null&&passenger.isAlive(),YsmNativeInputState.swingSequence(client,entity));
    }
    private static String entityId(Entity entity) { return entity==null?"":Registries.ENTITY_TYPE.getId(entity.getType()).toString(); }
    private static Set<String> entityTags(Entity entity) {
        if(entity==null)return Set.of();Set<String> tags=new LinkedHashSet<>();
        entity.getType().getRegistryEntry().streamTags().limit(256).forEach(tag->tags.add(tag.id().toString()));return tags;
    }
    public static VanillaYsmAnimations.ItemState itemState(ItemStack stack) {
        if(stack.isEmpty())return VanillaYsmAnimations.ItemState.EMPTY;
        String id=Registries.ITEM.getId(stack.getItem()).toString();Set<String> tags=new LinkedHashSet<>();
        stack.streamTags().limit(256).forEach(tag->tags.add(tag.id().toString()));
        return new VanillaYsmAnimations.ItemState(id,tags,itemKind(id,tags),stack.getUseAction().name().toLowerCase(Locale.ROOT),
                false,charged(stack),31L*ItemStack.hashCode(stack)+stack.getCount(),stack.isDamaged(),new NativeStackKey(stack));
    }
    private static final class NativeStackKey implements VanillaYsmAnimations.TrackedItemComparison {
        private final ItemStack stack;NativeStackKey(ItemStack stack) { this.stack=stack; }
        @Override public boolean isDamaged() { return stack.isDamaged(); }
        @Override public boolean equals(Object other) { return other instanceof NativeStackKey key&&ItemStack.areEqual(stack,key.stack); }
        @Override public int hashCode() { return 31*ItemStack.hashCode(stack)+stack.getCount(); }
    }
    private static void q(Molang.Context c,String name,double value){c.query("query."+name,value);}
    private static void q(Molang.Context c,String name,boolean value){q(c,name,value?1:0);}
    private static void y(Molang.Context c,String name,double value){c.query("ysm."+name,value);}
    private static void y(Molang.Context c,String name,boolean value){y(c,name,value?1:0);}
    public static boolean charged(ItemStack stack){
        if(!Registries.ITEM.getId(stack.getItem()).equals(Identifier.ofVanilla("crossbow")))return false;
        var data=stack.get(DataComponentTypes.CHARGED_PROJECTILES);return data!=null&&!data.isEmpty();
    }
    /** Only an actual owner-identified successful block may call this; item cooldown is a different state. */
    public static void onShieldBlock(PlayerEntity player){frame(player).shield.hit(player.getEntityWorld().getTime());}
    public static void reset(){FRAMES.clear();}
    private static NativeFrame frame(Entity entity){
        NativeFrame existing=FRAMES.get(entity);if(existing!=null)return existing;
        if(FRAMES.size()>=512)FRAMES.clear();
        NativeFrame next=new NativeFrame();FRAMES.put(entity,next);return next;
    }
    private static final class NativeFrame { final MotionSampler motion=new MotionSampler();final ShieldWindow shield=new ShieldWindow(); }

    @Override public Object call(String name,List<Object> a) {
        if(--remaining<0)throw new IllegalArgumentException("Native model query budget exceeded");
        validateArguments(name,a.size());
        if(living==null&&requiresLiving(name))return null;
        // Mature native helpers return unavailable for an invalid slot or an out-of-range block lookup.
        if(nativeYsm&&slotFunction(name)&&!knownSlot(a.getFirst()))return null;
        if(nativeYsm&&relativeFunction(name)&&!nativeRelativeRange(number(a.get(0)),number(a.get(1)),number(a.get(2))))return null;
        return switch(name) {
            case "query.position"->axis(position,integer(a,0));
            case "query.position_delta"->axis(positionDelta(motion,entity.getVelocity(),new Vec3d(entity.getX()-entity.lastX,entity.getY()-entity.lastY,entity.getZ()-entity.lastZ),
                    entity instanceof PlayerEntity&&entity!=client.player),integer(a,0));
            case "query.rotation_to_camera"->cameraRotation(integer(a,0),client.gameRenderer.getCamera().getPitch(),client.gameRenderer.getCamera().getYaw());
            case "query.is_item_name_any"->!stack(a).isEmpty()&&a.subList(1,a.size()).stream().anyMatch(value->id(value).equals(Registries.ITEM.getId(stack(a).getItem())));
            case "query.equipped_item_all_tags"->!stack(a).isEmpty()&&a.subList(1,a.size()).stream().allMatch(value->stack(a).isIn(TagKey.of(RegistryKeys.ITEM,id(value))));
            case "query.equipped_item_any_tag"->!stack(a).isEmpty()&&a.subList(1,a.size()).stream().anyMatch(value->stack(a).isIn(TagKey.of(RegistryKeys.ITEM,id(value))));
            case "query.max_durability"->stack(a).getMaxDamage();
            case "query.remaining_durability"->stack(a).getMaxDamage()-stack(a).getDamage();
            case "ysm.get_equipped_item_name"->{
                ItemStack item="off_hand".equals(String.valueOf(a.get(0)))?living.getOffHandStack():living.getMainHandStack();
                yield item.isEmpty()?"empty":Registries.ITEM.getId(item.getItem()).getPath();
            }
            case "ysm.get_root_locator_offset"->0d; // Exact Sparkle stub, not an invented locator transform.
            case "ysm.equipped_enchantment_level"->enchantments(a);
            case "ysm.effect_level"->effectLevels(a);
            case "ysm.relative_block_name"->Registries.BLOCK.getId(entity.getEntityWorld().getBlockState(relative(a)).getBlock()).toString();
            case "ysm.relative_block_name_any"->a.subList(3,a.size()).stream().anyMatch(value->id(value).equals(Registries.BLOCK.getId(entity.getEntityWorld().getBlockState(relative(a)).getBlock())));
            case "query.relative_block_has_all_tags"->a.subList(3,a.size()).stream().allMatch(value->entity.getEntityWorld().getBlockState(relative(a)).isIn(TagKey.of(RegistryKeys.BLOCK,id(value))));
            case "query.relative_block_has_any_tag"->a.subList(3,a.size()).stream().anyMatch(value->entity.getEntityWorld().getBlockState(relative(a)).isIn(TagKey.of(RegistryKeys.BLOCK,id(value))));
            case "query.biome_has_all_tags"->a.stream().allMatch(value->entity.getEntityWorld().getBiome(entity.getBlockPos()).isIn(TagKey.of(RegistryKeys.BIOME,id(value))));
            case "query.biome_has_any_tag"->a.stream().anyMatch(value->entity.getEntityWorld().getBiome(entity.getBlockPos()).isIn(TagKey.of(RegistryKeys.BIOME,id(value))));
            case "ctrl.hold","ctrl.swing","ctrl.use","ctrl.armor"->matchesEquipment(name,a);
            case "ctrl.ride"->matchesRide(a);
            case "ysm.keyboard"->inputReady(client)&&a.stream().anyMatch(value->{int key=(int)number(value);return key>=32&&key<=348&&InputUtil.isKeyPressed(client.getWindow(),key);});
            case "ysm.mouse"->{int button=integer(a,0);yield inputReady(client)&&button>=0&&button<=7&&GLFW.glfwGetMouseButton(client.getWindow().getHandle(),button)==GLFW.GLFW_PRESS;}
            case "ysm.perlin_noise"->YsmNoise.sample(integer(a,0),number(argument(a,1)),a.size()>2?number(a.get(2)):0,a.size()>3?number(a.get(3)):0);
            case "ysm.mod_version"->{
                Object value=argument(a,0);
                if(!(value instanceof String mod)||!mod.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Model query mod ID");
                yield FabricLoader.getInstance().getModContainer(mod).map(container->container.getMetadata().getVersion().getFriendlyString()).orElse("");
            }
            // Original debug functions return null when debug mode is off; they do not expose host data.
            case "query.debug_output","ysm.dump_equipped_item","ysm.dump_relative_block"->null;
            case "ysm.has_any_curios"->0d; // Optional integration is absent.
            default->throw new IllegalArgumentException("Native model query is not bound: "+name);
        };
    }
    private int enchantments(List<Object>a){
        var values=stack(a).get(DataComponentTypes.ENCHANTMENTS);if(values==null)return 0;
        int total=0;
        for(Object requested:a.subList(1,a.size())) {Identifier requestedId=id(requested);
            total+=values.getEnchantments().stream().filter(entry->entry.matchesId(requestedId)).mapToInt(values::getLevel).sum();}
        return total;
    }
    private Object effectLevels(List<Object>a){
        Iterable<net.minecraft.entity.effect.StatusEffectInstance> effects;
        if(living!=null)effects=living.getStatusEffects();
        else if(entity instanceof net.minecraft.entity.projectile.ArrowEntity arrow) {
            var contents=arrow.getItemStack().get(DataComponentTypes.POTION_CONTENTS);
            effects=contents==null?List.of():contents.getEffects();
        } else return null;
        int total=0;
        for(Object requested:a) {Identifier requestedId=id(requested);
            for(var effect:effects)if(effect.getEffectType().matchesId(requestedId)){total+=effect.getAmplifier()+1;break;}}
        return total;
    }
    private boolean matchesEquipment(String function,List<Object>a){
        EquipmentSlot slot=slot(a.get(0));boolean armor=slot!=EquipmentSlot.MAINHAND&&slot!=EquipmentSlot.OFFHAND;
        if(function.equals("ctrl.armor")!=armor)return false;
        Hand hand=slot==EquipmentSlot.OFFHAND?Hand.OFF_HAND:Hand.MAIN_HAND;
        if(!handPredicate(function,YsmNativeInputState.isSwinging(client,living,hand),YsmNativeInputState.isUsing(client,living,hand),living.isSleeping()))return false;
        ItemStack item=living.getEquippedStack(slot);Set<String> tags=new HashSet<>();
        item.streamTags().forEach(tag->tags.add(tag.id().toString()));
        String itemId=Registries.ITEM.getId(item.getItem()).toString();
        return itemCondition(String.valueOf(a.get(1)),item.isEmpty(),itemId,tags,itemKind(itemId,tags),
                item.getUseAction().name().toLowerCase(Locale.ROOT),!armor);
    }
    private boolean matchesRide(List<Object>a){
        var related=switch(String.valueOf(a.get(0))){case "vehicle"->entity.getVehicle();case "passenger"->entity.getFirstPassenger();default->null;};
        if(related==null||!related.isAlive())return false;
        String condition=String.valueOf(a.get(1));
        if(condition.startsWith("$"))return condition.substring(1).equals(Registries.ENTITY_TYPE.getId(related.getType()).toString());
        return condition.startsWith("#")&&related.getType().getRegistryEntry().isIn(TagKey.of(RegistryKeys.ENTITY_TYPE,id(condition)));
    }
    private ItemStack stack(List<Object>a){return living.getEquippedStack(slot(argument(a,0)));}
    static boolean requiresLiving(String name){return Set.of("query.is_item_name_any","query.equipped_item_all_tags",
            "query.equipped_item_any_tag","query.max_durability","query.remaining_durability","ysm.equipped_enchantment_level",
            "ysm.get_equipped_item_name","ctrl.hold","ctrl.swing","ctrl.use","ctrl.armor").contains(name);}
    private static boolean slotFunction(String name) {
        return Set.of("query.is_item_name_any","query.equipped_item_all_tags","query.equipped_item_any_tag","query.max_durability",
                "query.remaining_durability","ysm.equipped_enchantment_level","ctrl.hold","ctrl.swing","ctrl.use","ctrl.armor").contains(name);
    }
    static boolean knownSlot(Object value) {return value instanceof String name&&Set.of("mainhand","offhand","head","chest","legs","feet").contains(name.toLowerCase(Locale.ROOT));}
    private static boolean relativeFunction(String name) {
        return Set.of("ysm.relative_block_name","ysm.relative_block_name_any","query.relative_block_has_all_tags","query.relative_block_has_any_tag").contains(name);
    }
    static boolean nativeRelativeRange(double x,double y,double z) {
        return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)&&Math.abs(x)<=5&&Math.abs(y)<=5&&Math.abs(z)<=5;
    }
    public static EquipmentSlot slot(Object value){
        if(!(value instanceof String name))throw new IllegalArgumentException("Equipment slot must be a name");
        return switch(name.toLowerCase(Locale.ROOT)){case "mainhand"->EquipmentSlot.MAINHAND;case "offhand"->EquipmentSlot.OFFHAND;
            case "head"->EquipmentSlot.HEAD;case "chest"->EquipmentSlot.CHEST;case "legs"->EquipmentSlot.LEGS;case "feet"->EquipmentSlot.FEET;
            default->throw new IllegalArgumentException("Unknown equipment slot");};
    }
    private BlockPos relative(List<Object>a){return new BlockPos(relativeCoordinate(entity.getX(),number(argument(a,0))),
            relativeCoordinate(entity.getY(),number(argument(a,1))),relativeCoordinate(entity.getZ(),number(argument(a,2))));}
    static int relativeCoordinate(double position,double offset){
        if(!Double.isFinite(position)||!Double.isFinite(offset)||Math.abs(offset)>32)throw new IllegalArgumentException("Relative model query exceeds 32 blocks");
        long coordinate=Math.round(position+offset-.5);
        if(coordinate<Integer.MIN_VALUE||coordinate>Integer.MAX_VALUE)throw new IllegalArgumentException("Relative model query coordinate");
        return (int)coordinate;
    }
    private static int integer(List<Object>a,int index){double value=number(argument(a,index));
        if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IllegalArgumentException("Native model query integer");return (int)value;}
    private static Object argument(List<Object>a,int index){if(index>=a.size())throw new IllegalArgumentException("Missing model query argument");return a.get(index);}
    private static double number(Object value){double result=value instanceof Number n?n.doubleValue():value instanceof Boolean b?b?1:0:Double.NaN;
        if(!Double.isFinite(result))throw new IllegalArgumentException("Native model query number");return result;}
    private static Identifier id(Object value){if(!(value instanceof String text)||text.length()>256)throw new IllegalArgumentException("Model query identifier");Identifier id=Identifier.tryParse(text.startsWith("#")?text.substring(1):text);if(id==null)throw new IllegalArgumentException("Invalid model query identifier");return id;}
    private static double axis(Vec3d value,int axis){return switch(axis){case 0->value.x;case 1->value.y;case 2->value.z;default->throw new IllegalArgumentException("Model query axis");};}
    private static String hitTargetId(MinecraftClient client){
        HitResult hit=client.crosshairTarget;if(hit==null||hit.getType()==HitResult.Type.MISS)return "";
        if(hit instanceof BlockHitResult block)return Registries.BLOCK.getId(client.world.getBlockState(block.getBlockPos()).getBlock()).toString();
        if(hit instanceof EntityHitResult entity)return Registries.ENTITY_TYPE.getId(entity.getEntity().getType()).toString();return "";
    }
    private static boolean inputReady(MinecraftClient client){return client.player!=null&&client.currentScreen==null&&client.getOverlay()==null&&client.mouse.isCursorLocked()&&client.isWindowFocused();}
    static void validateArguments(String name,int size){
        int minimum=switch(name){
            case "query.position","query.position_delta","query.rotation_to_camera","query.max_durability","query.remaining_durability","ysm.mouse","ysm.mod_version","ysm.get_equipped_item_name"->1;
            case "query.is_item_name_any","query.equipped_item_all_tags","query.equipped_item_any_tag","ysm.equipped_enchantment_level","ysm.perlin_noise","ctrl.hold","ctrl.swing","ctrl.use","ctrl.armor","ctrl.ride"->2;
            case "ysm.relative_block_name","ysm.dump_relative_block"->3;
            case "ysm.relative_block_name_any","query.relative_block_has_all_tags","query.relative_block_has_any_tag"->4;
            case "query.biome_has_all_tags","query.biome_has_any_tag","ysm.effect_level","ysm.keyboard","ysm.dump_equipped_item"->1;
            default->0;};
        boolean exact=Set.of("query.position","query.position_delta","query.rotation_to_camera","query.max_durability",
                "query.remaining_durability","ysm.mouse","ysm.mod_version","ysm.relative_block_name","ysm.get_equipped_item_name").contains(name);
        if(size<minimum||exact&&size!=minimum||size>256||name.startsWith("ctrl.")&&size>3)throw new IllegalArgumentException("Native model query arguments: "+name);
    }
    static double cameraRotation(int axis,double pitch,double yaw){return switch(axis){case 0->-pitch;case 1->180+yaw;default->throw new IllegalArgumentException("Camera rotation axis");};}
    static double timeOfDay(long timestamp){return ((float)(timestamp+6000L)/24000)%1;}
    static boolean closeEyes(double tick,long uuidLeastBits,boolean sleeping){double phase=(tick+(Math.abs(uuidLeastBits)%10))%90;return sleeping||phase>85&&phase<90;}
    static double inputDirection(double x,double z,double yaw,boolean horizontal){
        if(Math.hypot(x,z)<1e-4)return 0;
        double angle=Math.toRadians(Math.toDegrees(Math.atan2(z,x))-(90+yaw));return horizontal?Math.sin(angle):Math.cos(angle);
    }
    static double capeFlap(double x,double y,double z,double yaw,double walk,double bob,boolean crouching){
        double radians=Math.toRadians(yaw),vertical=Math.max(-6,Math.min(32,y*10));
        double forward=Math.max(0,Math.min(150,(x*Math.sin(radians)-z*Math.cos(radians))*100));
        return Math.max(0,Math.min(1,(6+forward/2+vertical+Math.sin(walk*6)*32*bob+(crouching?25:0))/108));
    }
    record Motion(double ticks,double x,double y,double z) { }
    static final class MotionSampler {
        private double tick=Double.NaN,x,y,z;private Motion previous=new Motion(0,0,0,0);
        Motion sample(double now,double x,double y,double z,double firstTicks){
            if(now==tick)return previous;
            double elapsed=now-tick,dx=x-this.x,dy=y-this.y,dz=z-this.z;
            boolean valid=Double.isFinite(tick)&&elapsed>0&&elapsed<=40&&dx*dx+dy*dy+dz*dz<=16;
            previous=new Motion(valid?elapsed:Math.min(40,Math.max(0,firstTicks)),valid?dx:0,valid?dy:0,valid?dz:0);
            tick=now;this.x=x;this.y=y;this.z=z;return previous;
        }
    }
    static final class ShieldWindow {
        private long hit=Long.MIN_VALUE;void hit(long tick){hit=tick;}
        boolean observed(){return hit!=Long.MIN_VALUE;}boolean inCooldown(long tick){return observed()&&tick>=hit&&tick-hit<=5;}
    }
    record ControlSample(boolean dead,boolean riptide,boolean sleeping,boolean swimming,boolean prone,boolean climbing,
                         double vertical,boolean flying,boolean gliding,boolean water,boolean grounded,boolean hurt,
                         boolean sneak,boolean sprint,boolean walking,boolean liveVehicle) { }
    static String controlState(ControlSample s){
        if(s.liveVehicle)return "";if(s.dead)return "death";if(s.riptide)return "riptide";if(s.sleeping)return "sleep";if(s.swimming)return "swim";
        if(s.prone)return s.walking?"climb":"climbing";if(s.climbing)return s.vertical>.01?"ladder_up":s.vertical<-.01?"ladder_down":"ladder_stillness";
        if(s.gliding)return "elytra_fly";if(s.flying)return "fly";if(s.water&&!s.grounded)return "swim_stand";
        if(s.hurt)return "attacked";if(!s.grounded&&!s.water)return "jump";if(s.grounded&&s.sneak)return s.walking?"sneak":"sneaking";
        if(s.grounded&&s.sprint&&s.walking)return "run";if(s.grounded&&s.walking)return "walk";return "idle";
    }
    static boolean handPredicate(String function,boolean swing,boolean using,boolean sleep){
        return switch(function){case "ctrl.swing"->swing&&!sleep;case "ctrl.use"->using&&!sleep;default->true;};
    }
    static boolean itemCondition(String selector,boolean empty,String itemId,Set<String> tags,String kind,String useAction,boolean types){
        if(selector.isBlank())return false;if(empty&&selector.equals("empty"))return true;
        if(selector.startsWith("$"))return selector.substring(1).equals(itemId);
        if(selector.startsWith("#"))return tags.contains(selector.substring(1));
        return types&&selector.startsWith(":")&&(!kind.isEmpty()&&selector.substring(1).equals(kind)||selector.substring(1).equals(useAction));
    }
    public static String itemKind(String id,Set<String> tags){
        if(id.equals("minecraft:trident")||tags.contains("c:tridents")||nativeTag(tags,"tridents"))return "spear";
        if(id.startsWith("minecraft:")&&id.endsWith("_spear")||tags.contains("minecraft:spears")||tags.contains("c:spears")||tags.contains("c:pikes")||nativeTag(tags,"lances"))return "lance";
        if(id.equals("minecraft:mace")||tags.contains("c:maces")||nativeTag(tags,"mace"))return "mace";
        for(String kind:List.of("sword","axe","pickaxe","shovel","hoe","shield","crossbow","bow","fishing_rod"))
            if(id.equals("minecraft:"+kind)||tags.contains("minecraft:"+kind+"s")||tags.contains("c:"+kind+"s")||nativeTag(tags,kind+"s"))return kind;
        if(id.equals("minecraft:splash_potion")||id.equals("minecraft:lingering_potion")||nativeTag(tags,"throwable_potion"))return "throwable_potion";
        return "";
    }
    private static boolean nativeTag(Set<String> tags,String path) {
        return tags.contains("ysm:"+path)||tags.contains("sparkle_morpher:"+path);
    }
}

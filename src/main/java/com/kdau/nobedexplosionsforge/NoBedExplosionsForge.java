package com.kdau.nobedexplosionsforge;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.block.HorizontalBlock;
import net.minecraft.entity.monster.MonsterEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.stats.Stats;
import net.minecraft.util.Direction;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.util.registry.MutableRegistry;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.DimensionType;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("nobedexplosionsforge")
public class NoBedExplosionsForge
{
    // TODO: Support the config options and commands from the Spigot version?

    private static final Logger LOGGER = LogManager.getLogger();

    private Field DimensionType__bedWorks;
    private Field PlayerEntity__sleepCounter;
    private Method ServerPlayerEntity__bedBlocked;
    private Method ServerPlayerEntity__bedInRange;

    public NoBedExplosionsForge()
    {
        // Get private class members via reflection.
        DimensionType__bedWorks = ObfuscationReflectionHelper.findField(DimensionType.class, "field_241500_t_");
        PlayerEntity__sleepCounter = ObfuscationReflectionHelper.findField(PlayerEntity.class, "field_71076_b");
        ServerPlayerEntity__bedBlocked = ObfuscationReflectionHelper.findMethod(ServerPlayerEntity.class, "func_241156_b_", BlockPos.class, Direction.class);
        ServerPlayerEntity__bedInRange = ObfuscationReflectionHelper.findMethod(ServerPlayerEntity.class, "func_241147_a_", BlockPos.class, Direction.class);

        // Register for events.
        MinecraftForge.EVENT_BUS.register(this);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event)
    {
        MutableRegistry<DimensionType> registry = DynamicRegistries.builtin().registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY);
        registry.keySet().forEach((key) ->
        {
            DimensionType entry = registry.get(key);
            if (!entry.bedWorks())
            {
                LOGGER.info(MessageFormat.format("Making beds work in {0}", key));
                try
                {
                    DimensionType__bedWorks.setBoolean(entry, true);
                }
                catch (IllegalAccessException e)
                {
                    LOGGER.warn("IllegalAccessException setting DimensionType.bedWorks", e);
                    return;
                }
            }
        });
    }

    @SubscribeEvent
    public void onSleepInBed(PlayerSleepInBedEvent event)
    {
        PlayerEntity playerCommon = event.getPlayer();
        BlockPos pos = event.getPos();
        Optional<BlockPos> optPos = event.getOptionalPos();
        Direction direction = playerCommon.level.getBlockState(pos).getValue(HorizontalBlock.FACING);

        // Don't intervene on the logical client.
        if (playerCommon.level.isClientSide)
            return;

        ServerPlayerEntity player = (ServerPlayerEntity) playerCommon;

        // Don't intervene if the player is asleep or dead.
        if (player.isSleeping() || !player.isAlive())
            return;

        // Don't intervene if the dimension is "natural".
        if (player.level.dimensionType().natural())
            return;

        // Otherwise, this is the point where the regular logic would reject with SleepResult.NOT_POSSIBLE_HERE. Since Forge's event isn't designed for a handler to affirmatively allow sleeping somewhere it would normally be impossible, we must perform the rest of the calling method's logic here and stop the calling method from proceeding.
        LOGGER.info(MessageFormat.format("Intervening for {0} sleeping in bed at {1} in {2}", player.getDisplayName().getString(), Vector3d.atLowerCornerOf(pos), player.level.dimension().location()));

        // Switch the reason if the bed is too far away or blocked.
        try
        {
            if (!(boolean) ServerPlayerEntity__bedInRange.invoke(player, pos, direction))
            {
                event.setResult(PlayerEntity.SleepResult.TOO_FAR_AWAY);
                return;
            }
            if ((boolean) ServerPlayerEntity__bedBlocked.invoke(player, pos, direction))
            {
                event.setResult(PlayerEntity.SleepResult.OBSTRUCTED);
                return;
            }
        }
        catch (IllegalAccessException e)
        {
            LOGGER.warn("IllegalAccessException calling ServerPlayerEntity.bedInRange or .bedBlocked", e);
            return;
        }
        catch (InvocationTargetException e)
        {
            LOGGER.warn("InvocationTargetException calling ServerPlayerEntity.bedInRange or .bedBlocked", e);
            return;
        }

        // For our purposes, don't set the respawn position like the regular
        // logic does here.

        // Switch the reason if it's not time to sleep or monsters are present.
        if (!net.minecraftforge.event.ForgeEventFactory.fireSleepingTimeCheck(player, optPos))
        {
            event.setResult(PlayerEntity.SleepResult.NOT_POSSIBLE_NOW);
            return;
        }
        if (!player.isCreative())
        {
            double rxz = 8.0D;
            double ry = 5.0D;
            Vector3d center = Vector3d.atBottomCenterOf(pos);
            List<MonsterEntity> list = player.level.getEntitiesOfClass(MonsterEntity.class, new AxisAlignedBB(center.x() - rxz, center.y() - ry, center.z() - rxz, center.x() + rxz, center.y() + ry, center.z() + rxz), (monster) ->
            {
                return monster.isPreventingPlayerRest(player);
            });
            if (!list.isEmpty())
            {
                event.setResult(PlayerEntity.SleepResult.NOT_SAFE);
                return;
            }
        }

        // Finally, we are sure that we can proceed.
        LOGGER.info("...and allowing the sleep");

        // Do the actual sleepy things.
        player.startSleeping(pos);
        try
        {
            PlayerEntity__sleepCounter.setInt(player, 0);
        }
        catch (IllegalAccessException e)
        {
            LOGGER.warn("IllegalAccessException setting PlayerEntity.sleepCounter", e);
            return;
        }
        player.awardStat(Stats.SLEEP_IN_BED);
        CriteriaTriggers.SLEPT_IN_BED.trigger(player);
        ((ServerWorld) player.level).updateSleepingPlayerList();

        // Ironically, we stop the calling method with the same enum value that we are trying to avoid it from choosing. This value conveniently has no associated HUD message.
        event.setResult(PlayerEntity.SleepResult.NOT_POSSIBLE_HERE);
    }

    @SubscribeEvent
    public void onExplosionStart(ExplosionEvent.Start event)
    {
        World world = event.getWorld ();
        Explosion explosion = event.getExplosion();

        // Don't intervene on the logical client.
        if (world.isClientSide)
            return;

        // Cancel any explosion caused by a bad respawn point (i.e., bed). These shouldn't happen due to the other changes, but this provides an extra layer of safety.
        if (explosion.getDamageSource().msgId == "badRespawnPoint")
        {
            LOGGER.info(MessageFormat.format("Cancelling explosion for bad respawn point at {0} in {1}", explosion.getPosition(), world.dimension().location()));
            event.setCanceled(true);
        }
    }
}

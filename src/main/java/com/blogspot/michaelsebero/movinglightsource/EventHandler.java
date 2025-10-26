package com.blogspot.michaelsebero.movinglightsource;

import com.blogspot.michaelsebero.movinglightsource.blocks.BlockMovingLightSource;
import com.blogspot.michaelsebero.movinglightsource.registries.BlockRegistry;
import com.blogspot.michaelsebero.movinglightsource.tileentities.TileEntityMovingLightSource;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.world.World;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent.OnConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.PlayerTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.WorldTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class EventHandler 
{
    // Cache to track last light block placement per entity item
    private static final Map<Integer, BlockPos> lastItemLightBlockPos = new HashMap<>();
    private static final Map<Integer, Block> lastItemLightBlockType = new HashMap<>();
    
    // Track which EntityItem "owns" each light block position
    private static final Map<BlockPos, Integer> itemLightBlockOwnership = new HashMap<>();
    
    // Cache for projectile lights (arrows, fireballs, etc.)
    private static final Map<Integer, BlockPos> lastProjectileLightBlockPos = new HashMap<>();
    private static final Map<BlockPos, Integer> projectileLightBlockOwnership = new HashMap<>();
    
    // Throttle for dropped items and projectiles
    private static final int ITEM_LIGHT_UPDATE_INTERVAL = 2;
    private static final int PROJECTILE_LIGHT_UPDATE_INTERVAL = 1; // Update every tick for fast movement
    
    @SubscribeEvent(priority=EventPriority.NORMAL, receiveCanceled=true)
    public void onEvent(RegistryEvent.NewRegistry event)
    {
        // Can create registries here if needed
    }
    
    @SubscribeEvent(priority=EventPriority.NORMAL, receiveCanceled=true)
    public void onEvent(LivingUpdateEvent event)
    {
        EntityLivingBase entity = event.getEntityLiving();
        
        // Only process on server side
        if (entity.world.isRemote) return;
        
        // Check if entity is burning and should emit light
        if (entity.isBurning() && MainMod.allowBurningEntitiesToGiveOffLight)
        {
            placeLightBlockForLivingEntity(entity, BlockRegistry.MOVING_LIGHT_SOURCE_15);
        }
        // Check if entity is holding a light source (for non-player entities)
        else if (!(entity instanceof EntityPlayer) && 
                 BlockMovingLightSource.isHoldingLightItem(entity) && 
                 MainMod.allowHeldItemsToGiveOffLight)
        {
            Block lightBlock = BlockMovingLightSource.lightBlockToPlace(entity);
            if (lightBlock != Blocks.AIR)
            {
                placeLightBlockForLivingEntity(entity, lightBlock);
            }
        }
    }
    
    /**
     * Handle EntityItem lighting in WorldTickEvent
     */
    @SubscribeEvent(priority=EventPriority.NORMAL, receiveCanceled=true)
    public void onEvent(WorldTickEvent event)
    {
        // Only process on server side at end of tick
        if (event.phase != TickEvent.Phase.END || event.world.isRemote) return;
        
        // Create a copy of the entity list to avoid ConcurrentModificationException
        List<Entity> entityListCopy;
        try
        {
            entityListCopy = new java.util.ArrayList<>(event.world.loadedEntityList);
        }
        catch (Exception e)
        {
            // If we can't copy the list, skip this tick
            return;
        }
        
        // Handle EntityItem lights if enabled
        if (MainMod.allowEntityItemsToGiveOffLight && 
            event.world.getTotalWorldTime() % ITEM_LIGHT_UPDATE_INTERVAL == 0)
        {
            Map<Integer, Boolean> seenItems = new HashMap<>();
            
            for (Entity entity : entityListCopy)
            {
                if (entity instanceof EntityItem)
                {
                    EntityItem entityItem = (EntityItem) entity;
                    seenItems.put(entityItem.getEntityId(), true);
                    handleEntityItemLight(entityItem);
                }
            }
            
            cleanupOrphanedItemLights(event.world, seenItems);
        }
        
        // Handle burning projectile lights if enabled
        if (MainMod.allowBurningEntitiesToGiveOffLight && 
            event.world.getTotalWorldTime() % PROJECTILE_LIGHT_UPDATE_INTERVAL == 0)
        {
            Map<Integer, Boolean> seenProjectiles = new HashMap<>();
            
            for (Entity entity : entityListCopy)
            {
                // Check if it's a projectile (not living, not item) and burning
                if (!(entity instanceof EntityLivingBase) && 
                    !(entity instanceof EntityItem) && 
                    entity.isBurning())
                {
                    seenProjectiles.put(entity.getEntityId(), true);
                    handleBurningProjectileLight(entity);
                }
            }
            
            cleanupOrphanedProjectileLights(event.world, seenProjectiles);
        }
    }
    
    /**
     * Clean up light blocks for items that no longer exist
     */
    private void cleanupOrphanedItemLights(World world, Map<Integer, Boolean> seenItems)
    {
        Iterator<Map.Entry<Integer, BlockPos>> iterator = lastItemLightBlockPos.entrySet().iterator();
        
        while (iterator.hasNext())
        {
            Map.Entry<Integer, BlockPos> entry = iterator.next();
            int entityId = entry.getKey();
            
            // If we didn't see this entity ID this tick, it's gone
            if (!seenItems.containsKey(entityId))
            {
                BlockPos pos = entry.getValue();
                if (pos != null)
                {
                    // Remove the light block if it exists and we own it
                    Block block = world.getBlockState(pos).getBlock();
                    if (block instanceof BlockMovingLightSource)
                    {
                        Integer owner = itemLightBlockOwnership.get(pos);
                        if (owner != null && owner == entityId)
                        {
                            // Check if there's a tile entity - if so, mark it as from an item
                            TileEntity te = world.getTileEntity(pos);
                            if (te instanceof TileEntityMovingLightSource)
                            {
                                ((TileEntityMovingLightSource) te).markAsItemLight();
                            }
                            world.setBlockToAir(pos);
                            itemLightBlockOwnership.remove(pos);
                        }
                    }
                }
                
                // Remove from caches
                iterator.remove();
                lastItemLightBlockType.remove(entityId);
            }
        }
    }
    
    /**
     * Clean up light blocks for projectiles that no longer exist
     */
    private void cleanupOrphanedProjectileLights(World world, Map<Integer, Boolean> seenProjectiles)
    {
        Iterator<Map.Entry<Integer, BlockPos>> iterator = lastProjectileLightBlockPos.entrySet().iterator();
        
        while (iterator.hasNext())
        {
            Map.Entry<Integer, BlockPos> entry = iterator.next();
            int entityId = entry.getKey();
            
            if (!seenProjectiles.containsKey(entityId))
            {
                BlockPos pos = entry.getValue();
                if (pos != null)
                {
                    Block block = world.getBlockState(pos).getBlock();
                    if (block instanceof BlockMovingLightSource)
                    {
                        Integer owner = projectileLightBlockOwnership.get(pos);
                        if (owner != null && owner == entityId)
                        {
                            world.setBlockToAir(pos);
                            projectileLightBlockOwnership.remove(pos);
                        }
                    }
                }
                
                iterator.remove();
            }
        }
    }
    
    /**
     * Handle lighting for a single EntityItem
     */
    private void handleEntityItemLight(EntityItem entityItem)
    {
        if (entityItem == null || entityItem.isDead) return;
        
        ItemStack stack = entityItem.getItem();
        
        // Check if the item should emit light
        if (!stack.isEmpty() && BlockMovingLightSource.LIGHT_SOURCE_MAP.containsKey(stack.getItem()))
        {
            Block lightBlock = BlockMovingLightSource.LIGHT_SOURCE_MAP.get(stack.getItem());
            if (lightBlock != null && lightBlock != Blocks.AIR)
            {
                placeItemLight(entityItem, lightBlock);
            }
        }
        else
        {
            // Item doesn't emit light - remove any existing light block
            removeItemLight(entityItem);
        }
    }
    
    /**
     * Handle lighting for burning projectiles (arrows, fireballs, etc.)
     */
    private void handleBurningProjectileLight(Entity projectile)
    {
        if (projectile == null || projectile.isDead || !projectile.isBurning()) 
        {
            removeProjectileLight(projectile);
            return;
        }
        
        placeProjectileLight(projectile, BlockRegistry.MOVING_LIGHT_SOURCE_15);
    }
    
    /**
     * Place light block for burning projectile
     */
    private void placeProjectileLight(Entity projectile, Block lightBlock)
    {
        int entityId = projectile.getEntityId();
        World world = projectile.world;
        
        // Determine projectile position
        int blockX = MathHelper.floor(projectile.posX);
        int blockY = MathHelper.floor(projectile.posY);
        int blockZ = MathHelper.floor(projectile.posZ);
        
        // Try positions: at projectile location and one block up
        BlockPos[] positionsToTry = {
            new BlockPos(blockX, blockY, blockZ),
            new BlockPos(blockX, blockY + 1, blockZ)
        };
        
        BlockPos lastPos = lastProjectileLightBlockPos.get(entityId);
        BlockPos targetPos = null;
        
        // Find a valid position to place the light
        for (BlockPos pos : positionsToTry)
        {
            Block blockAtLocation = world.getBlockState(pos).getBlock();
            
            if (blockAtLocation == Blocks.AIR || blockAtLocation instanceof BlockMovingLightSource)
            {
                targetPos = pos;
                break;
            }
        }
        
        // If no valid position found, keep trying
        if (targetPos == null)
        {
            return;
        }
        
        // Remove old light block if projectile moved to a different position
        if (lastPos != null && !lastPos.equals(targetPos))
        {
            removeProjectileLightAtPos(world, lastPos, entityId);
        }
        
        // Check if light already exists at target position
        Block blockAtLocation = world.getBlockState(targetPos).getBlock();
        
        if (blockAtLocation == Blocks.AIR)
        {
            // Empty space - place light block without tile entity
            world.setBlockState(targetPos, lightBlock.getDefaultState(), 3);
            lastProjectileLightBlockPos.put(entityId, targetPos);
            projectileLightBlockOwnership.put(targetPos, entityId);
        }
        else if (blockAtLocation instanceof BlockMovingLightSource)
        {
            // Already a light block - just update cache to maintain ownership
            lastProjectileLightBlockPos.put(entityId, targetPos);
            projectileLightBlockOwnership.put(targetPos, entityId);
        }
    }
    
    /**
     * Remove light block for projectile
     */
    private void removeProjectileLight(Entity projectile)
    {
        if (projectile == null) return;
        
        int entityId = projectile.getEntityId();
        BlockPos lastPos = lastProjectileLightBlockPos.get(entityId);
        
        if (lastPos != null)
        {
            removeProjectileLightAtPos(projectile.world, lastPos, entityId);
            lastProjectileLightBlockPos.remove(entityId);
        }
    }
    
    /**
     * Remove projectile light block at specific position
     */
    private void removeProjectileLightAtPos(World world, BlockPos pos, int entityId)
    {
        Block block = world.getBlockState(pos).getBlock();
        if (block instanceof BlockMovingLightSource)
        {
            Integer owner = projectileLightBlockOwnership.get(pos);
            if (owner == null || owner == entityId)
            {
                world.setBlockToAir(pos);
                projectileLightBlockOwnership.remove(pos);
            }
        }
    }
    
    /**
     * Place light block for EntityItem
     */
    private void placeItemLight(EntityItem entityItem, Block lightBlock)
    {
        int entityId = entityItem.getEntityId();
        World world = entityItem.world;
        
        // Determine item position
        int blockX = MathHelper.floor(entityItem.posX);
        int blockY = MathHelper.floor(entityItem.posY);
        int blockZ = MathHelper.floor(entityItem.posZ);
        
        // Try placing at item position first, then one block up if needed
        BlockPos[] positionsToTry = {
            new BlockPos(blockX, blockY, blockZ),
            new BlockPos(blockX, blockY + 1, blockZ)
        };
        
        BlockPos lastPos = lastItemLightBlockPos.get(entityId);
        Block lastBlock = lastItemLightBlockType.get(entityId);
        
        BlockPos targetPos = null;
        
        // Find a valid position to place the light
        for (BlockPos pos : positionsToTry)
        {
            Block blockAtLocation = world.getBlockState(pos).getBlock();
            
            if (blockAtLocation == Blocks.AIR || blockAtLocation instanceof BlockMovingLightSource)
            {
                targetPos = pos;
                break;
            }
        }
        
        // If no valid position found, keep trying
        if (targetPos == null)
        {
            return;
        }
        
        // Optimization: Only update if position or light level changed
        if (targetPos.equals(lastPos) && lightBlock == lastBlock)
        {
            // Position hasn't changed, but make sure tile entity knows about the item
            TileEntity te = world.getTileEntity(targetPos);
            if (te instanceof TileEntityMovingLightSource)
            {
                ((TileEntityMovingLightSource) te).setTrackedItem(entityItem);
            }
            return;
        }
        
        // Remove old light block if item moved
        if (lastPos != null && !lastPos.equals(targetPos))
        {
            removeItemLightAtPos(world, lastPos, entityId);
        }
        
        // Place or update light block at target position
        Block blockAtLocation = world.getBlockState(targetPos).getBlock();
        
        if (blockAtLocation == Blocks.AIR)
        {
            // Empty space - place light block
            world.setBlockState(targetPos, lightBlock.getDefaultState());
            
            // Set the tile entity to track this item
            TileEntity te = world.getTileEntity(targetPos);
            if (te instanceof TileEntityMovingLightSource)
            {
                ((TileEntityMovingLightSource) te).setTrackedItem(entityItem);
            }
            
            updateItemCache(entityId, targetPos, lightBlock);
            itemLightBlockOwnership.put(targetPos, entityId);
        }
        else if (blockAtLocation instanceof BlockMovingLightSource)
        {
            // Already a light block
            Integer owner = itemLightBlockOwnership.get(targetPos);
            float currentLight = blockAtLocation.getLightValue(blockAtLocation.getDefaultState());
            float desiredLight = lightBlock.getLightValue(lightBlock.getDefaultState());
            
            // Update if we own it or there's no owner
            if (owner == null || owner == entityId)
            {
                if (Math.abs(currentLight - desiredLight) > 0.001f)
                {
                    world.setBlockState(targetPos, lightBlock.getDefaultState());
                }
                
                // Make sure tile entity tracks this item
                TileEntity te = world.getTileEntity(targetPos);
                if (te instanceof TileEntityMovingLightSource)
                {
                    ((TileEntityMovingLightSource) te).setTrackedItem(entityItem);
                }
                
                updateItemCache(entityId, targetPos, lightBlock);
                itemLightBlockOwnership.put(targetPos, entityId);
            }
        }
    }
    
    /**
     * Remove light block for EntityItem
     */
    private void removeItemLight(EntityItem entityItem)
    {
        int entityId = entityItem.getEntityId();
        BlockPos lastPos = lastItemLightBlockPos.get(entityId);
        
        if (lastPos != null)
        {
            removeItemLightAtPos(entityItem.world, lastPos, entityId);
            clearItemCache(entityId);
        }
    }
    
    /**
     * Remove item light block at specific position
     */
    private void removeItemLightAtPos(World world, BlockPos pos, int entityId)
    {
        Block block = world.getBlockState(pos).getBlock();
        if (block instanceof BlockMovingLightSource)
        {
            Integer owner = itemLightBlockOwnership.get(pos);
            if (owner == null || owner == entityId)
            {
                // Mark tile entity as item light before removing
                TileEntity te = world.getTileEntity(pos);
                if (te instanceof TileEntityMovingLightSource)
                {
                    ((TileEntityMovingLightSource) te).markAsItemLight();
                }
                world.setBlockToAir(pos);
                itemLightBlockOwnership.remove(pos);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @SubscribeEvent(priority=EventPriority.NORMAL, receiveCanceled=true)
    public void onEvent(PlayerTickEvent event)
    {        
        // Handle server-side light placement
        if (event.phase == TickEvent.Phase.START && !event.player.world.isRemote)
        {
            handlePlayerLightPlacement(event.player);
        }
    }
    
    /**
     * Handle light block placement for players holding light sources
     */
    private void handlePlayerLightPlacement(EntityPlayer player)
    {
        if (!BlockMovingLightSource.isHoldingLightItem(player) || !MainMod.allowHeldItemsToGiveOffLight)
        {
            return;
        }
        
        // Determine player position (foot level)
        int blockX = MathHelper.floor(player.posX);
        int blockY = MathHelper.floor(player.posY - 0.2D - player.getYOffset());
        int blockZ = MathHelper.floor(player.posZ);
        BlockPos targetPos = new BlockPos(blockX, blockY, blockZ).up();
        
        // Determine which light block should be placed
        Block desiredLightBlock = BlockMovingLightSource.lightBlockToPlace(player);
        Block blockAtLocation = player.world.getBlockState(targetPos).getBlock();
        
        if (blockAtLocation == Blocks.AIR)
        {
            // Empty space - place light block
            player.world.setBlockState(targetPos, desiredLightBlock.getDefaultState());
        }
        else if (blockAtLocation instanceof BlockMovingLightSource)
        {
            // Already a light block - check if light level needs updating
            float currentLight = blockAtLocation.getLightValue(blockAtLocation.getDefaultState());
            float desiredLight = desiredLightBlock.getLightValue(desiredLightBlock.getDefaultState());
            
            if (Math.abs(currentLight - desiredLight) > 0.001f)
            {
                player.world.setBlockState(targetPos, desiredLightBlock.getDefaultState());
            }
        }
    }
    
    /**
     * Place light blocks for non-player living entities
     */
    private void placeLightBlockForLivingEntity(EntityLivingBase entity, Block lightBlock)
    {
        if (entity == null || lightBlock == null) return;
        
        // Determine entity position
        int blockX = MathHelper.floor(entity.posX);
        int blockY = MathHelper.floor(entity.posY - 0.2D);
        int blockZ = MathHelper.floor(entity.posZ);
        BlockPos targetPos = new BlockPos(blockX, blockY, blockZ).up();
        
        Block blockAtLocation = entity.world.getBlockState(targetPos).getBlock();
        
        if (blockAtLocation == Blocks.AIR)
        {
            entity.world.setBlockState(targetPos, lightBlock.getDefaultState());
        }
        else if (blockAtLocation instanceof BlockMovingLightSource)
        {
            float currentLight = blockAtLocation.getLightValue(blockAtLocation.getDefaultState());
            float desiredLight = lightBlock.getLightValue(lightBlock.getDefaultState());
            
            if (Math.abs(currentLight - desiredLight) > 0.001f)
            {
                entity.world.setBlockState(targetPos, lightBlock.getDefaultState());
            }
        }
    }
    
    /**
     * Update item light block cache
     */
    private void updateItemCache(int entityId, BlockPos pos, Block block)
    {
        lastItemLightBlockPos.put(entityId, pos);
        lastItemLightBlockType.put(entityId, block);
    }
    
    /**
     * Clear cache for item
     */
    private void clearItemCache(int entityId)
    {
        BlockPos oldPos = lastItemLightBlockPos.remove(entityId);
        lastItemLightBlockType.remove(entityId);
        
        if (oldPos != null)
        {
            Integer owner = itemLightBlockOwnership.get(oldPos);
            if (owner != null && owner == entityId)
            {
                itemLightBlockOwnership.remove(oldPos);
            }
        }
    }
    
    @SubscribeEvent(priority=EventPriority.NORMAL, receiveCanceled=true)
    public void onEvent(AttackEntityEvent event)
    {
        EntityPlayer player = event.getEntityPlayer();
        
        if (player.world.isRemote) return;
        
        if (player.getHeldItemMainhand().getItem() == Item.getItemFromBlock(Blocks.TORCH))
        {
            if (MainMod.allowTorchesToBurnEntities && event.getTarget() != null)
            {
                event.getTarget().setFire(10);
            }
        }
    }

    @SubscribeEvent(priority=EventPriority.NORMAL, receiveCanceled=true)
    public void onEvent(OnConfigChangedEvent eventArgs) 
    {
        if (eventArgs.getModID().equals(MainMod.MODID))
        {
            System.out.println("Config changed for mod: " + eventArgs.getModID());
            MainMod.config.save();
            MainMod.proxy.syncConfig();
            
            // Clear caches when config changes
            lastItemLightBlockPos.clear();
            lastItemLightBlockType.clear();
            itemLightBlockOwnership.clear();
            lastProjectileLightBlockPos.clear();
            projectileLightBlockOwnership.clear();
            BlockMovingLightSource.clearCache();
        }
    }
}
